package com.lambda.fusion.ai.llm.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.MediaGenerationOperation;
import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.llm.mapper.LlmProviderMapper;
import com.lambda.fusion.ai.llm.model.entity.LlmProviderEntity;
import com.lambda.fusion.ai.security.KeyEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class LlmProviderServiceImplCapabilityTest {

    private LlmProviderMapper llmProviderMapper;
    private LlmProviderServiceImpl service;

    @BeforeEach
    void setUp() {
        llmProviderMapper = mock(LlmProviderMapper.class);
        service = new LlmProviderServiceImpl(
                llmProviderMapper, mock(KeyEncryptionService.class), mock(ApplicationEventPublisher.class));
    }

    @Test
    void populatesDeclaredAndRuntimeCapabilitiesWithoutEntityBusinessLogic() {
        LlmProviderEntity provider = new LlmProviderEntity();
        provider.setId("minimax-provider");
        provider.setProviderType("minimax");
        when(llmProviderMapper.selectOne(any())).thenReturn(provider);

        LlmProviderEntity result = service.get("minimax-provider");

        assertThat(result.getSupportedModelTypes())
                .containsExactlyInAnyOrder(
                        ModelType.CHAT, ModelType.IMAGE_GEN, ModelType.VIDEO_GEN, ModelType.AUDIO_GEN);
        assertThat(result.getRuntimeModelTypes()).containsExactly(ModelType.CHAT);
        assertThat(result.getSupportedMediaOperations())
                .containsExactlyInAnyOrder(
                        MediaGenerationOperation.TEXT_TO_IMAGE,
                        MediaGenerationOperation.TEXT_TO_VIDEO,
                        MediaGenerationOperation.IMAGE_TO_VIDEO,
                        MediaGenerationOperation.TEXT_TO_SPEECH);
        assertThat(result.getRuntimeMediaOperations()).isEmpty();
    }
}
