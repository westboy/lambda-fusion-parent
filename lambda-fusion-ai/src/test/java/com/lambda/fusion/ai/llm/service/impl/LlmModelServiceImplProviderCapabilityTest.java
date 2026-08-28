package com.lambda.fusion.ai.llm.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.MediaGenerationOperation;
import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.llm.mapper.LlmModelMapper;
import com.lambda.fusion.ai.llm.model.CreateLlmModel;
import com.lambda.fusion.ai.llm.model.UpdateLlmModel;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import com.lambda.fusion.ai.llm.model.entity.LlmProviderEntity;
import com.lambda.fusion.ai.llm.service.LlmProviderService;
import com.lambda.fusion.ai.runtime.event.ConfigChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class LlmModelServiceImplProviderCapabilityTest {

    private LlmModelMapper llmModelMapper;
    private LlmProviderService llmProviderService;
    private ApplicationEventPublisher eventPublisher;
    private LlmModelServiceImpl service;

    @BeforeEach
    void setUp() {
        llmModelMapper = mock(LlmModelMapper.class);
        llmProviderService = mock(LlmProviderService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new LlmModelServiceImpl(llmModelMapper, llmProviderService, eventPublisher);
    }

    @Test
    void rejectsModelTypeNotSupportedByProviderOnCreate() {
        when(llmProviderService.loadById("kimi-provider")).thenReturn(provider("kimi-provider", "kimi"));
        CreateLlmModel dto = new CreateLlmModel();
        dto.setProviderId("kimi-provider");
        dto.setName("图片模型");
        dto.setModelName("image-model");
        dto.setModelType(ModelType.IMAGE_GEN);

        assertThatThrownBy(() -> service.create(dto)).isInstanceOf(AiBusinessException.class);

        verifyNoInteractions(llmModelMapper, eventPublisher);
    }

    @Test
    void validatesExistingModelTypeWhenChangingProvider() {
        LlmModelEntity existing = new LlmModelEntity();
        existing.setId("model-1");
        existing.setProviderId("dashscope-provider");
        existing.setModelType(ModelType.VIDEO_GEN);
        when(llmModelMapper.selectOne(any())).thenReturn(existing);
        when(llmProviderService.loadById("deepseek-provider")).thenReturn(provider("deepseek-provider", "deepseek"));
        UpdateLlmModel dto = new UpdateLlmModel();
        dto.setProviderId("deepseek-provider");

        assertThatThrownBy(() -> service.update("model-1", dto)).isInstanceOf(AiBusinessException.class);

        verify(llmModelMapper, never()).updateById(any(LlmModelEntity.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void acceptsMiniMaxMediaModelsInTheVendorCatalog() {
        when(llmProviderService.loadById("minimax-provider")).thenReturn(provider("minimax-provider", "minimax"));
        CreateLlmModel dto = new CreateLlmModel();
        dto.setProviderId("minimax-provider");
        dto.setName("MiniMax 视频");
        dto.setModelName("video-01");
        dto.setModelType(ModelType.VIDEO_GEN);
        dto.setMediaOperation(MediaGenerationOperation.TEXT_TO_VIDEO);

        service.create(dto);

        verify(llmModelMapper).insert(any(LlmModelEntity.class));
        verify(eventPublisher).publishEvent(any(ConfigChangedEvent.class));
    }

    @Test
    void requiresDefaultDimensionsForEmbeddingModel() {
        when(llmProviderService.loadById("openai-provider")).thenReturn(provider("openai-provider", "openai"));
        CreateLlmModel dto = new CreateLlmModel();
        dto.setProviderId("openai-provider");
        dto.setName("嵌入模型");
        dto.setModelName("text-embedding-3-small");
        dto.setModelType(ModelType.EMBEDDING);

        assertThatThrownBy(() -> service.create(dto)).isInstanceOf(AiBusinessException.class);

        verifyNoInteractions(llmModelMapper, eventPublisher);
    }

    @Test
    void persistsDefaultDimensionsForEmbeddingModel() {
        when(llmProviderService.loadById("openai-provider")).thenReturn(provider("openai-provider", "openai"));
        CreateLlmModel dto = new CreateLlmModel();
        dto.setProviderId("openai-provider");
        dto.setName("嵌入模型");
        dto.setModelName("text-embedding-3-small");
        dto.setModelType(ModelType.EMBEDDING);
        dto.setEmbeddingDimensions(1536);

        service.create(dto);

        ArgumentCaptor<LlmModelEntity> captor = ArgumentCaptor.forClass(LlmModelEntity.class);
        verify(llmModelMapper).insert(captor.capture());
        assertThat(captor.getValue().getEmbeddingDimensions()).isEqualTo(1536);
    }

    private static LlmProviderEntity provider(String id, String providerType) {
        LlmProviderEntity provider = new LlmProviderEntity();
        provider.setId(id);
        provider.setProviderType(providerType);
        return provider;
    }
}
