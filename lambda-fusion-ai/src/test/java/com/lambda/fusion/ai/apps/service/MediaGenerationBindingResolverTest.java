package com.lambda.fusion.ai.apps.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiConstants.ProviderType;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import com.lambda.fusion.ai.llm.model.entity.LlmProviderEntity;
import com.lambda.fusion.ai.llm.service.LlmModelService;
import com.lambda.fusion.ai.llm.service.LlmProviderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 媒体模型绑定的单一运行时可用性校验测试。 */
class MediaGenerationBindingResolverTest {

    private LlmModelService modelService;
    private LlmProviderService providerService;
    private MediaGenerationBindingResolver resolver;

    @BeforeEach
    void setUp() {
        modelService = mock(LlmModelService.class);
        providerService = mock(LlmProviderService.class);
        resolver = new MediaGenerationBindingResolver(modelService, providerService);
    }

    @Test
    void shouldResolveAnEnabledDashscopeVideoBinding() {
        when(modelService.loadById("video-1"))
                .thenReturn(model("video-1", ModelType.VIDEO_GEN, "custom-video-alias", "provider-1"));
        when(providerService.loadById("provider-1")).thenReturn(provider("dashscope", true, true));

        var binding = resolver.requireExecutable("video-1", ModelType.VIDEO_GEN);

        assertThat(binding.providerType()).isEqualTo(ProviderType.DASHSCOPE);
        assertThat(binding.capability().getKind()).isEqualTo("video");
    }

    @Test
    void shouldRejectVideoBindingsWithoutAnOfficialProviderTool() {
        when(modelService.loadById("video-1"))
                .thenReturn(model("video-1", ModelType.VIDEO_GEN, "doubao-seed-2.1-turbo", "provider-1"));
        when(providerService.loadById("provider-1")).thenReturn(provider("openai", true, true));

        assertThatThrownBy(() -> resolver.requireExecutable("video-1", ModelType.VIDEO_GEN))
                .isInstanceOf(AiBusinessException.class)
                .satisfies(error -> assertThat(((AiBusinessException) error).getCode())
                        .isEqualTo(AiErrorCode.APP_MEDIA_BINDING_INVALID.getCode()));
    }

    @Test
    void shouldRejectBindingsWhenTheProviderMediaSwitchIsOff() {
        when(modelService.loadById("image-1"))
                .thenReturn(model("image-1", ModelType.IMAGE_GEN, "wanx-v1", "provider-1"));
        when(providerService.loadById("provider-1")).thenReturn(provider("dashscope", true, false));

        assertThatThrownBy(() -> resolver.requireExecutable("image-1", ModelType.IMAGE_GEN))
                .isInstanceOf(AiBusinessException.class);
    }

    private static LlmModelEntity model(String id, ModelType type, String modelName, String providerId) {
        LlmModelEntity model = new LlmModelEntity();
        model.setId(id);
        model.setName(modelName);
        model.setModelName(modelName);
        model.setModelType(type);
        model.setProviderId(providerId);
        model.setEnabled(Boolean.TRUE);
        return model;
    }

    private static LlmProviderEntity provider(String type, boolean enabled, boolean mediaEnabled) {
        LlmProviderEntity provider = new LlmProviderEntity();
        provider.setProviderType(type);
        provider.setEnabled(enabled);
        provider.setEnableMediaGen(mediaEnabled);
        return provider;
    }
}
