package com.lambda.fusion.ai.rag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.llm.service.LlmModelService;
import com.lambda.fusion.ai.rag.mapper.KnowledgeBaseMapper;
import com.lambda.fusion.ai.rag.model.TestKnowledgeRetrieval;
import com.lambda.fusion.ai.rag.model.entity.KnowledgeBaseEntity;
import com.lambda.fusion.ai.rag.runtime.RetrievalProbe;
import com.lambda.fusion.ai.rag.runtime.SimpleKnowledgeAdapter;
import com.lambda.fusion.ai.rag.service.KnowledgeDocumentService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 验证检索测试的知识库状态、RAG 装配和失败语义。 */
class KnowledgeBaseServiceImplTest {

    private final KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
    private final SimpleKnowledgeAdapter adapter = mock(SimpleKnowledgeAdapter.class);
    private final ObjectProvider<SimpleKnowledgeAdapter> adapterProvider = provider(adapter);
    private final KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
            mapper, mock(LlmModelService.class), adapterProvider, provider(mock(KnowledgeDocumentService.class)));

    @Test
    void retrievalTestUsesEnabledKnowledgeBaseAndTrimsQuery() {
        KnowledgeBaseEntity kb = knowledgeBase(true);
        TestKnowledgeRetrieval command = command("  如何复位设备？  ");
        command.setLimit(3);
        command.setScoreThreshold(new BigDecimal("0.35"));
        RetrievalProbe expected = new RetrievalProbe(3, 0.35, 12, List.of());
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
                mapper, mock(LlmModelService.class), emptyProvider, provider(mock(KnowledgeDocumentService.class)));
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
        return provider;
    }
}
