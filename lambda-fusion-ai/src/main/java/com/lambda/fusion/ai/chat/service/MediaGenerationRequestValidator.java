package com.lambda.fusion.ai.chat.service;

import com.lambda.fusion.ai.AiConstants.MediaGenerationOperation;
import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.apps.model.MediaGenerationCapability;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.apps.service.MediaGenerationBindingResolver;
import com.lambda.fusion.ai.chat.model.MediaGenerationRequest;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/** 将浏览器创作参数转换为已绑定的 AgentScope 官方工具调用，不信任客户端工具名或模型信息。 */
@Component
public class MediaGenerationRequestValidator {

    private final MediaGenerationBindingResolver bindingResolver;

    public MediaGenerationRequestValidator(MediaGenerationBindingResolver bindingResolver) {
        this.bindingResolver = bindingResolver;
    }

    /** 普通消息返回空；创作请求返回只含服务端批准参数的 AgentScope 工具调用。 */
    public ToolUseBlock validate(AppEntity app, String prompt, MediaGenerationRequest request) {
        if (request == null) {
            return null;
        }
        String kind = StringUtils.lowerCase(StringUtils.trim(request.getKind()), Locale.ROOT);
        ModelType modelType = modelType(kind);
        var binding = bindingResolver.resolve(app, modelType).orElseThrow(() -> invalid("当前应用未配置可用的" + kind + "创作模型"));
        MediaGenerationOperation operation = binding.model().getMediaOperation();
        MediaGenerationCapability capability = binding.capability();
        validateNumericBounds(request);
        rejectUnsupportedParameters(request, capability.getParameters());

        Map<String, Object> input = new LinkedHashMap<>();
        String normalizedPrompt = StringUtils.trim(prompt);
        switch (operation) {
            case TEXT_TO_IMAGE -> configureTextToImage(input, normalizedPrompt, request);
            case TEXT_TO_VIDEO -> configureTextToVideo(input, normalizedPrompt, request);
            case IMAGE_TO_VIDEO -> configureImageToVideo(input, normalizedPrompt, request);
            case FIRST_LAST_FRAME_TO_VIDEO -> configureFirstLastFrameToVideo(input, normalizedPrompt, request);
            case TEXT_TO_SPEECH -> configureTextToSpeech(input, normalizedPrompt, request);
        }
        return ToolUseBlock.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .name(operation.getToolName())
                .input(input)
                .content(JsonUtils.getJsonCodec().toJson(input))
                .build();
    }

    private static void configureTextToImage(Map<String, Object> input, String prompt, MediaGenerationRequest request) {
        input.put("prompt", prompt);
        putIfNotNull(input, "size", dimensions(request));
        putIfNotNull(input, "n", request.getCount());
        putIfNotNull(input, "quality", normalizedLower(request.getQuality()));
    }

    private static void configureTextToVideo(Map<String, Object> input, String prompt, MediaGenerationRequest request) {
        input.put("prompt", prompt);
        putIfNotNull(input, "negative_prompt", normalized(request.getNegativePrompt()));
        putIfNotNull(input, "audio_url", publicUrl(request.getAudioUrl(), "背景音频地址"));
        putIfNotNull(input, "size", dimensions(request));
        putCommonVideoParameters(input, request);
    }

    private static void configureImageToVideo(
            Map<String, Object> input, String prompt, MediaGenerationRequest request) {
        input.put("prompt", prompt);
        input.put("image_url", requiredPublicUrl(request.getSourceImageUrl(), "源图片地址"));
        putIfNotNull(input, "audio_url", publicUrl(request.getAudioUrl(), "背景音频地址"));
        putIfNotNull(input, "negative_prompt", normalized(request.getNegativePrompt()));
        putIfNotNull(input, "template", normalized(request.getTemplate()));
        putIfNotNull(input, "resolution", normalized(request.getResolution()));
        putCommonVideoParameters(input, request);
        putIfNotNull(input, "audio", request.getAudio());
    }

    private static void configureFirstLastFrameToVideo(
            Map<String, Object> input, String prompt, MediaGenerationRequest request) {
        input.put("prompt", prompt);
        input.put("first_frame_url", requiredPublicUrl(request.getFirstFrameUrl(), "首帧图片地址"));
        putIfNotNull(input, "last_frame_url", publicUrl(request.getLastFrameUrl(), "尾帧图片地址"));
        putIfNotNull(input, "negative_prompt", normalized(request.getNegativePrompt()));
        putIfNotNull(input, "template", normalized(request.getTemplate()));
        putIfNotNull(input, "resolution", normalized(request.getResolution()));
        putIfNotNull(input, "prompt_extend", request.getPromptExtend());
        putIfNotNull(input, "watermark", request.getWatermark());
        putIfNotNull(input, "seed", request.getSeed());
    }

    private static void configureTextToSpeech(
            Map<String, Object> input, String prompt, MediaGenerationRequest request) {
        input.put("text", prompt);
        putIfNotNull(input, "voice", normalized(request.getVoice()));
        putIfNotNull(input, "language", normalized(request.getLanguage()));
        putIfNotNull(input, "sample_rate", request.getSampleRate());
    }

