package com.lambda.fusion.ai.rag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import com.lambda.fusion.ai.llm.service.LlmModelService;
import com.lambda.fusion.ai.rag.mapper.KnowledgeBaseMapper;
import com.lambda.fusion.ai.rag.model.CreateKnowledgeBase;
import com.lambda.fusion.ai.rag.model.TestKnowledgeRetrieval;
import com.lambda.fusion.ai.rag.model.UpdateKnowledgeBase;
import com.lambda.fusion.ai.rag.model.entity.KnowledgeBaseEntity;
import com.lambda.fusion.ai.rag.runtime.RetrievalProbe;
import com.lambda.fusion.ai.rag.runtime.SimpleKnowledgeAdapter;
import com.lambda.fusion.ai.rag.service.KnowledgeDocumentService;
import com.lambda.fusion.core.utils.AuthUtils;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;

/** 验证检索测试的知识库状态、RAG 装配和失败语义。 */
class KnowledgeBaseServiceImplTest {

    private final KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
    private final LlmModelService modelService = mock(LlmModelService.class);
    private final SimpleKnowledgeAdapter adapter = mock(SimpleKnowledgeAdapter.class);
    private final ObjectProvider<SimpleKnowledgeAdapter> adapterProvider = provider(adapter);
    private final AiProperties aiProperties = new AiProperties();
    private final KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
    private final KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
            mapper, modelService, aiProperties, adapterProvider, provider(documentService));

    @Test
    void createMaterializesEmbeddingModelDefaultDimensions() {
        LlmModelEntity model = new LlmModelEntity();
        model.setId("embedding-1");
        model.setModelType(ModelType.EMBEDDING);
        model.setEmbeddingDimensions(1024);
        model.setEnabled(true);
        when(modelService.loadById("embedding-1")).thenReturn(model);
        CreateKnowledgeBase command = new CreateKnowledgeBase();
        command.setName("知识库");
        command.setEmbeddingModelId("embedding-1");

        try (MockedStatic<AuthUtils> auth = mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getTenantId).thenReturn("tenant-1");

            KnowledgeBaseEntity created = service.create(command);

            assertThat(created.getDimensions()).isEqualTo(1024);
            assertThat(created.getTenantId()).isEqualTo("tenant-1");
            assertThat(created.getRetrievalMode()).isEqualTo("VECTOR");
            verify(mapper).insert(same(created));
        }
    }

    @Test
    void createFullTextKnowledgeBaseWithoutEmbeddingModel() {
        CreateKnowledgeBase command = new CreateKnowledgeBase();
        command.setName("全文知识库");
        command.setRetrievalMode("FULL_TEXT");

        try (MockedStatic<AuthUtils> auth = mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getTenantId).thenReturn("tenant-1");

            KnowledgeBaseEntity created = service.create(command);

            assertThat(created.getRetrievalMode()).isEqualTo("FULL_TEXT");
            assertThat(created.getEmbeddingModelId()).isNull();
            assertThat(created.getDimensions()).isNull();
            assertThat(created.getVectorTable()).isNull();
            verify(mapper).insert(same(created));
            verifyNoInteractions(modelService);
        }
    }

    @Test
    void createVectorKnowledgeBaseRequiresEmbeddingModel() {
        CreateKnowledgeBase command = new CreateKnowledgeBase();
        command.setName("向量知识库");
        command.setRetrievalMode("VECTOR");

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOfSatisfying(AiBusinessException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AiErrorCode.KB_EMBEDDING_MODEL_REQUIRED.getCode()));
        verifyNoInteractions(modelService);
    }

    @Test
    void switchingFromFullTextToVectorConfiguresVectorIndexAndReingestsReadyDocuments() {
        KnowledgeBaseEntity existing = knowledgeBase(true);
        existing.setId("123");
        existing.setTenantId("tenant-1");
        existing.setRetrievalMode("FULL_TEXT");
        when(mapper.selectOne(any())).thenReturn(existing);
        LlmModelEntity model = new LlmModelEntity();
        model.setId("embedding-1");
        model.setModelType(ModelType.EMBEDDING);
        model.setEmbeddingDimensions(768);
        model.setEnabled(true);
        when(modelService.loadById("embedding-1")).thenReturn(model);
        UpdateKnowledgeBase command = new UpdateKnowledgeBase();
        command.setRetrievalMode("VECTOR");
        command.setEmbeddingModelId("embedding-1");

        service.update("123", command);

        assertThat(existing.getRetrievalMode()).isEqualTo("VECTOR");
        assertThat(existing.getEmbeddingModelId()).isEqualTo("embedding-1");
        assertThat(existing.getDimensions()).isEqualTo(768);
        assertThat(existing.getVectorTable()).isEqualTo("ai_kb_123");
        verify(mapper).updateById(same(existing));
        verify(adapter).evict("123");
        verify(documentService).reingestByKbId("123");
    }

    @Test
    void retrievalTestUsesEnabledKnowledgeBaseAndTrimsQuery() {
        KnowledgeBaseEntity kb = knowledgeBase(true);
        TestKnowledgeRetrieval command = command("  如何复位设备？  ");
        command.setLimit(3);
        command.setScoreThreshold(new BigDecimal("0.35"));
        RetrievalProbe expected = new RetrievalProbe("VECTOR", 3, 0.35, 12, List.of());
        when(mapper.selectOne(any())).thenReturn(kb);
        when(adapter.probe(same(kb), eq("如何复位设备？"), eq(3), eq(new BigDecimal("0.35"))))
                .thenReturn(expected);

        RetrievalProbe result = service.testRetrieval("kb-1", command);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void disabledKnowledgeBaseIsRejectedBeforeAdapterLookup() {
        when(mapper.selectOne(any())).thenReturn(knowledgeBase(false));

        assertThatThrownBy(() -> service.testRetrieval("kb-1", command("问题")))
                .isInstanceOfSatisfying(AiBusinessException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AiErrorCode.KB_DISABLED.getCode()));
        verify(adapter, never()).probe(any(), any(), any(), any());
    }

    @Test
    void unavailableRagAdapterIsReportedExplicitly() {
        ObjectProvider<SimpleKnowledgeAdapter> emptyProvider = provider(null);
        KnowledgeBaseServiceImpl disabledRagService = new KnowledgeBaseServiceImpl(
                mapper,
                mock(LlmModelService.class),
                new AiProperties(),
                emptyProvider,
                provider(mock(KnowledgeDocumentService.class)));
        when(mapper.selectOne(any())).thenReturn(knowledgeBase(true));

        assertThatThrownBy(() -> disabledRagService.testRetrieval("kb-1", command("问题")))
                .isInstanceOfSatisfying(AiBusinessException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AiErrorCode.KB_RAG_NOT_ENABLED.getCode()));
    }

    @Test
    void runtimeFailureIsNotReportedAsEmptyHits() {
        when(mapper.selectOne(any())).thenReturn(knowledgeBase(true));
        when(adapter.probe(any(), any(), any(), any())).thenThrow(new IllegalStateException("vector unavailable"));

        assertThatThrownBy(() -> service.testRetrieval("kb-1", command("问题")))
                .isInstanceOfSatisfying(AiBusinessException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AiErrorCode.KB_RETRIEVAL_FAILED.getCode()));
    }

    private static KnowledgeBaseEntity knowledgeBase(boolean enabled) {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId("kb-1");
        kb.setEnabled(enabled);
        return kb;
    }

    private static TestKnowledgeRetrieval command(String query) {
        TestKnowledgeRetrieval command = new TestKnowledgeRetrieval();
        command.setQuery(query);
        return command;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        if (value != null) {
            when(provider.getObject()).thenReturn(value);
        }
        return provider;
    }
}
