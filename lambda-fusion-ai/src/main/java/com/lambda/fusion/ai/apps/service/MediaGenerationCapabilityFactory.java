package com.lambda.fusion.ai.apps.service;

import com.lambda.fusion.ai.AiConstants.ProviderType;
import com.lambda.fusion.ai.apps.model.MediaGenerationCapability;
import com.lambda.fusion.ai.apps.model.MediaGenerationSizeOption;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;

/**
 * 将已通过运行时校验的媒体模型转换成聊天端安全视图。
 *
 * <p>这里仅提供 AgentScope 2.0.1 官方工具签名中出现的快捷建议，不维护厂商各模型版本的第二套
 * 参数规格，也不参与运行时支持判断。用户输入由官方工具和上游提供方作最终校验。
 *
 * @author Jin
 */
final class MediaGenerationCapabilityFactory {

    private MediaGenerationCapabilityFactory() {}

    static MediaGenerationCapability create(LlmModelEntity model, ProviderType providerType) {
        return switch (model.getMediaOperation()) {
            case TEXT_TO_IMAGE -> imageCapability(model, providerType);
            case TEXT_TO_VIDEO -> textToVideoCapability(model);
            case IMAGE_TO_VIDEO -> imageToVideoCapability(model);
            case FIRST_LAST_FRAME_TO_VIDEO -> firstLastFrameToVideoCapability(model);
            case TEXT_TO_SPEECH -> audioCapability(model);
            case null -> throw new IllegalArgumentException("媒体模型未配置创作操作: " + model.getModelType());
        };
    }

    private static MediaGenerationCapability imageCapability(LlmModelEntity model, ProviderType providerType) {
        MediaGenerationCapability capability = base(model);
        List<String> parameters = new ArrayList<>(List.of("width", "height", "count"));
        String modelName = normalizedModelName(model);
        if (providerType == ProviderType.DASHSCOPE) {
            capability.setSizes(List.of(
                    option("auto", "auto", null),
                    option("1:1", "1K", "1024x1024"),
                    option("1:1", "1.3K", "1280x1280"),
                    option("2:3", "1K", "800x1200")));
            capability.setCounts(List.of(1));
        } else if (modelName.equals("dall-e-2")) {
            capability.setSizes(List.of(
                    option("auto", "auto", null),
                    option("1:1", "256", "256x256"),
                    option("1:1", "512", "512x512"),
                    option("1:1", "1K", "1024x1024")));
            capability.setCounts(integerRange(1, 10));
        } else if (modelName.equals("dall-e-3")) {
            capability.setSizes(List.of(
                    option("auto", "auto", null),
                    option("1:1", "1K", "1024x1024"),
                    option("16:9", "1K", "1792x1024"),
                    option("9:16", "1K", "1024x1792")));
            capability.setCounts(List.of(1));
            capability.setQualities(List.of("standard", "hd"));
            parameters.add("quality");
        } else {
            capability.setSizes(List.of(option("auto", "auto", null)));
            capability.setCounts(List.of(1));
        }
        capability.setParameters(List.copyOf(parameters));
        return capability;
    }

    private static MediaGenerationCapability textToVideoCapability(LlmModelEntity model) {
        MediaGenerationCapability capability = base(model);
        capability.setSizes(List.of(
                option("auto", "auto", null),
                option("16:9", "720P", "1280x720"),
                option("16:9", "1080P", "1920x1080")));
        capability.setDurations(List.of(5, 10));
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
        return capability;
    }

    private static MediaGenerationCapability imageToVideoCapability(LlmModelEntity model) {
        MediaGenerationCapability capability = base(model);
        capability.setSizes(List.of(option("auto", "720P", null), option("auto", "1080P", null)));
        capability.setDurations(List.of(5, 10, 15));
        capability.setParameters(List.of(
                "sourceImageUrl",
                "resolution",
                "duration",
                "negativePrompt",
                "audioUrl",
                "template",
                "shotType",
                "audio",
                "promptExtend",
                "watermark",
                "seed"));
        return capability;
    }

    private static MediaGenerationCapability firstLastFrameToVideoCapability(LlmModelEntity model) {
        MediaGenerationCapability capability = base(model);
        capability.setSizes(
                List.of(option("auto", "480P", null), option("auto", "720P", null), option("auto", "1080P", null)));
        capability.setParameters(List.of(
                "firstFrameUrl",
                "lastFrameUrl",
                "resolution",
                "negativePrompt",
                "template",
                "promptExtend",
                "watermark",
                "seed"));
        return capability;
    }

    private static MediaGenerationCapability audioCapability(LlmModelEntity model) {
        MediaGenerationCapability capability = base(model);
        String modelName = normalizedModelName(model);
        if (modelName.startsWith("qwen3-tts") || modelName.startsWith("qwen-tts")) {
            capability.setParameters(List.of("voice", "language"));
            capability.setVoices(List.of("Cherry", "Serena"));
            capability.setLanguages(List.of(
                    "Auto",
                    "Chinese",
                    "English",
                    "German",
                    "Italian",
                    "Portuguese",
                    "Spanish",
                    "Japanese",
                    "Korean",
                    "French",
                    "Russian"));
        } else {
            capability.setParameters(List.of("sampleRate"));
            capability.setSampleRates(List.of(16000, 24000, 48000));
        }
        return capability;
    }

    private static List<Integer> integerRange(int startInclusive, int endInclusive) {
        List<Integer> values = new ArrayList<>(endInclusive - startInclusive + 1);
        for (int value = startInclusive; value <= endInclusive; value++) {
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static String normalizedModelName(LlmModelEntity model) {
        return StringUtils.defaultString(model.getModelName()).toLowerCase(Locale.ROOT);
    }

    private static MediaGenerationCapability base(LlmModelEntity model) {
        MediaGenerationCapability capability = new MediaGenerationCapability();
        capability.setOperation(model.getMediaOperation());
        capability.setModelLabel(StringUtils.defaultIfBlank(model.getName(), "应用绑定模型"));
        return capability;
    }

    private static MediaGenerationSizeOption option(String aspectRatio, String resolution, String size) {
        return new MediaGenerationSizeOption(aspectRatio, resolution, size);
    }
}
