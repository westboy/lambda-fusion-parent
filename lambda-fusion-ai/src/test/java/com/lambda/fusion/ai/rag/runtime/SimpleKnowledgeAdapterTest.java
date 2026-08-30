package com.lambda.fusion.ai.rag.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lambda.cloud.lucene.manager.LuceneManagerFactory;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.rag.model.entity.KnowledgeBaseEntity;
import com.lambda.fusion.ai.rag.service.KnowledgeBaseService;
import com.lambda.fusion.ai.rag.store.LuceneKnowledgeIndex;
import com.lambda.fusion.ai.runtime.EmbeddingModelResolver;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.InMemoryStore;
import io.agentscope.core.rag.store.VDBStoreBase;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证 {@link SimpleKnowledgeAdapter} 抽出的纯逻辑：跨知识库合并按分数降序截断、
 * 知识库级 limit/scoreThreshold 覆盖全局默认值、向量库后端按类型分流
 * （MEMORY 零配置可用 / 未知类型拒绝）。不连真实 pgvector。
 *
 * @author Jin
 */
@SuppressWarnings("removal")
class SimpleKnowledgeAdapterTest {

    @Test
    void mergeSortsByScoreDescending() {
        List<RetrievedChunk> merged = new ArrayList<>(List.of(
                chunk("low", 0.3, "kb1", "doc1"), chunk("high", 0.9, "kb2", "doc2"), chunk("mid", 0.6, "kb1", "doc1")));

        List<RetrievedChunk> result = SimpleKnowledgeAdapter.mergeAndTruncate(merged, 5);

        assertThat(result).extracting(RetrievedChunk::content).containsExactly("high", "mid", "low");
    }

    @Test
    void mergeTruncatesToLimit() {
        List<RetrievedChunk> merged = new ArrayList<>(List.of(
                chunk("a", 0.9, "kb1", "doc1"), chunk("b", 0.8, "kb1", "doc1"), chunk("c", 0.7, "kb2", "doc2")));

        List<RetrievedChunk> result = SimpleKnowledgeAdapter.mergeAndTruncate(merged, 2);

        assertThat(result).extracting(RetrievedChunk::content).containsExactly("a", "b");
    }

    @Test
    void mergeKeepsAllWhenBelowLimit() {
        List<RetrievedChunk> merged = new ArrayList<>(List.of(chunk("a", 0.9, "kb1", "doc1")));

        assertThat(SimpleKnowledgeAdapter.mergeAndTruncate(merged, 5)).hasSize(1);
    }

    @Test
    void hybridFusionRewardsChunksReturnedByBothChannels() {
        RetrievedChunk sharedVector = chunk("shared", 0.72, "kb1", "doc-shared");
        RetrievedChunk sharedFullText = chunk("shared", 8.4, "kb1", "doc-shared");

        List<RetrievedChunk> result = SimpleKnowledgeAdapter.fuseRankedLists(
                List.of(
                        List.of(sharedVector, chunk("vector-only", 0.70, "kb1", "doc-vector")),
                        List.of(chunk("full-text-only", 9.1, "kb1", "doc-full-text"), sharedFullText)),
                3);

        assertThat(result).extracting(RetrievedChunk::content).startsWith("shared");
        assertThat(result).extracting(RetrievedChunk::scoreType).containsOnly("RRF");
        assertThat(result.getFirst().score()).isGreaterThan(result.get(1).score());
    }

    @Test
    void kbLevelLimitOverridesDefault() {
        AiProperties.Rag rag = new AiProperties.Rag(); // defaultLimit = 5
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();

        assertThat(SimpleKnowledgeAdapter.resolveLimit(kb, rag)).isEqualTo(5);

        kb.setRetrieveLimit(3);
        assertThat(SimpleKnowledgeAdapter.resolveLimit(kb, rag)).isEqualTo(3);
    }

    @Test
    void kbLevelScoreThresholdOverridesDefault() {
        AiProperties.Rag rag = new AiProperties.Rag(); // defaultScoreThreshold = 0.5
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();

        assertThat(SimpleKnowledgeAdapter.resolveScoreThreshold(kb, rag)).isEqualTo(0.5);

        kb.setScoreThreshold(new BigDecimal("0.750"));
        assertThat(SimpleKnowledgeAdapter.resolveScoreThreshold(kb, rag)).isEqualTo(0.75);
    }

