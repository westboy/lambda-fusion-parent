package com.lambda.fusion.ai.chat.service;

import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.apps.model.MediaGenerationCapability;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.apps.service.MediaGenerationBindingResolver;
import com.lambda.fusion.ai.chat.model.MediaGenerationRequest;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.runtime.media.MediaGenerationInvocation;
import com.lambda.fusion.ai.runtime.tools.AudioGenerationTool;
import com.lambda.fusion.ai.runtime.tools.ImageGenerationTool;
import com.lambda.fusion.ai.runtime.tools.VideoGenerationTool;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/** 将浏览器媒体参数校验并转换为运行时权威调用，不信任客户端工具名或模型信息。 */
@Component
public class MediaGenerationRequestValidator {

    private final MediaGenerationBindingResolver bindingResolver;

    public MediaGenerationRequestValidator(MediaGenerationBindingResolver bindingResolver) {
        this.bindingResolver = bindingResolver;
    }

    /** 普通消息返回空；媒体请求返回只含服务端批准参数的调用对象。 */
    public MediaGenerationInvocation validate(AppEntity app, String prompt, MediaGenerationRequest request) {
        if (request == null) {
            return null;
        }
        String kind = StringUtils.lowerCase(StringUtils.trim(request.getKind()), Locale.ROOT);
        ModelType modelType = modelType(kind);
        var binding = bindingResolver.resolve(app, modelType).orElseThrow(() -> invalid("当前应用未配置可用的" + kind + "生成模型"));
        MediaGenerationCapability capability = binding.capability();
        String size = validateSize(request, capability);

        MediaGenerationInvocation.MediaGenerationInvocationBuilder builder = MediaGenerationInvocation.builder()
                .toolName(toolName(modelType))
                .prompt(StringUtils.trim(prompt))
                .size(size);
        switch (modelType) {
            case IMAGE_GEN -> configureImage(builder, request, capability);
            case VIDEO_GEN -> configureVideo(builder, request, capability);
            case AUDIO_GEN -> configureAudio(builder, request, capability);
            default -> throw invalid("不支持的媒体生成类型: " + kind);
        }
        return builder.build();
    }

    private static void configureImage(
            MediaGenerationInvocation.MediaGenerationInvocationBuilder builder,
            MediaGenerationRequest request,
            MediaGenerationCapability capability) {
        reject(request.getDuration() != null, "图片生成不接受视频时长");
        reject(hasAudioParameters(request), "图片生成不接受语音参数");
        reject(hasVideoParameters(request), "图片生成不接受视频参数");
        Integer count = request.getCount();
        if (count != null) {
            if (count < 1) {
                throw invalid("生成数量必须大于 0");
            }
            if (!capability.getCounts().isEmpty()) {
                validateAllowedValue(count, capability.getCounts(), "生成数量");
            }
        }
        String quality = StringUtils.lowerCase(StringUtils.trimToNull(request.getQuality()), Locale.ROOT);
        if (quality != null && !capability.getQualities().contains(quality)) {
            throw invalid("当前模型不支持图片质量: " + quality);
        }
        builder.count(count).quality(quality);
    }

    private static void configureVideo(
            MediaGenerationInvocation.MediaGenerationInvocationBuilder builder,
            MediaGenerationRequest request,
            MediaGenerationCapability capability) {
        reject(request.getCount() != null, "视频生成不接受生成数量");
        reject(StringUtils.isNotBlank(request.getQuality()), "视频生成不接受图片质量");
        reject(hasAudioParameters(request), "视频生成不接受语音合成参数");
        if (request.getDuration() != null) {
            validateAllowedValue(request.getDuration(), capability.getDurations(), "视频时长");
        }
        builder.duration(request.getDuration())
                .negativePrompt(StringUtils.trimToNull(request.getNegativePrompt()))
                .shotType(StringUtils.trimToNull(request.getShotType()))
                .promptExtend(request.getPromptExtend())
                .watermark(request.getWatermark())
                .seed(request.getSeed());
    }

