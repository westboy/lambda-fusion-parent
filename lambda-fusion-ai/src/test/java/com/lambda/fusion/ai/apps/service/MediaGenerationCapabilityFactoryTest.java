package com.lambda.fusion.ai.apps.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiConstants.ProviderType;
import com.lambda.fusion.ai.apps.model.MediaGenerationCapability;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import org.junit.jupiter.api.Test;

/** 聊天安全视图媒体能力预设测试。 */
class MediaGenerationCapabilityFactoryTest {

    @Test
    void shouldExposeLegacyDashscopeImagePresetsWithoutClaimingCustomSizes() {
        MediaGenerationCapability capability = MediaGenerationCapabilityFactory.create(
                model(ModelType.IMAGE_GEN, "内部展示名", "wanx-v1"), ProviderType.DASHSCOPE);

        assertThat(capability).isNotNull();
        assertThat(capability.getKind()).isEqualTo("image");
        assertThat(capability.getModelLabel()).isEqualTo("内部展示名");
        assertThat(capability.isCustomSizeAllowed()).isFalse();
        assertThat(capability.getMinDimension()).isNull();
        assertThat(capability.getMaxDimension()).isNull();
        assertThat(capability.getCounts()).containsExactly(1, 2, 3, 4);
        assertThat(capability.getSizes())
                .extracting(option -> option.getSize())
                .containsExactly(null, "1024x1024", "768x1152", "1280x720", "720x1280");
        assertThat(capability.toString()).doesNotContain("wanx-v1");
    }

    @Test
    void shouldAllowCustomSizeForDashscopeImageModelsThatSupportIt() {
        MediaGenerationCapability capability = MediaGenerationCapabilityFactory.create(
                model(ModelType.IMAGE_GEN, "千问图片", "qwen-image-3.0"), ProviderType.DASHSCOPE);

        assertThat(capability.isCustomSizeAllowed()).isTrue();
        assertThat(capability.getMinDimension()).isEqualTo(512);
        assertThat(capability.getMaxDimension()).isEqualTo(4096);
    }

    @Test
    void shouldRestrictDallE3ToOneImageAndItsOfficialSizes() {
        MediaGenerationCapability capability = MediaGenerationCapabilityFactory.create(
                model(ModelType.IMAGE_GEN, "海报模型", "dall-e-3"), ProviderType.OPENAI);

        assertThat(capability.getCounts()).containsExactly(1);
        assertThat(capability.getSizes())
                .extracting(option -> option.getSize())
                .containsExactly(null, "1024x1024", "1792x1024", "1024x1792");
    }

