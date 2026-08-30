package com.lambda.fusion.ai.rag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lambda.fusion.ai.AiConstants.DocumentStatus;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.rag.mapper.KnowledgeDocumentMapper;
import com.lambda.fusion.ai.rag.model.entity.KnowledgeDocumentEntity;
import com.lambda.fusion.ai.rag.runtime.SimpleKnowledgeAdapter;
import com.lambda.fusion.ai.rag.service.DocumentIngestionService;
import com.lambda.fusion.ai.rag.service.KnowledgeBaseService;
import com.lambda.fusion.ai.storage.AiFileStorageResolver;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class KnowledgeDocumentServiceImplTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        assistant.setCurrentNamespace(KnowledgeDocumentMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocumentEntity.class);
    }

    private final KnowledgeDocumentMapper mapper = mock(KnowledgeDocumentMapper.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final SimpleKnowledgeAdapter adapter = mock(SimpleKnowledgeAdapter.class);
    private final DocumentIngestionService ingestionService = mock(DocumentIngestionService.class);
    private final KnowledgeDocumentServiceImpl service = new KnowledgeDocumentServiceImpl(
            mapper,
            knowledgeBaseService,
            new AiProperties(),
            provider(adapter),
            provider(ingestionService),
            mock(AiFileStorageResolver.class));

    @Test
    void explicitRebuildIncludesReadyAndFailedDocuments() {
        KnowledgeDocumentEntity ready = document("doc-ready", DocumentStatus.READY);
        KnowledgeDocumentEntity failed = document("doc-failed", DocumentStatus.FAILED);
        when(mapper.selectList(any())).thenReturn(List.of(ready, failed));

        int queued = service.rebuildIndexesByKbId("kb-1");

        assertThat(queued).isEqualTo(2);
        verify(knowledgeBaseService).loadById("kb-1");
        verify(adapter).resetIndexes("kb-1", List.of("doc-ready", "doc-failed"));
        verify(mapper, times(2)).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(ingestionService).ingest("doc-ready");
        verify(ingestionService).ingest("doc-failed");
    }

    @Test
    void explicitRebuildRejectsConcurrentPendingDocumentBeforeClearingIndexes() {
        when(mapper.selectList(any())).thenReturn(List.of(document("doc-pending", DocumentStatus.PENDING)));

        assertThatThrownBy(() -> service.rebuildIndexesByKbId("kb-1"))
                .isInstanceOfSatisfying(AiBusinessException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AiErrorCode.KB_INDEX_REBUILD_CONFLICT.getCode()));

        verify(adapter, never()).resetIndexes(any(), any());
        verifyNoInteractions(ingestionService);
    }

    private static KnowledgeDocumentEntity document(String id, DocumentStatus status) {
        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
        document.setId(id);
        document.setKbId("kb-1");
        document.setStatus(status.getCode());
        document.setStorageType("LOCAL");
        document.setStoragePath("kb-1/" + id + ".txt");
        return document;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        when(provider.getObject()).thenReturn(value);
        return provider;
    }
}
