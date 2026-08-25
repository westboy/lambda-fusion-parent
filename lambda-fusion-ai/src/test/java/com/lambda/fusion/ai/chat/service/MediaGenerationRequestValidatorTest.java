package com.lambda.fusion.ai.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.MediaGenerationOperation;
import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiConstants.ProviderType;
import com.lambda.fusion.ai.apps.model.MediaGenerationCapability;
import com.lambda.fusion.ai.apps.model.MediaGenerationSizeOption;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.apps.service.MediaGenerationBindingResolver;
import com.lambda.fusion.ai.apps.service.MediaGenerationBindingResolver.Binding;
import com.lambda.fusion.ai.chat.model.MediaGenerationRequest;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import com.lambda.fusion.ai.llm.model.entity.LlmProviderEntity;
import com.lambda.fusion.ai.runtime.tools.AudioGenerationTool;
import com.lambda.fusion.ai.runtime.tools.ImageGenerationTool;
import com.lambda.fusion.ai.runtime.tools.VideoGenerationTool;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MediaGenerationRequestValidatorTest {

    private final AppEntity app = new AppEntity();
    private MediaGenerationBindingResolver bindingResolver;
    private MediaGenerationRequestValidator validator;

    @BeforeEach
    void setUp() {
        bindingResolver = mock(MediaGenerationBindingResolver.class);
        validator = new MediaGenerationRequestValidator(bindingResolver);
    }

    @Test
    void shouldReturnNullForOrdinaryChatMessage() {
        assertThat(validator.validate(app, "普通对话", null)).isNull();
        verifyNoInteractions(bindingResolver);
    }

    @Test
    void shouldKeepProviderDefaultsForSmartImageRequest() {
        MediaGenerationCapability capability = capability("image");
        capability.setCounts(List.of(1, 2, 3, 4));
        stubBinding(ModelType.IMAGE_GEN, capability);

        ToolUseBlock toolCall = validator.validate(app, "生成海报", request("image"));

        assertThat(toolCall.getName()).isEqualTo(ImageGenerationTool.TOOL_NAME);
        assertThat(toolCall.getInput()).containsEntry("prompt", "生成海报").doesNotContainKeys("size", "n", "quality");
    }

    @Test
    void shouldBuildAuthoritativeImageInvocationFromCustomInput() {
        MediaGenerationCapability capability = capability("image");
        capability.setCounts(List.of(1));
        capability.setQualities(List.of("standard", "hd"));
        stubBinding(ModelType.IMAGE_GEN, capability);
        MediaGenerationRequest request = request("image");
        request.setWidth(1600);
        request.setHeight(900);
        request.setCount(3);
        request.setQuality(" HD ");

        ToolUseBlock toolCall = validator.validate(app, "  城市夜景  ", request);

        assertThat(toolCall.getName()).isEqualTo(ImageGenerationTool.TOOL_NAME);
        assertThat(toolCall.getInput())
                .containsEntry("prompt", "城市夜景")
                .containsEntry("size", "1600x900")
                .containsEntry("n", 3)
                .containsEntry("quality", "hd");
    }

    @Test
    void shouldTreatImageCountsAndSizesAsSuggestionsInsteadOfAllowlists() {
        MediaGenerationCapability capability = capability("image");
        capability.setCounts(List.of(1));
        capability.setSizes(List.of(new MediaGenerationSizeOption("1:1", "1K", "1024x1024")));
        stubBinding(ModelType.IMAGE_GEN, capability);
        MediaGenerationRequest request = request("image");
        request.setWidth(2048);
        request.setHeight(1536);
        request.setCount(7);

        ToolUseBlock toolCall = validator.validate(app, "兼容端点图片", request);

        assertThat(toolCall.getInput()).containsEntry("size", "2048x1536").containsEntry("n", 7);
    }

    @Test
    void shouldForwardUserEnteredVideoDurationOutsideSuggestions() {
        MediaGenerationCapability capability = capability("video");
        capability.setDurations(List.of(5, 10));
        stubBinding(ModelType.VIDEO_GEN, capability);
        MediaGenerationRequest request = request("video");
        request.setDuration(7);
        request.setNegativePrompt(" 模糊 ");
        request.setShotType("multi");
        request.setPromptExtend(Boolean.TRUE);
        request.setWatermark(Boolean.FALSE);
        request.setSeed(42);

        ToolUseBlock toolCall = validator.validate(app, "短片", request);

        assertThat(toolCall.getName()).isEqualTo(VideoGenerationTool.TOOL_NAME);
        assertThat(toolCall.getInput())
                .containsEntry("duration", 7)
                .containsEntry("negative_prompt", "模糊")
                .containsEntry("shot_type", "multi")
                .containsEntry("seed", 42);
    }

    @Test
    void shouldBuildImageToVideoInvocationForTheBoundOfficialOperation() {
        MediaGenerationCapability capability = new MediaGenerationCapability();
        capability.setOperation(MediaGenerationOperation.IMAGE_TO_VIDEO);
        capability.setParameters(List.of(
                "sourceImageUrl",
                "resolution",
                "duration",
                "negativePrompt",
                "template",
                "audio",
                "promptExtend",
                "watermark",
                "seed"));
        stubBinding(ModelType.VIDEO_GEN, capability);
        MediaGenerationRequest request = request("video");
        request.setSourceImageUrl(" https://media.example.com/source.png ");
        request.setResolution(" 1080P ");
        request.setDuration(7);
        request.setTemplate(" rotation ");
        request.setAudio(Boolean.TRUE);
        request.setPromptExtend(Boolean.TRUE);

        ToolUseBlock toolCall = validator.validate(app, " 让人物挥手 ", request);

        assertThat(toolCall.getName()).isEqualTo(VideoGenerationTool.IMAGE_TO_VIDEO_TOOL_NAME);
        assertThat(toolCall.getInput())
                .containsEntry("prompt", "让人物挥手")
                .containsEntry("image_url", "https://media.example.com/source.png")
                .containsEntry("resolution", "1080P")
                .containsEntry("duration", 7)
                .containsEntry("template", "rotation")
                .containsEntry("audio", true)
                .containsEntry("prompt_extend", true)
                .doesNotContainKey("size");
    }

    @Test
    void shouldRequireTheStructuralSourceForImageToVideoInSmartMode() {
        MediaGenerationCapability capability = new MediaGenerationCapability();
        capability.setOperation(MediaGenerationOperation.IMAGE_TO_VIDEO);
        capability.setParameters(List.of("sourceImageUrl"));
        stubBinding(ModelType.VIDEO_GEN, capability);

        assertThatThrownBy(() -> validator.validate(app, "视频", request("video")))
                .isInstanceOf(AiBusinessException.class);
    }

    @Test
    void shouldBuildFirstAndLastFrameInvocationForTheBoundOfficialOperation() {
        MediaGenerationCapability capability = new MediaGenerationCapability();
        capability.setOperation(MediaGenerationOperation.FIRST_LAST_FRAME_TO_VIDEO);
        capability.setParameters(
                List.of("firstFrameUrl", "lastFrameUrl", "resolution", "negativePrompt", "template", "seed"));
        stubBinding(ModelType.VIDEO_GEN, capability);
        MediaGenerationRequest request = request("video");
        request.setFirstFrameUrl("https://media.example.com/first.png");
        request.setLastFrameUrl("https://media.example.com/last.png");
        request.setResolution("720P");
        request.setSeed(23);

        ToolUseBlock toolCall = validator.validate(app, "平滑过渡", request);

        assertThat(toolCall.getName()).isEqualTo(VideoGenerationTool.FIRST_LAST_FRAME_TO_VIDEO_TOOL_NAME);
        assertThat(toolCall.getInput())
                .containsEntry("first_frame_url", "https://media.example.com/first.png")
                .containsEntry("last_frame_url", "https://media.example.com/last.png")
                .containsEntry("resolution", "720P")
                .containsEntry("seed", 23)
                .doesNotContainKeys("duration", "size");
    }

    @Test
    void shouldAllowUserEnteredQwenVoiceAndLanguage() {
        MediaGenerationCapability capability = capability("audio");
        capability.setParameters(List.of("voice", "language"));
        capability.setVoices(List.of("Cherry"));
        capability.setLanguages(List.of("Auto", "Chinese"));
        stubBinding(ModelType.AUDIO_GEN, capability);
        MediaGenerationRequest request = request("audio");
        request.setVoice(" CustomVoice ");
        request.setLanguage(" Japanese ");

        ToolUseBlock toolCall = validator.validate(app, "播报内容", request);

        assertThat(toolCall.getName()).isEqualTo(AudioGenerationTool.TOOL_NAME);
        assertThat(toolCall.getInput())
                .containsEntry("text", "播报内容")
                .containsEntry("voice", "CustomVoice")
                .containsEntry("language", "Japanese")
                .doesNotContainKey("sample_rate");
    }

    @Test
    void shouldTreatSampleRatesAsSuggestionsInsteadOfAllowlists() {
        MediaGenerationCapability capability = capability("audio");
        capability.setParameters(List.of("sampleRate"));
        capability.setSampleRates(List.of(16000, 24000));
        stubBinding(ModelType.AUDIO_GEN, capability);
        MediaGenerationRequest request = request("audio");
        request.setSampleRate(48000);

        assertThat(validator.validate(app, "播报内容", request).getInput()).containsEntry("sample_rate", 48000);
    }

    @Test
    void shouldRejectParametersIgnoredByTheBoundAudioModel() {
        MediaGenerationCapability qwen = capability("audio");
        qwen.setParameters(List.of("voice", "language"));
        qwen.setVoices(List.of("Cherry"));
        qwen.setLanguages(List.of("Auto"));
        stubBinding(ModelType.AUDIO_GEN, qwen);
        MediaGenerationRequest request = request("audio");
        request.setSampleRate(24000);

        assertThatThrownBy(() -> validator.validate(app, "播报内容", request)).isInstanceOf(AiBusinessException.class);
    }

    @Test
    void shouldForwardAUserEnteredSizeOutsideTheSuggestions() {
        MediaGenerationCapability capability = capability("video");
        capability.setSizes(List.of(new MediaGenerationSizeOption("16:9", "720P", "1280x720")));
        capability.setDurations(List.of(5));
        stubBinding(ModelType.VIDEO_GEN, capability);
        MediaGenerationRequest request = request("video");
        request.setWidth(1600);
        request.setHeight(900);

        assertThat(validator.validate(app, "短片", request).getInput()).containsEntry("size", "1600x900");
    }

    @Test
    void shouldForwardTheSmallPositiveVideoSizeAcceptedAtTheRequestBoundary() {
        MediaGenerationCapability capability = capability("video");
        stubBinding(ModelType.VIDEO_GEN, capability);
        MediaGenerationRequest request = request("video");
        request.setWidth(11);
        request.setHeight(111);

        assertThat(validator.validate(app, "短片", request).getInput()).containsEntry("size", "11x111");
    }

    @Test
    void shouldStillRejectInvalidPlatformLevelNumbers() {
        MediaGenerationCapability capability = capability("video");
        stubBinding(ModelType.VIDEO_GEN, capability);
        MediaGenerationRequest request = request("video");
        request.setDuration(0);

        assertThatThrownBy(() -> validator.validate(app, "短片", request)).isInstanceOf(AiBusinessException.class);
    }

    private void stubBinding(ModelType type, MediaGenerationCapability capability) {
        LlmModelEntity model = new LlmModelEntity();
        model.setModelType(type);
        model.setMediaOperation(capability.getOperation());
        LlmProviderEntity provider = new LlmProviderEntity();
        when(bindingResolver.resolve(app, type))
                .thenReturn(Optional.of(new Binding(model, provider, ProviderType.DASHSCOPE, capability)));
    }

    private static MediaGenerationCapability capability(String kind) {
        MediaGenerationCapability capability = new MediaGenerationCapability();
        switch (kind) {
            case "image" -> {
                capability.setOperation(MediaGenerationOperation.TEXT_TO_IMAGE);
                capability.setParameters(List.of("width", "height", "count", "quality"));
            }
            case "video" -> {
                capability.setOperation(MediaGenerationOperation.TEXT_TO_VIDEO);
                capability.setParameters(List.of(
                        "width",
                        "height",
                        "duration",
                        "negativePrompt",
                        "audioUrl",
                        "shotType",
                        "promptExtend",
                        "watermark",
                        "seed"));
            }
            case "audio" -> capability.setOperation(MediaGenerationOperation.TEXT_TO_SPEECH);
            default -> throw new IllegalArgumentException(kind);
        }
        return capability;
    }

    private static MediaGenerationRequest request(String kind) {
        MediaGenerationRequest request = new MediaGenerationRequest();
        request.setKind(kind);
        return request;
    }
}