    @Test
    void overrideScoreThresholdTakesPrecedenceOverKbLevel() {
        AiProperties.Rag rag = new AiProperties.Rag();
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setScoreThreshold(new BigDecimal("0.750"));

        assertThat(SimpleKnowledgeAdapter.resolveScoreThreshold(kb, rag, new BigDecimal("0.320")))
                .isEqualTo(0.32);
        assertThat(SimpleKnowledgeAdapter.resolveScoreThreshold(kb, rag, null)).isEqualTo(0.75);
    }

    @Test
    void memoryStoreCreatedWithoutPgvectorConfig() {
        AiProperties.Rag rag = new AiProperties.Rag(); // store.type=MEMORY 默认，pgvector 未配置

        VDBStoreBase store = SimpleKnowledgeAdapter.createStore(rag, null, 1536);

        assertThat(store).isInstanceOf(InMemoryStore.class);
        assertThat(((InMemoryStore) store).getDimensions()).isEqualTo(1536);
    }

    @Test
    void pgvectorStoreRequiresJdbcUrl() {
        AiProperties.Rag rag = new AiProperties.Rag();
        rag.getStore().setType("PGVECTOR"); // jdbcUrl 未配置
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setVectorTable("ai_kb_x");

        assertThatThrownBy(() -> SimpleKnowledgeAdapter.createStore(rag, kb, 1536))
                .isInstanceOf(AiBusinessException.class);
    }

    @Test
    void oceanbaseStoreRequiresJdbcUrl() {
        AiProperties.Rag rag = new AiProperties.Rag();
        rag.getStore().setType("OCEANBASE");
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setVectorTable("ai_kb_1");

        assertThatThrownBy(() -> SimpleKnowledgeAdapter.createStore(rag, kb, 1536))
                .isInstanceOf(AiBusinessException.class);
    }

    @Test
    void unknownStoreTypeRejected() {
        AiProperties.Rag rag = new AiProperties.Rag();
        rag.getStore().setType("QDRANT");

        assertThatThrownBy(() -> SimpleKnowledgeAdapter.createStore(rag, null, 1536))
                .isInstanceOf(AiBusinessException.class);
    }

    @Test
    void overrideLimitTakesPrecedenceOverKbLevel() {
        AiProperties.Rag rag = new AiProperties.Rag(); // defaultLimit = 5
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setRetrieveLimit(3);

        // 调用方显式 limit（Agentic 工具）优先于知识库级与全局默认
        assertThat(SimpleKnowledgeAdapter.resolveLimit(kb, rag, 10)).isEqualTo(10);
        // null 时回落知识库级
        assertThat(SimpleKnowledgeAdapter.resolveLimit(kb, rag, null)).isEqualTo(3);
        // 合并截断条数：limit 优先，null 回落全局默认
        assertThat(SimpleKnowledgeAdapter.resolveFinalLimit(rag, 10)).isEqualTo(10);
        assertThat(SimpleKnowledgeAdapter.resolveFinalLimit(rag, null)).isEqualTo(5);
    }

    @Test
    void retrievalPreservesSourceAndChunkMetadata() {
        DocumentMetadata metadata = DocumentMetadata.builder()
                .content(TextBlock.builder().text("读取保持寄存器").build())
                .docId("doc1")
                .chunkId("doc1-1")
                .payload(Map.of("fileName", "设备协议.pdf", "chunkIndex", 1, "chunkCount", 4, "sectionPath", "第3章 / 第2节"))
                .build();
        Document document = new Document(metadata);
        document.setScore(0.86);

        RetrievedChunk result = SimpleKnowledgeAdapter.toRetrievedChunk(document, "kb1");

        assertThat(result.fileName()).isEqualTo("设备协议.pdf");
        assertThat(result.chunkId()).isEqualTo("doc1-1");
        assertThat(result.chunkIndex()).isEqualTo(1);
        assertThat(result.chunkCount()).isEqualTo(4);
        assertThat(result.sectionPath()).isEqualTo("第3章 / 第2节");
        assertThat(result.scoreType()).isEqualTo("COSINE");
    }

