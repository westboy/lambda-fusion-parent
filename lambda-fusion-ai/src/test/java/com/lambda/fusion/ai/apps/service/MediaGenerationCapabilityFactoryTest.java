package com.lambda.fusion.ai.apps.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lambda.fusion.ai.AiConstants.MediaGenerationOperation;
import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiConstants.ProviderType;
import com.lambda.fusion.ai.apps.model.MediaGenerationCapability;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import org.junit.jupiter.api.Test;

class MediaGenerationCapabilityFactoryTest {

    @Test
    void shouldExposeAgentScopeDashscopeImageSuggestions() {
        MediaGenerationCapability capability = MediaGenerationCapabilityFactory.create(
                model(ModelType.IMAGE_GEN, "通义生图", "custom-image-alias"), ProviderType.DASHSCOPE);

        assertThat(capability.getKind()).isEqualTo("image");
        assertThat(capability.getSizes())
                .extracting(option -> option.getSize())
                .containsExactly(null, "1024x1024", "1280x1280", "800x1200");
        assertThat(capability.getCounts()).containsExactly(1);
    }

    @Test
    void shouldExposeDallEThreeSuggestionsWithoutUsingThemAsRuntimeAdmissionRules() {
        MediaGenerationCapability capability = MediaGenerationCapabilityFactory.create(
                model(ModelType.IMAGE_GEN, "DALL-E 3", "dall-e-3"), ProviderType.OPENAI);

        assertThat(capability.getSizes())
                .extracting(option -> option.getSize())
                .containsExactly(null, "1024x1024", "1792x1024", "1024x1792");
        assertThat(capability.getCounts()).containsExactly(1);
        assertThat(capability.getQualities()).containsExactly("standard", "hd");
    }

    @Test
    void shouldExposeDallETwoSuggestions() {
        MediaGenerationCapability capability = MediaGenerationCapabilityFactory.create(
                model(ModelType.IMAGE_GEN, "DALL-E 2", "dall-e-2"), ProviderType.OPENAI);

        assertThat(capability.getCounts()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(capability.getQualities()).isEmpty();
    }

    @Test
    void shouldLeaveRuntimeAdmissionToTheBindingResolver() {
        MediaGenerationCapability capability = MediaGenerationCapabilityFactory.create(
                model(ModelType.IMAGE_GEN, "GPT Image", "gpt-image-1"), ProviderType.OPENAI);

        assertThat(capability).isNotNull();
        assertThat(capability.getSizes()).extracting(option -> option.getSize()).containsExactly((String) null);
    }

    @Test
    void shouldUseAgentScopeVideoSignatureSuggestionsInsteadOfModelNameProfiles() {
        MediaGenerationCapability capability = MediaGenerationCapabilityFactory.create(
                model(ModelType.VIDEO_GEN, "视频别名", "custom-video-alias"), ProviderType.DASHSCOPE);

        assertThat(capability.getKind()).isEqualTo("video");
        assertThat(capability.getSizes())
                .extracting(option -> option.getSize())
                .containsExactly(null, "1280x720", "1920x1080");
        assertThat(capability.getDurations()).containsExactly(5, 10);
    }

    @Test
    void shouldExposeQwenAudioSuggestions() {
        MediaGenerationCapability capability = MediaGenerationCapabilityFactory.create(
                model(ModelType.AUDIO_GEN, "语音模型", "qwen3-tts-flash"), ProviderType.DASHSCOPE);

        assertThat(capability.getKind()).isEqualTo("audio");
        assertThat(capability.getVoices()).contains("Cherry", "Serena");
        assertThat(capability.getLanguages()).contains("Auto", "Chinese", "English", "Japanese");
        assertThat(capability.getSampleRates()).isEmpty();
    }

    @Test
    void shouldIncludeAllAgentScopeSampleRateExamplesForSambert() {
        MediaGenerationCapability capability = MediaGenerationCapabilityFactory.create(
                model(ModelType.AUDIO_GEN, "Sambert", "sambert-zhinan-v1"), ProviderType.DASHSCOPE);

        assertThat(capability.getSampleRates()).containsExactly(16000, 24000, 48000);
    }

    private static LlmModelEntity model(ModelType type, String name, String modelName) {
        LlmModelEntity model = new LlmModelEntity();
        model.setModelType(type);
        model.setMediaOperation(
                switch (type) {
                    case IMAGE_GEN -> MediaGenerationOperation.TEXT_TO_IMAGE;
                    case VIDEO_GEN -> MediaGenerationOperation.TEXT_TO_VIDEO;
                    case AUDIO_GEN -> MediaGenerationOperation.TEXT_TO_SPEECH;
                    default -> null;
                });
        model.setName(name);
        model.setModelName(modelName);
        return model;
    }
}