    private static void putCommonVideoParameters(Map<String, Object> input, MediaGenerationRequest request) {
        putIfNotNull(input, "duration", request.getDuration());
        putIfNotNull(input, "shot_type", normalized(request.getShotType()));
        putIfNotNull(input, "prompt_extend", request.getPromptExtend());
        putIfNotNull(input, "watermark", request.getWatermark());
        putIfNotNull(input, "seed", request.getSeed());
    }

    private static void rejectUnsupportedParameters(MediaGenerationRequest request, List<String> supported) {
        rejectUnsupported(supported, "width", request.getWidth());
        rejectUnsupported(supported, "height", request.getHeight());
        rejectUnsupported(supported, "count", request.getCount());
        rejectUnsupported(supported, "duration", request.getDuration());
        rejectUnsupported(supported, "quality", normalized(request.getQuality()));
        rejectUnsupported(supported, "negativePrompt", normalized(request.getNegativePrompt()));
        rejectUnsupported(supported, "sourceImageUrl", normalized(request.getSourceImageUrl()));
        rejectUnsupported(supported, "firstFrameUrl", normalized(request.getFirstFrameUrl()));
        rejectUnsupported(supported, "lastFrameUrl", normalized(request.getLastFrameUrl()));
        rejectUnsupported(supported, "audioUrl", normalized(request.getAudioUrl()));
        rejectUnsupported(supported, "resolution", normalized(request.getResolution()));
        rejectUnsupported(supported, "template", normalized(request.getTemplate()));
        rejectUnsupported(supported, "shotType", normalized(request.getShotType()));
        rejectUnsupported(supported, "audio", request.getAudio());
        rejectUnsupported(supported, "promptExtend", request.getPromptExtend());
        rejectUnsupported(supported, "watermark", request.getWatermark());
        rejectUnsupported(supported, "seed", request.getSeed());
        rejectUnsupported(supported, "voice", normalized(request.getVoice()));
        rejectUnsupported(supported, "language", normalized(request.getLanguage()));
        rejectUnsupported(supported, "sampleRate", request.getSampleRate());
    }

    private static void validateNumericBounds(MediaGenerationRequest request) {
        Integer width = request.getWidth();
        Integer height = request.getHeight();
        if ((width == null) != (height == null)) {
            throw invalid("媒体宽度和高度必须同时填写");
        }
        validateRange(width, 1, 16384, "媒体宽度");
        validateRange(height, 1, 16384, "媒体高度");
        validateRange(request.getCount(), 1, 10, "生成数量");
        validateRange(request.getDuration(), 1, 600, "视频时长");
        validateRange(request.getSampleRate(), 1, 384000, "采样率");
    }

    private static void validateRange(Integer value, int minimum, int maximum, String label) {
        if (value != null && (value < minimum || value > maximum)) {
            throw invalid(label + "必须在" + minimum + "到" + maximum + "之间");
        }
    }

    private static void rejectUnsupported(List<String> supported, String parameter, Object value) {
        if (value != null && !supported.contains(parameter)) {
            throw invalid("当前创作操作不接受参数: " + parameter);
        }
    }

    private static String dimensions(MediaGenerationRequest request) {
        if (request.getWidth() == null && request.getHeight() == null) {
            return null;
        }
        if (request.getWidth() == null || request.getHeight() == null) {
            throw invalid("媒体宽度和高度必须同时填写");
        }
        return request.getWidth() + "x" + request.getHeight();
    }

    private static String requiredPublicUrl(String value, String label) {
        String url = publicUrl(value, label);
        if (url == null) {
            throw invalid(label + "不能为空");
        }
        return url;
    }

    private static String publicUrl(String value, String label) {
        String normalized = normalized(value);
        if (normalized == null) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException invalidUri) {
            throw invalid(label + "格式不正确");
        }
        String scheme = StringUtils.lowerCase(uri.getScheme(), Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))
                || StringUtils.isBlank(uri.getHost())
                || uri.getUserInfo() != null) {
            throw invalid(label + "必须是无用户信息的 HTTP/HTTPS 公网地址");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return StringUtils.trimToNull(value);
    }

    private static String normalizedLower(String value) {
        return StringUtils.lowerCase(normalized(value), Locale.ROOT);
    }

    private static ModelType modelType(String kind) {
        return switch (kind) {
            case "image" -> ModelType.IMAGE_GEN;
            case "video" -> ModelType.VIDEO_GEN;
            case "audio" -> ModelType.AUDIO_GEN;
            default -> throw invalid("不支持的创作类型: " + kind);
        };
    }

    private static void putIfNotNull(Map<String, Object> input, String name, Object value) {
        if (value != null) {
            input.put(name, value);
        }
    }

    private static AiBusinessException invalid(String message) {
        return new AiBusinessException(AiErrorCode.INVALID_PARAMETER, message);
    }
}
