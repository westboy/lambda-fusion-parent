package com.lambda.fusion.ai.rag.runtime;

import com.lambda.fusion.ai.AiConstants.VectorStoreType;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.rag.model.entity.KnowledgeBaseEntity;
import com.lambda.fusion.ai.rag.service.KnowledgeBaseService;
import com.lambda.fusion.ai.runtime.EmbeddingModelResolver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.InMemoryStore;
import io.agentscope.core.rag.store.PgVectorStore;
import io.agentscope.core.rag.store.VDBStoreBase;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 基于 AgentScope {@link SimpleKnowledge} 的知识库适配器：集中转换 AgentScope 已弃用的
 * RAG 文档类型，业务侧只依赖 {@link KnowledgeRetriever}、{@link RetrievedChunk} 和
 * {@link IngestChunk}；每个知识库独立缓存配置和向量存储实例。
 *
 * @author Jin
 */
@Slf4j
@RequiredArgsConstructor
@SuppressWarnings("removal")
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class SimpleKnowledgeAdapter implements KnowledgeRetriever {

    /** 向量库连接有效性探测超时（秒）。 */
    private static final int CONNECTION_VALID_CHECK_SECONDS = 2;

    private final KnowledgeBaseService knowledgeBaseService;
    private final EmbeddingModelResolver embeddingModelResolver;
    private final AiProperties aiProperties;

    private final Map<String, SimpleKnowledge> knowledgeCache = new ConcurrentHashMap<>();
    private final Map<String, KnowledgeBaseEntity> kbCache = new ConcurrentHashMap<>();

    @Override
    public Mono<List<RetrievedChunk>> retrieve(List<String> kbIds, String query) {
        return retrieve(kbIds, query, null);
    }

    @Override
    public Mono<List<RetrievedChunk>> retrieve(List<String> kbIds, String query, Integer limit) {
        // Embedding 请求和向量检索可能阻塞，交给弹性线程池执行。
        return Mono.fromCallable(() -> doRetrieve(kbIds, query, limit)).subscribeOn(Schedulers.boundedElastic());
    }

    private List<RetrievedChunk> doRetrieve(List<String> kbIds, String query, Integer limit) {
        AiProperties.Rag rag = aiProperties.getRag();
        List<RetrievedChunk> merged = new ArrayList<>();
        for (String kbId : kbIds) {
            KnowledgeBaseEntity kb = loadKb(kbId);
            if (kb == null || !Boolean.TRUE.equals(kb.getEnabled())) {
                continue;
            }
            try {
                merged.addAll(retrieveOne(kb, query, resolveLimit(kb, rag, limit), resolveScoreThreshold(kb, rag)));
            } catch (Exception e) {
                // 单个知识库检索失败不影响其他知识库。
                log.warn("知识库 {} 检索失败，跳过: {}", kbId, e.getMessage());
            }
        }
        return mergeAndTruncate(merged, resolveFinalLimit(rag, limit));
    }

    /**
     * 使用生产检索路径执行单知识库诊断。与多知识库检索不同，本方法不吞掉底层异常，
     * 以便管理端区分“没有命中”与“检索失败”。
     */
    public RetrievalProbe probe(KnowledgeBaseEntity kb, String query, Integer limit, BigDecimal scoreThreshold) {
        AiProperties.Rag rag = aiProperties.getRag();
        int effectiveLimit = resolveLimit(kb, rag, limit);
        double effectiveScoreThreshold = resolveScoreThreshold(kb, rag, scoreThreshold);
        long startedAt = System.nanoTime();
        List<RetrievedChunk> hits = mergeAndTruncate(
                new ArrayList<>(retrieveOne(kb, query, effectiveLimit, effectiveScoreThreshold)), effectiveLimit);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        return new RetrievalProbe(effectiveLimit, effectiveScoreThreshold, durationMs, hits);
    }

    private List<RetrievedChunk> retrieveOne(KnowledgeBaseEntity kb, String query, int limit, double scoreThreshold) {
        RetrieveConfig config = RetrieveConfig.builder()
                .limit(limit)
                .scoreThreshold(scoreThreshold)
                .build();
        List<Document> docs = knowledge(kb).retrieve(query, config).block();
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> chunks = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            chunks.add(toRetrievedChunk(doc, kb.getId()));
        }
        return chunks;
    }

    /** 按分数降序排列检索结果，并截断到指定条数。 */
    static List<RetrievedChunk> mergeAndTruncate(List<RetrievedChunk> merged, int limit) {
        merged.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
        return merged.size() > limit ? new ArrayList<>(merged.subList(0, limit)) : merged;
    }

    /** 将文档切块转换为 AgentScope 文档并写入指定知识库。 */
    public void addChunks(String kbId, String docId, String tenantId, String fileName, List<IngestChunk> chunks) {
        KnowledgeBaseEntity kb = requireKb(kbId);
        List<Document> documents = new ArrayList<>(chunks.size());
        // tenantId 允许为空，不能使用 Map.of。
        Map<String, Object> documentPayload = new HashMap<>();
        documentPayload.put("kbId", kbId);
        documentPayload.put("tenantId", tenantId);
        documentPayload.put("fileName", fileName);
        for (IngestChunk chunk : chunks) {
            Map<String, Object> chunkPayload = new HashMap<>(documentPayload);
            chunkPayload.put("chunkIndex", chunk.chunkIndex());
            chunkPayload.put("chunkCount", chunks.size());
            if (StringUtils.isNotBlank(chunk.sectionPath())) {
                chunkPayload.put("sectionPath", chunk.sectionPath());
            }
            DocumentMetadata metadata = DocumentMetadata.builder()
                    .content(TextBlock.builder().text(chunk.text()).build())
                    .docId(docId)
                    .chunkId(chunk.chunkId())
                    .payload(chunkPayload)
                    .build();
            documents.add(new Document(metadata));
        }
        knowledge(kb).addDocuments(documents).block();
    }

    static RetrievedChunk toRetrievedChunk(Document document, String kbId) {
        DocumentMetadata metadata = document.getMetadata();
        Map<String, Object> payload = metadata.getPayload();
        Integer chunkIndex = asInteger(payload.get("chunkIndex"));
        if (chunkIndex == null) {
            chunkIndex = parseLegacyChunkIndex(metadata.getChunkId());
        }
        return new RetrievedChunk(
                metadata.getContentText(),
                document.getScore() != null ? document.getScore() : 0d,
                kbId,
                metadata.getDocId(),
                asString(payload.get("fileName")),
                metadata.getChunkId(),
                chunkIndex,
                asInteger(payload.get("chunkCount")),
                asString(payload.get("sectionPath")));
    }

    private static String asString(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer parseLegacyChunkIndex(String chunkId) {
        if (StringUtils.isBlank(chunkId)) {
            return null;
        }
        try {
            return Integer.valueOf(chunkId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 删除指定文档的全部向量切块。知识库不存在时不处理。 */
    public void deleteDocument(String kbId, String docId) {
        KnowledgeBaseEntity kb = loadKb(kbId);
        if (kb == null) {
            return;
        }
        try {
            knowledge(kb).getEmbeddingStore().delete(docId).block();
        } catch (Exception e) {
            log.warn("删除文档向量数据失败(kb={}, doc={}): {}", kbId, docId, e.getMessage());
        }
    }

    /** 清除知识库实体与向量存储缓存，并关闭已创建的存储连接。 */
    public void evict(String kbId) {
        kbCache.remove(kbId);
        closeQuietly(kbId, knowledgeCache.remove(kbId));
    }

    /** 静默关闭向量库连接；实例为空或不支持关闭时不处理。 */
    private void closeQuietly(String kbId, SimpleKnowledge knowledge) {
        if (knowledge != null && knowledge.getEmbeddingStore() instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("关闭知识库 {} 向量库连接失败: {}", kbId, e.getMessage());
            }
        }
    }

    /** 加载并缓存知识库；查询不到时不写入缓存。 */
    private KnowledgeBaseEntity loadKb(String kbId) {
        return kbCache.computeIfAbsent(kbId, id -> {
            try {
                return knowledgeBaseService.loadById(id);
            } catch (AiBusinessException e) {
                return null;
            }
        });
    }

    private KnowledgeBaseEntity requireKb(String kbId) {
        KnowledgeBaseEntity kb = loadKb(kbId);
        if (kb == null) {
            throw new AiBusinessException(AiErrorCode.KB_NOT_FOUND, kbId);
        }
        return kb;
    }

    private SimpleKnowledge knowledge(KnowledgeBaseEntity kb) {
        String kbId = kb.getId();
        SimpleKnowledge cached = knowledgeCache.get(kbId);
        if (cached != null && isAlive(cached)) {
            return cached;
        }
        // 缓存缺失或底层连接已失效（pgvector 为单条长连接，空闲会被服务端断开），
        // 按知识库维度串行重建，避免并发检索重复建连。
        synchronized (kbId.intern()) {
            cached = knowledgeCache.get(kbId);
            if (cached != null && isAlive(cached)) {
                return cached;
            }
            closeQuietly(kbId, cached);
            SimpleKnowledge rebuilt = buildKnowledge(kb);
            knowledgeCache.put(kbId, rebuilt);
            return rebuilt;
        }
    }

    private boolean isAlive(SimpleKnowledge knowledge) {
        try {
            return isAlive(knowledge.getEmbeddingStore());
        } catch (Exception e) {
            log.debug("知识库向量库连接探测失败，将重建: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 判断向量库连接是否仍然可用（抽出以便单测）。{@link PgVectorStore} 是构造时建立的
     * 单条 JDBC 长连接，无连接池/保活/重连，{@code ensureNotClosed} 只检查内存标志，
     * 故用 {@code isValid} 轻量探测底层连接，失效触发重建；非 pgvector 存储视为始终可用。
     */
    static boolean isAlive(VDBStoreBase store) throws Exception {
        if (!(store instanceof PgVectorStore pgStore)) {
            return true;
        }
        return !pgStore.isClosed() && pgStore.getConnection().isValid(CONNECTION_VALID_CHECK_SECONDS);
    }

    private SimpleKnowledge buildKnowledge(KnowledgeBaseEntity kb) {
        EmbeddingModel embeddingModel = embeddingModelResolver.resolve(kb.getEmbeddingModelId(), kb.getDimensions());
        int dimensions = kb.getDimensions() != null ? kb.getDimensions() : embeddingModel.getDimensions();
        VDBStoreBase store = createStore(aiProperties.getRag(), kb, dimensions);
        return SimpleKnowledge.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(store)
                .build();
    }

    /** 根据知识库与模块配置创建向量存储。 */
    static VDBStoreBase createStore(AiProperties.Rag rag, KnowledgeBaseEntity kb, int dimensions) {
        VectorStoreType type = VectorStoreType.of(rag.getStore().getType());
        if (type == null) {
            throw new AiBusinessException(
                    AiErrorCode.CONFIGURATION_ERROR,
                    "未知向量库类型: " + rag.getStore().getType());
        }
        if (type == VectorStoreType.MEMORY) {
            return InMemoryStore.builder().dimensions(dimensions).build();
        }
        AiProperties.Rag.PgVector pgvector = rag.getStore().getPgVector();
        if (StringUtils.isBlank(pgvector.getJdbcUrl())) {
            throw new AiBusinessException(AiErrorCode.KB_VECTOR_STORE_NOT_CONFIGURED);
        }
        try {
            return PgVectorStore.builder()
                    .jdbcUrl(pgvector.getJdbcUrl())
                    .username(pgvector.getUsername())
                    .password(pgvector.getPassword())
                    .schema(pgvector.getSchema())
                    .tableName(kb.getVectorTable())
                    .dimensions(dimensions)
                    .build();
        } catch (VectorStoreException e) {
            throw new AiBusinessException(AiErrorCode.CONFIGURATION_ERROR, e);
        }
    }

    /** 解析单个知识库的检索条数，知识库配置优先于全局配置。 */
    static int resolveLimit(KnowledgeBaseEntity kb, AiProperties.Rag rag) {
        return resolveLimit(kb, rag, null);
    }

    /** 解析单个知识库的检索条数，调用参数优先于知识库和全局配置。 */
    static int resolveLimit(KnowledgeBaseEntity kb, AiProperties.Rag rag, Integer overrideLimit) {
        if (overrideLimit != null) {
            return overrideLimit;
        }
        return kb.getRetrieveLimit() != null ? kb.getRetrieveLimit() : rag.getDefaultLimit();
    }

    /** 解析跨知识库合并后的结果上限，调用参数优先于全局配置。 */
    static int resolveFinalLimit(AiProperties.Rag rag, Integer overrideLimit) {
        return overrideLimit != null ? overrideLimit : rag.getDefaultLimit();
    }

    /** 解析检索分数阈值，知识库配置优先于全局配置。 */
    static double resolveScoreThreshold(KnowledgeBaseEntity kb, AiProperties.Rag rag) {
        return resolveScoreThreshold(kb, rag, null);
    }

    /** 解析检索分数阈值，调用参数优先于知识库和全局配置。 */
    static double resolveScoreThreshold(
            KnowledgeBaseEntity kb, AiProperties.Rag rag, BigDecimal overrideScoreThreshold) {
        if (overrideScoreThreshold != null) {
            return overrideScoreThreshold.doubleValue();
        }
        return kb.getScoreThreshold() != null ? kb.getScoreThreshold().doubleValue() : rag.getDefaultScoreThreshold();
    }
}