    private static void configureAudio(
            MediaGenerationInvocation.MediaGenerationInvocationBuilder builder,
            MediaGenerationRequest request,
            MediaGenerationCapability capability) {
        reject(request.getWidth() != null || request.getHeight() != null, "语音生成不接受尺寸参数");
        reject(request.getCount() != null || request.getDuration() != null, "语音生成不接受数量或时长参数");
        reject(StringUtils.isNotBlank(request.getQuality()), "语音生成不接受图片质量");
        reject(hasVideoParameters(request), "语音生成不接受视频参数");
        String voice = StringUtils.trimToNull(request.getVoice());
        String language = StringUtils.trimToNull(request.getLanguage());
        reject(voice != null && capability.getVoices().isEmpty(), "当前语音模型不接受音色参数");
        reject(language != null && capability.getLanguages().isEmpty(), "当前语音模型不接受语言参数");
        Integer sampleRate = request.getSampleRate();
        if (sampleRate != null) {
            if (capability.getSampleRates().isEmpty()) {
                throw invalid("当前语音模型不接受采样率参数");
            }
            validateAllowedValue(sampleRate, capability.getSampleRates(), "音频采样率");
        }
        builder.voice(voice).language(language).sampleRate(sampleRate);
    }

    private static String validateSize(MediaGenerationRequest request, MediaGenerationCapability capability) {
        if (request.getWidth() == null && request.getHeight() == null) {
            return null;
        }
        if (request.getWidth() == null || request.getHeight() == null) {
            throw invalid("媒体宽度和高度必须同时填写");
        }
        String size = request.getWidth() + "x" + request.getHeight();
        if (!capability.isCustomSizeAllowed()) {
            boolean preset = capability.getSizes().stream()
                    .map(option -> option.getSize())
                    .filter(StringUtils::isNotBlank)
                    .map(MediaGenerationRequestValidator::normalizeSize)
                    .anyMatch(size::equals);
            if (!preset) {
                throw invalid("当前模型只支持推荐尺寸");
            }
            return size;
        }
        Integer min = capability.getMinDimension();
        Integer max = capability.getMaxDimension();
        if (request.getWidth() < 1 || request.getHeight() < 1) {
            throw invalid("自定义宽高必须为正整数");
        }
        if ((min != null && (request.getWidth() < min || request.getHeight() < min))
                || (max != null && (request.getWidth() > max || request.getHeight() > max))) {
            throw invalid("自定义宽高必须在 " + min + " 到 " + max + " 像素之间");
        }
        return size;
    }

    private static void validateAllowedValue(int value, List<Integer> allowedValues, String label) {
        if (allowedValues == null || allowedValues.isEmpty()) {
            throw invalid("当前模型不接受" + label + "参数");
        }
        if (!allowedValues.contains(value)) {
            throw invalid(label + "只支持: " + allowedValues);
        }
    }

    private static boolean hasAudioParameters(MediaGenerationRequest request) {
        return StringUtils.isNotBlank(request.getVoice())
                || StringUtils.isNotBlank(request.getLanguage())
                || request.getSampleRate() != null;
    }

    private static boolean hasVideoParameters(MediaGenerationRequest request) {
        return StringUtils.isNotBlank(request.getNegativePrompt())
                || StringUtils.isNotBlank(request.getShotType())
                || request.getPromptExtend() != null
                || request.getWatermark() != null
                || request.getSeed() != null;
    }

    private static ModelType modelType(String kind) {
        return switch (kind) {
            case "image" -> ModelType.IMAGE_GEN;
            case "video" -> ModelType.VIDEO_GEN;
            case "audio" -> ModelType.AUDIO_GEN;
            default -> throw invalid("不支持的媒体生成类型: " + kind);
        };
    }

    private static String toolName(ModelType type) {
        return switch (type) {
            case IMAGE_GEN -> ImageGenerationTool.TOOL_NAME;
            case VIDEO_GEN -> VideoGenerationTool.TOOL_NAME;
            case AUDIO_GEN -> AudioGenerationTool.TOOL_NAME;
            default -> throw invalid("不是媒体生成模型: " + type);
        };
    }

    private static String normalizeSize(String size) {
        return size.replace('*', 'x');
    }

    private static void reject(boolean rejected, String message) {
        if (rejected) {
            throw invalid(message);
        }
    }

    private static AiBusinessException invalid(String message) {
        return new AiBusinessException(AiErrorCode.INVALID_PARAMETER, message);
    }
}