    @Test
    void fullTextModeWorksWithoutEmbeddingModel(@TempDir Path tempDir) throws Exception {
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        EmbeddingModelResolver embeddingModelResolver = mock(EmbeddingModelResolver.class);
        KnowledgeBaseEntity kb = fullTextKnowledgeBase();
        when(knowledgeBaseService.loadById("kb1")).thenReturn(kb);

        try (LuceneManagerFactory managerFactory = new LuceneManagerFactory(tempDir, SmartChineseAnalyzer::new)) {
            SimpleKnowledgeAdapter adapter = new SimpleKnowledgeAdapter(
                    knowledgeBaseService,
                    embeddingModelResolver,
                    new AiProperties(),
                    new LuceneKnowledgeIndex(managerFactory));
            adapter.addChunks(
                    "kb1",
                    "doc1",
                    "tenant1",
                    "设备指南.txt",
                    List.of(new IngestChunk("doc1-0", "设备复位操作需要长按复位键三秒。", 0, "故障处理")));

            List<RetrievedChunk> hits =
                    adapter.retrieve(List.of("kb1"), "设备复位", 5).block();
            RetrievalProbe probe = adapter.probe(kb, "设备复位", 5, null);

            assertThat(hits).isNotEmpty();
            assertThat(hits.getFirst().scoreType()).isEqualTo("BM25");
            assertThat(hits.getFirst().fileName()).isEqualTo("设备指南.txt");
            assertThat(probe.retrievalMode()).isEqualTo("FULL_TEXT");
            assertThat(probe.effectiveScoreThreshold()).isNull();
            verifyNoInteractions(embeddingModelResolver);
        }
    }

    @Test
    void fullTextResetDeletesLogicalIndexWithoutEmbeddingModel(@TempDir Path tempDir) throws Exception {
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        EmbeddingModelResolver embeddingModelResolver = mock(EmbeddingModelResolver.class);
        KnowledgeBaseEntity kb = fullTextKnowledgeBase();
        when(knowledgeBaseService.loadById("kb1")).thenReturn(kb);

        try (LuceneManagerFactory managerFactory = new LuceneManagerFactory(tempDir, SmartChineseAnalyzer::new)) {
            SimpleKnowledgeAdapter adapter = new SimpleKnowledgeAdapter(
                    knowledgeBaseService,
                    embeddingModelResolver,
                    new AiProperties(),
                    new LuceneKnowledgeIndex(managerFactory));
            adapter.addChunks(
                    "kb1", "doc1", "tenant1", "设备指南.txt", List.of(new IngestChunk("doc1-0", "设备复位操作。", 0, null)));

            adapter.resetIndexes("kb1", List.of("doc1"));

            assertThat(managerFactory.exists("rag/tenant1/kb1")).isFalse();
            verifyNoInteractions(embeddingModelResolver);
        }
    }

    @Test
    void fullTextModeReportsMissingLuceneConfiguration() {
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        EmbeddingModelResolver embeddingModelResolver = mock(EmbeddingModelResolver.class);
        KnowledgeBaseEntity kb = fullTextKnowledgeBase();
        SimpleKnowledgeAdapter adapter =
                new SimpleKnowledgeAdapter(knowledgeBaseService, embeddingModelResolver, new AiProperties(), null);

        assertThatThrownBy(() -> adapter.probe(kb, "设备复位", 5, null))
                .isInstanceOfSatisfying(AiBusinessException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AiErrorCode.KB_FULL_TEXT_NOT_CONFIGURED.getCode()));
        verifyNoInteractions(embeddingModelResolver);
    }

    @Test
    void nonPgvectorStoreAlwaysAlive() throws Exception {
        // 内存库无连接概念，始终视为可用，不会触发重建
        VDBStoreBase store = InMemoryStore.builder().dimensions(1536).build();

        assertThat(SimpleKnowledgeAdapter.isAlive(store)).isTrue();
    }

    private static RetrievedChunk chunk(String content, double score, String kbId, String docId) {
        return new RetrievedChunk(content, score, kbId, docId, docId + ".pdf", "0", 0, 1, null, "COSINE");
    }

    private static KnowledgeBaseEntity fullTextKnowledgeBase() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId("kb1");
        kb.setTenantId("tenant1");
        kb.setRetrievalMode("FULL_TEXT");
        kb.setEnabled(true);
        return kb;
    }
}
