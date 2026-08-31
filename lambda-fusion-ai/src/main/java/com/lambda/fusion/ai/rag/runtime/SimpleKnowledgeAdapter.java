package com.lambda.fusion.ai.rag.runtime;

import com.lambda.fusion.ai.AiConstants.KnowledgeRetrievalMode;
import com.lambda.fusion.ai.AiConstants.VectorStoreType;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.rag.model.entity.KnowledgeBaseEntity;
import com.lambda.fusion.ai.rag.service.KnowledgeBaseService;
import com.lambda.fusion.ai.rag.store.KnowledgeFullTextIndex;
import com.lambda.fusion.ai.rag.store.OceanBaseVectorStore;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 知识库默认适配器：VECTOR 委托 AgentScope {@link SimpleKnowledge}，FULL_TEXT 委托 Lucene，HYBRID
 * 对两路候选执行 RRF。业务侧只依赖 {@link KnowledgeRetriever}、{@link RetrievedChunk} 与 {@link IngestChunk}。
 *
 * @author Jin
 */
@Slf4j
@SuppressWarnings("removal")
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class SimpleKnowledgeAdapter implements KnowledgeRetriever {

    private static final int CONNECTION_VALID_CHECK_SECONDS = 2;
    private static final int RRF_K = 60;
    private static final int MIN_HYBRID_CANDIDATES = 20;
    private static final int HYBRID_CANDIDATE_MULTIPLIER = 4;

    private final KnowledgeBaseService knowledgeBaseService;
    private final EmbeddingModelResolver embeddingModelResolver;
    private final AiProperties aiProperties;
    private final KnowledgeFullTextIndex fullTextIndex;

    private final Map<String, SimpleKnowledge> knowledgeCache = new ConcurrentHashMap<>();
    private final Map<String, KnowledgeBaseEntity> kbCache = new ConcurrentHashMap<>();

    public SimpleKnowledgeAdapter(
            KnowledgeBaseService knowledgeBaseService,
            EmbeddingModelResolver embeddingModelResolver,
            AiProperties aiProperties,
            KnowledgeFullTextIndex fullTextIndex) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.embeddingModelResolver = embeddingModelResolver;
        this.aiProperties = aiProperties;
        this.fullTextIndex = fullTextIndex;
    }

    @Override
    public Mono<List<RetrievedChunk>> retrieve(List<String> kbIds, String query) {
        return retrieve(kbIds, query, null);
    }

    @Override
    public Mono<List<RetrievedChunk>> retrieve(List<String> kbIds, String query, Integer limit) {
        return Mono.fromCallable(() -> doRetrieve(kbIds, query, limit)).subscribeOn(Schedulers.boundedElastic());
    }

    private List<RetrievedChunk> doRetrieve(List<String> kbIds, String query, Integer limit) {
        AiProperties.Rag rag = aiProperties.getRag();
        List<List<RetrievedChunk>> rankedLists = new ArrayList<>();
        for (String kbId : kbIds) {
            KnowledgeBaseEntity kb = loadKb(kbId);
            if (kb == null || !Boolean.TRUE.equals(kb.getEnabled())) {
                continue;
            }
            try {
                KnowledgeRetrievalMode mode = resolveMode(kb, rag);
                rankedLists.add(
                        retrieveOne(kb, mode, query, resolveLimit(kb, rag, limit), resolveScoreThreshold(kb, rag)));
            } catch (Exception e) {
                // 单个知识库检索失败不影响其他知识库。
                log.warn("知识库 {} 检索失败，跳过: {}", kbId, e.getMessage());
            }
        }
        return mergeRankedLists(rankedLists, resolveFinalLimit(rag, limit));
    }

    /** 管理端诊断使用生产检索路径，但不吞掉底层异常。 */
    public RetrievalProbe probe(KnowledgeBaseEntity kb, String query, Integer limit, BigDecimal scoreThreshold) {
        AiProperties.Rag rag = aiProperties.getRag();
        KnowledgeRetrievalMode mode = resolveMode(kb, rag);
        int effectiveLimit = resolveLimit(kb, rag, limit);
        Double effectiveScoreThreshold =
                mode.isVectorRequired() ? resolveScoreThreshold(kb, rag, scoreThreshold) : null;
        long startedAt = System.nanoTime();
        List<RetrievedChunk> hits = retrieveOne(
                kb, mode, query, effectiveLimit, effectiveScoreThreshold != null ? effectiveScoreThreshold : 0d);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        return new RetrievalProbe(mode.getCode(), effectiveLimit, effectiveScoreThreshold, durationMs, hits);
    }

    private List<RetrievedChunk> retrieveOne(
            KnowledgeBaseEntity kb, KnowledgeRetrievalMode mode, String query, int limit, double vectorScoreThreshold) {
        if (mode == KnowledgeRetrievalMode.FULL_TEXT) {
            return mergeAndTruncate(new ArrayList<>(requireFullTextIndex().search(kb, query, limit)), limit);
        }
        if (mode == KnowledgeRetrievalMode.VECTOR) {
            return mergeAndTruncate(new ArrayList<>(retrieveVector(kb, query, limit, vectorScoreThreshold)), limit);
        }
        int candidateLimit = resolveHybridCandidateLimit(limit);
        return fuseRankedLists(
                List.of(
                        retrieveVector(kb, query, candidateLimit, vectorScoreThreshold),
                        requireFullTextIndex().search(kb, query, candidateLimit)),
                limit);
    }

    private List<RetrievedChunk> retrieveVector(
            KnowledgeBaseEntity kb, String query, int limit, double scoreThreshold) {
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

    /** 单一可比分数列表按分数降序截断；用于单知识库、单召回通道。 */
    static List<RetrievedChunk> mergeAndTruncate(List<RetrievedChunk> merged, int limit) {
        merged.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
        return merged.size() > limit ? new ArrayList<>(merged.subList(0, limit)) : merged;
    }

    /** 多知识库或多召回通道使用排名而非原始分数融合，避免 BM25 与余弦分数直接相加。 */
    static List<RetrievedChunk> fuseRankedLists(List<List<RetrievedChunk>> rankedLists, int limit) {
        Map<String, FusedCandidate> candidates = new LinkedHashMap<>();
        for (List<RetrievedChunk> rankedList : rankedLists) {
            for (int index = 0; index < rankedList.size(); index++) {
                RetrievedChunk chunk = rankedList.get(index);
                candidates
                        .computeIfAbsent(chunkKey(chunk), ignored -> new FusedCandidate(chunk))
                        .add(1d / (RRF_K + index + 1));
            }
        }
        return candidates.values().stream()
                .sorted(Comparator.comparingDouble(FusedCandidate::score).reversed())
                .limit(limit)
                .map(candidate -> candidate.chunk().withScore(candidate.score(), "RRF"))
                .toList();
    }

    private static List<RetrievedChunk> mergeRankedLists(List<List<RetrievedChunk>> rankedLists, int limit) {
        List<List<RetrievedChunk>> nonEmpty =
                rankedLists.stream().filter(list -> !list.isEmpty()).toList();
        if (nonEmpty.isEmpty()) {
            return List.of();
        }
        if (nonEmpty.size() == 1) {
            return mergeAndTruncate(new ArrayList<>(nonEmpty.getFirst()), limit);
        }
        return fuseRankedLists(nonEmpty, limit);
    }

    private static String chunkKey(RetrievedChunk chunk) {
        return chunk.kbId() + '\u0000' + chunk.docId() + '\u0000' + chunk.chunkId();
    }

    /** 按知识库检索模式写入所需索引；HYBRID 任一路失败都会尽力补偿已写入的数据。 */
    public void addChunks(String kbId, String docId, String tenantId, String fileName, List<IngestChunk> chunks) {
        KnowledgeBaseEntity kb = requireKb(kbId);
        KnowledgeRetrievalMode mode = resolveMode(kb, aiProperties.getRag());
        boolean vectorAttempted = false;
        boolean fullTextAttempted = false;
        try {
            if (mode.isVectorRequired()) {
                vectorAttempted = true;
                replaceVectorChunks(kb, docId, tenantId, fileName, chunks);
            }
            if (mode.isFullTextRequired()) {
                fullTextAttempted = true;
                requireFullTextIndex().replaceDocument(kb, docId, tenantId, fileName, chunks);
            }
        } catch (RuntimeException e) {
            compensateFailedWrite(kb, docId, vectorAttempted, fullTextAttempted, e);
            throw e;
        }
        cleanupInactiveIndexes(kb, mode, docId);
    }

    private void replaceVectorChunks(
            KnowledgeBaseEntity kb, String docId, String tenantId, String fileName, List<IngestChunk> chunks) {
        deleteVectorDocument(kb, docId);
        List<Document> documents = new ArrayList<>(chunks.size());
        Map<String, Object> documentPayload = new HashMap<>();
        documentPayload.put("kbId", kb.getId());
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

    private void compensateFailedWrite(
            KnowledgeBaseEntity kb,
            String docId,
            boolean vectorAttempted,
            boolean fullTextAttempted,
            RuntimeException original) {
        if (vectorAttempted) {
            try {
                deleteVectorDocument(kb, docId);
            } catch (RuntimeException cleanupError) {
                original.addSuppressed(cleanupError);
            }
        }
        if (fullTextAttempted && fullTextIndex != null) {
            try {
                fullTextIndex.deleteDocument(kb, docId);
            } catch (RuntimeException cleanupError) {
                original.addSuppressed(cleanupError);
            }
        }
    }

    /** 目标通道写入成功后清理旧策略遗留的索引；清理失败不影响新策略已经可检索。 */
    private void cleanupInactiveIndexes(KnowledgeBaseEntity kb, KnowledgeRetrievalMode mode, String docId) {
        if (!mode.isVectorRequired() && hasVectorConfiguration(kb)) {
            try {
                deleteVectorDocument(kb, docId);
            } catch (RuntimeException e) {
                log.warn("清理知识库 {} 文档 {} 的旧向量索引失败: {}", kb.getId(), docId, e.getMessage());
            }
        }
        if (!mode.isFullTextRequired() && fullTextIndex != null && fullTextIndex.exists(kb)) {
            try {
                fullTextIndex.deleteDocument(kb, docId);
            } catch (RuntimeException e) {
                log.warn("清理知识库 {} 文档 {} 的旧全文索引失败: {}", kb.getId(), docId, e.getMessage());
            }
        }
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
                asString(payload.get("sectionPath")),
                "COSINE");
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

    /** 删除指定文档的全部派生索引；任一必需后端失败都会向上抛出。 */
    public void deleteDocument(String kbId, String docId) {
        KnowledgeBaseEntity kb = loadKb(kbId);
        if (kb == null) {
            return;
        }
        RuntimeException failure = null;
        if (hasVectorConfiguration(kb)) {
            try {
                deleteVectorDocument(kb, docId);
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        KnowledgeRetrievalMode mode = resolveMode(kb, aiProperties.getRag());
        if (mode.isFullTextRequired() || (fullTextIndex != null && fullTextIndex.exists(kb))) {
            try {
                requireFullTextIndex().deleteDocument(kb, docId);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 清空当前检索模式使用的派生索引，为知识库级重建做准备。Lucene 全文索引按逻辑目录直接删除，
     * 因而不需要先打开可能由旧 Lucene 版本创建的索引；向量索引按文档 ID 删除且不会调用嵌入模型。
     */
    public void resetIndexes(String kbId, List<String> documentIds) {
        KnowledgeBaseEntity kb = requireKb(kbId);
        KnowledgeRetrievalMode mode = resolveMode(kb, aiProperties.getRag());
        try {
            // 先完成后端可用性校验，再执行清理，减少配置错误造成的部分清理。
            if (mode.isVectorRequired()) {
                knowledge(kb);
            }
            if (mode.isFullTextRequired()) {
                requireFullTextIndex();
            }
            if (mode.isVectorRequired()) {
                documentIds.forEach(docId -> deleteVectorDocument(kb, docId));
            }
            if (fullTextIndex != null && (mode.isFullTextRequired() || fullTextIndex.exists(kb))) {
                fullTextIndex.deleteKnowledgeBase(kb);
            }
        } catch (AiBusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AiBusinessException(AiErrorCode.KB_RETRIEVAL_FAILED, e);
        }
    }

    public void deleteKnowledgeBase(String kbId) {
        KnowledgeBaseEntity kb = loadKb(kbId);
        if (kb == null) {
            return;
        }
        if (fullTextIndex != null && fullTextIndex.exists(kb)) {
            fullTextIndex.deleteKnowledgeBase(kb);
        }
        evict(kbId);
    }

    /** 清除知识库实体、向量连接与全文索引句柄缓存。 */
    public void evict(String kbId) {
        KnowledgeBaseEntity kb = kbCache.remove(kbId);
        closeQuietly(kbId, knowledgeCache.remove(kbId));
        if (kb != null && fullTextIndex != null && fullTextIndex.exists(kb)) {
            fullTextIndex.evict(kb);
        }
    }

    private void deleteVectorDocument(KnowledgeBaseEntity kb, String docId) {
        knowledge(kb).getEmbeddingStore().delete(docId).block();
    }

    private void closeQuietly(String kbId, SimpleKnowledge knowledge) {
        if (knowledge != null && knowledge.getEmbeddingStore() instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("关闭知识库 {} 向量库连接失败: {}", kbId, e.getMessage());
            }
        }
    }

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

    private KnowledgeFullTextIndex requireFullTextIndex() {
        if (fullTextIndex == null) {
            throw new AiBusinessException(AiErrorCode.KB_FULL_TEXT_NOT_CONFIGURED);
        }
        return fullTextIndex;
    }

    private SimpleKnowledge knowledge(KnowledgeBaseEntity kb) {
        String kbId = kb.getId();
        SimpleKnowledge cached = knowledgeCache.get(kbId);
        if (cached != null && isAlive(cached)) {
            return cached;
        }
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

    static boolean isAlive(VDBStoreBase store) throws Exception {
        if (!(store instanceof PgVectorStore pgStore)) {
            return true;
        }
        return !pgStore.isClosed() && pgStore.getConnection().isValid(CONNECTION_VALID_CHECK_SECONDS);
    }

    private SimpleKnowledge buildKnowledge(KnowledgeBaseEntity kb) {
        if (!hasVectorConfiguration(kb)) {
            throw new AiBusinessException(AiErrorCode.KB_EMBEDDING_MODEL_REQUIRED);
        }
        EmbeddingModel embeddingModel = embeddingModelResolver.resolve(kb.getEmbeddingModelId(), kb.getDimensions());
        int dimensions = kb.getDimensions() != null ? kb.getDimensions() : embeddingModel.getDimensions();
        VDBStoreBase store = createStore(aiProperties.getRag(), kb, dimensions);
        return SimpleKnowledge.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(store)
                .build();
    }

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
        if (type == VectorStoreType.OCEANBASE) {
            AiProperties.Rag.OceanBase oceanBase = rag.getStore().getOceanBase();
            if (StringUtils.isBlank(oceanBase.getJdbcUrl())) {
                throw new AiBusinessException(AiErrorCode.KB_VECTOR_STORE_NOT_CONFIGURED);
            }
            try {
                return new OceanBaseVectorStore(
                        oceanBase.getJdbcUrl(),
                        oceanBase.getUsername(),
                        oceanBase.getPassword(),
                        kb.getVectorTable(),
                        dimensions);
            } catch (VectorStoreException e) {
                throw new AiBusinessException(AiErrorCode.CONFIGURATION_ERROR, e);
            }
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

    static KnowledgeRetrievalMode resolveMode(KnowledgeBaseEntity kb, AiProperties.Rag rag) {
        String code = StringUtils.defaultIfBlank(kb.getRetrievalMode(), rag.getDefaultRetrievalMode());
        KnowledgeRetrievalMode mode = KnowledgeRetrievalMode.of(code);
        if (mode == null) {
            throw new AiBusinessException(AiErrorCode.KB_RETRIEVAL_MODE_INVALID, code);
        }
        return mode;
    }

    private static boolean hasVectorConfiguration(KnowledgeBaseEntity kb) {
        return StringUtils.isNotBlank(kb.getEmbeddingModelId())
                && kb.getDimensions() != null
                && StringUtils.isNotBlank(kb.getVectorTable());
    }

    static int resolveLimit(KnowledgeBaseEntity kb, AiProperties.Rag rag) {
        return resolveLimit(kb, rag, null);
    }

    static int resolveLimit(KnowledgeBaseEntity kb, AiProperties.Rag rag, Integer overrideLimit) {
        int limit = overrideLimit != null
                ? overrideLimit
                : kb.getRetrieveLimit() != null ? kb.getRetrieveLimit() : rag.getDefaultLimit();
        if (limit <= 0) {
            throw new AiBusinessException(AiErrorCode.CONFIGURATION_ERROR, "知识库检索条数必须大于 0");
        }
        return limit;
    }

    static int resolveFinalLimit(AiProperties.Rag rag, Integer overrideLimit) {
        int limit = overrideLimit != null ? overrideLimit : rag.getDefaultLimit();
        if (limit <= 0) {
            throw new AiBusinessException(AiErrorCode.CONFIGURATION_ERROR, "知识库检索条数必须大于 0");
        }
        return limit;
    }

    static int resolveHybridCandidateLimit(int limit) {
        long expanded = Math.max(MIN_HYBRID_CANDIDATES, (long) limit * HYBRID_CANDIDATE_MULTIPLIER);
        return (int) Math.min(Integer.MAX_VALUE, expanded);
    }

    static double resolveScoreThreshold(KnowledgeBaseEntity kb, AiProperties.Rag rag) {
        return resolveScoreThreshold(kb, rag, null);
    }

    static double resolveScoreThreshold(
            KnowledgeBaseEntity kb, AiProperties.Rag rag, BigDecimal overrideScoreThreshold) {
        if (overrideScoreThreshold != null) {
            return overrideScoreThreshold.doubleValue();
        }
        return kb.getScoreThreshold() != null ? kb.getScoreThreshold().doubleValue() : rag.getDefaultScoreThreshold();
    }

    private static final class FusedCandidate {

        private final RetrievedChunk chunk;
        private double score;

        private FusedCandidate(RetrievedChunk chunk) {
            this.chunk = chunk;
        }

        private void add(double value) {
            score += value;
        }

        private RetrievedChunk chunk() {
            return chunk;
        }

        private double score() {
            return score;
        }
    }
}