    @Test
    void shouldExposeDashscopeVideoSizesAndDurations() {
        MediaGenerationCapability capability = MediaGenerationCapabilityFactory.create(
                model(ModelType.VIDEO_GEN, "视频模型", "wan2.6-t2v"), ProviderType.DASHSCOPE);

        assertThat(capability.getKind()).isEqualTo("video");
        assertThat(capability.getCounts()).isEmpty();
        assertThat(capability.isCustomSizeAllowed()).isFalse();
        assertThat(capability.getDurations())
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(2, 15).boxed().toList());
        assertThat(capability.getSizes())
                .extracting(option -> option.getSize())
                .contains("1280x720", "720x1280", "960x960", "1920x1080", "1080x1920", "1440x1440");
    }

    @Test
    void shouldRespectLegacyVideoModelResolutionAndDurationLimits() {
        MediaGenerationCapability capability = MediaGenerationCapabilityFactory.create(
                model(ModelType.VIDEO_GEN, "视频模型", "wanx2.1-t2v-plus"), ProviderType.DASHSCOPE);

        assertThat(capability.getDurations()).containsExactly(5);
        assertThat(capability.getSizes())
                .extracting(option -> option.getResolution())
                .containsOnly("720P");
        assertThat(capability.getSizes())
                .extracting(option -> option.getSize())
                .contains("1280x720", "720x1280", "960x960")
                .doesNotContain("1920x1080");
    }

    @Test
    void shouldExposeAllDallE2CountsSupportedByOfficialAgentScopeTool() {
        MediaGenerationCapability capability = MediaGenerationCapabilityFactory.create(
                model(ModelType.IMAGE_GEN, "经典图片模型", "dall-e-2"), ProviderType.OPENAI);

        assertThat(capability.getCounts()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(capability.getSizes())
                .extracting(option -> option.getSize())
                .containsExactly(null, "256x256", "512x512", "1024x1024");
    }

    @Test
    void shouldExposeOfficialSizeAndCountInputsForUnknownOpenAiCompatibleImageModel() {
        MediaGenerationCapability capability = MediaGenerationCapabilityFactory.create(
                model(ModelType.IMAGE_GEN, "兼容图片模型", "custom-image-alias"), ProviderType.OPENAI);

        assertThat(capability.isCustomSizeAllowed()).isTrue();
        assertThat(capability.getMinDimension()).isNull();
        assertThat(capability.getMaxDimension()).isNull();
        assertThat(capability.getCounts()).isEmpty();
        assertThat(capability.getSizes()).extracting(option -> option.getSize()).containsExactly((String) null);
    }

    @Test
    void shouldRejectUnsupportedVideoProvider() {
        LlmModelEntity model = model(ModelType.VIDEO_GEN, "视频模型", "video-compatible");

        assertThat(MediaGenerationCapabilityFactory.create(model, ProviderType.OPENAI))
                .isNull();
        assertThat(MediaGenerationCapabilityFactory.supports(ModelType.VIDEO_GEN, ProviderType.OPENAI))
                .isFalse();
    }

    @Test
    void shouldNotTreatConfigurableMiniMaxMediaModelsAsAgentScopeRuntimeTools() {
        LlmModelEntity model = model(ModelType.VIDEO_GEN, "MiniMax 视频", "video-01");

        assertThat(ProviderType.MINIMAX.supports(ModelType.VIDEO_GEN)).isTrue();
        assertThat(MediaGenerationCapabilityFactory.create(model, ProviderType.MINIMAX))
                .isNull();
        assertThat(MediaGenerationCapabilityFactory.supports(ModelType.VIDEO_GEN, ProviderType.MINIMAX))
                .isFalse();
    }

    @Test
    void shouldNotExposeGptImageThroughTheDallEOnlyOfficialTool() {
        LlmModelEntity model = model(ModelType.IMAGE_GEN, "GPT 图片", "gpt-image-1");

        assertThat(MediaGenerationCapabilityFactory.create(model, ProviderType.OPENAI))
                .isNull();
        assertThat(MediaGenerationCapabilityFactory.supports(ModelType.IMAGE_GEN, ProviderType.OPENAI, "gpt-image-1"))
                .isFalse();
    }

    @Test
    void shouldExposeDashscopeAudioParameters() {
        MediaGenerationCapability capability = MediaGenerationCapabilityFactory.create(
                model(ModelType.AUDIO_GEN, "语音模型", "qwen3-tts-flash"), ProviderType.DASHSCOPE);

        assertThat(capability.getKind()).isEqualTo("audio");
        assertThat(capability.getVoices()).contains("Cherry", "Serena");
        assertThat(capability.getLanguages()).contains("Auto", "Chinese", "English", "Japanese");
        assertThat(capability.getSampleRates()).isEmpty();
    }

    @Test
    void shouldExposeOnlySambertParametersConsumedByTheOfficialTool() {
        MediaGenerationCapability capability = MediaGenerationCapabilityFactory.create(
                model(ModelType.AUDIO_GEN, "Sambert", "sambert-zhinan-v1"), ProviderType.DASHSCOPE);

        assertThat(capability.getVoices()).isEmpty();
        assertThat(capability.getLanguages()).isEmpty();
        assertThat(capability.getSampleRates()).containsExactly(8000, 16000, 22050, 24000);
    }

    @Test
    void shouldUseExplicitVideoModelTypeInsteadOfGuessingFromItsName() {
        MediaGenerationCapability capability = MediaGenerationCapabilityFactory.create(
                model(ModelType.VIDEO_GEN, "视频别名", "custom-video-alias"), ProviderType.DASHSCOPE);

        assertThat(capability).isNotNull();
        assertThat(capability.getKind()).isEqualTo("video");
        assertThat(capability.getSizes()).extracting(option -> option.getSize()).containsExactly((String) null);
    }

    private static LlmModelEntity model(ModelType type, String name, String modelName) {
        LlmModelEntity model = new LlmModelEntity();
        model.setModelType(type);
        model.setName(name);
        model.setModelName(modelName);
        return model;
    }
}
