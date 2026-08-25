package com.lambda.fusion.ai.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import com.lambda.fusion.ai.runtime.media.MediaGenerationInvocation;
import com.lambda.fusion.ai.runtime.tools.AudioGenerationTool;
import com.lambda.fusion.ai.runtime.tools.ImageGenerationTool;
import com.lambda.fusion.ai.runtime.tools.VideoGenerationTool;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
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

        MediaGenerationInvocation invocation = validator.validate(app, "生成海报", request("image"));

        assertThat(invocation.getToolName()).isEqualTo(ImageGenerationTool.TOOL_NAME);
        assertThat(invocation.getSize()).isNull();
        assertThat(invocation.getCount()).isNull();
        assertThat(invocation.getQuality()).isNull();
    }

    @Test
    void shouldBuildAuthoritativeImageInvocationFromCustomInput() {
        MediaGenerationCapability capability = capability("image");
        capability.setCustomSizeAllowed(true);
        capability.setMinDimension(512);
        capability.setMaxDimension(4096);
        capability.setCounts(List.of(1, 2, 3, 4, 5, 6));
        capability.setQualities(List.of("standard", "hd"));
        stubBinding(ModelType.IMAGE_GEN, capability);
        MediaGenerationRequest request = request("image");
        request.setWidth(1600);
        request.setHeight(900);
        request.setCount(3);
        request.setQuality(" HD ");

        MediaGenerationInvocation invocation = validator.validate(app, "  城市夜景  ", request);

        assertThat(invocation.getToolName()).isEqualTo(ImageGenerationTool.TOOL_NAME);
        assertThat(invocation.getPrompt()).isEqualTo("城市夜景");
        assertThat(invocation.getSize()).isEqualTo("1600x900");
        assertThat(invocation.getCount()).isEqualTo(3);
        assertThat(invocation.getQuality()).isEqualTo("hd");
    }

    @Test
    void shouldPassThroughOfficialImageInputsWhenNoModelProfileIsKnown() {
        MediaGenerationCapability capability = capability("image");
        capability.setCustomSizeAllowed(true);
        stubBinding(ModelType.IMAGE_GEN, capability);
        MediaGenerationRequest request = request("image");
        request.setWidth(2048);
        request.setHeight(1536);
        request.setCount(7);

        MediaGenerationInvocation invocation = validator.validate(app, "兼容端点图片", request);

        assertThat(invocation.getSize()).isEqualTo("2048x1536");
        assertThat(invocation.getCount()).isEqualTo(7);
    }

    @Test
    void shouldAllowUserEnteredVideoDurationWithinSupportedRange() {
        MediaGenerationCapability capability = capability("video");
        capability.setDurations(IntStream.rangeClosed(2, 15).boxed().toList());
        stubBinding(ModelType.VIDEO_GEN, capability);
        MediaGenerationRequest request = request("video");
        request.setDuration(7);
        request.setNegativePrompt(" 模糊 ");
        request.setShotType("multi");
        request.setPromptExtend(Boolean.TRUE);
        request.setWatermark(Boolean.FALSE);
        request.setSeed(42);

        MediaGenerationInvocation invocation = validator.validate(app, "短片", request);

        assertThat(invocation.getToolName()).isEqualTo(VideoGenerationTool.TOOL_NAME);
        assertThat(invocation.getDuration()).isEqualTo(7);
        assertThat(invocation.getNegativePrompt()).isEqualTo("模糊");
        assertThat(invocation.getShotType()).isEqualTo("multi");
        assertThat(invocation.getSeed()).isEqualTo(42);
    }

    @Test
    void shouldAllowUserEnteredQwenVoiceAndLanguage() {
        MediaGenerationCapability capability = capability("audio");
        capability.setVoices(List.of("Cherry"));
        capability.setLanguages(List.of("Auto", "Chinese"));
        stubBinding(ModelType.AUDIO_GEN, capability);
        MediaGenerationRequest request = request("audio");
        request.setVoice(" CustomVoice ");
        request.setLanguage(" Japanese ");

        MediaGenerationInvocation invocation = validator.validate(app, "播报内容", request);

        assertThat(invocation.getToolName()).isEqualTo(AudioGenerationTool.TOOL_NAME);
        assertThat(invocation.getVoice()).isEqualTo("CustomVoice");
        assertThat(invocation.getLanguage()).isEqualTo("Japanese");
        assertThat(invocation.getSampleRate()).isNull();
    }

    @Test
    void shouldAcceptOnlySampleRatesConsumedBySambert() {
        MediaGenerationCapability capability = capability("audio");
        capability.setSampleRates(List.of(8000, 16000, 22050, 24000));
        stubBinding(ModelType.AUDIO_GEN, capability);
        MediaGenerationRequest request = request("audio");
        request.setSampleRate(22050);

        assertThat(validator.validate(app, "播报内容", request).getSampleRate()).isEqualTo(22050);

        request.setSampleRate(48000);
        assertThatThrownBy(() -> validator.validate(app, "播报内容", request)).isInstanceOf(AiBusinessException.class);
    }

    @Test
    void shouldRejectParametersIgnoredByTheSelectedAudioModel() {
        MediaGenerationCapability qwen = capability("audio");
        qwen.setVoices(List.of("Cherry"));
        qwen.setLanguages(List.of("Auto"));
        stubBinding(ModelType.AUDIO_GEN, qwen);
        MediaGenerationRequest request = request("audio");
        request.setSampleRate(24000);

        assertThatThrownBy(() -> validator.validate(app, "播报内容", request)).isInstanceOf(AiBusinessException.class);
    }

    @Test
    void shouldRejectUnsupportedFixedSizeInsteadOfPassingItUpstream() {
        MediaGenerationCapability capability = capability("video");
        capability.setSizes(List.of(new MediaGenerationSizeOption("16:9", "720P", "1280x720")));
        capability.setDurations(List.of(5));
        stubBinding(ModelType.VIDEO_GEN, capability);
        MediaGenerationRequest request = request("video");
        request.setWidth(1600);
        request.setHeight(900);

        assertThatThrownBy(() -> validator.validate(app, "短片", request)).isInstanceOf(AiBusinessException.class);
    }

    private void stubBinding(ModelType type, MediaGenerationCapability capability) {
        LlmModelEntity model = new LlmModelEntity();
        model.setModelType(type);
        LlmProviderEntity provider = new LlmProviderEntity();
        when(bindingResolver.resolve(app, type))
                .thenReturn(Optional.of(new Binding(model, provider, ProviderType.DASHSCOPE, capability)));
    }

    private static MediaGenerationCapability capability(String kind) {
        MediaGenerationCapability capability = new MediaGenerationCapability();
        capability.setKind(kind);
        return capability;
    }

    private static MediaGenerationRequest request(String kind) {
        MediaGenerationRequest request = new MediaGenerationRequest();
        request.setKind(kind);
        return request;
    }
}
