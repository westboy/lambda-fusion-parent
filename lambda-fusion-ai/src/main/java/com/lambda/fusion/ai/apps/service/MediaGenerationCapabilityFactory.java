package com.lambda.fusion.ai.apps.service;

import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiConstants.ProviderType;
import com.lambda.fusion.ai.apps.model.MediaGenerationCapability;
import com.lambda.fusion.ai.apps.model.MediaGenerationSizeOption;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;

/**
 * 将内部模型配置转换为聊天端安全的媒体生成能力。预设表示平台当前实际支持并会传给 AgentScope
 * 官方工具的参数组合；不同模型系列使用各自的官方规格，未知模型只开放工具默认值，避免宣称并不存在的
 * 分辨率、数量或时长能力。
 *
 * @author Jin
 */
public final class MediaGenerationCapabilityFactory {

    private static final String IMAGE = "image";
    private static final String VIDEO = "video";
    private static final String IMAGE_TOOL_NAME = "text_to_image";
    private static final String VIDEO_TOOL_NAME = "text_to_video";

    private MediaGenerationCapabilityFactory() {}

    /** 返回指定模型类型和提供方组合是否有已实现的运行时工具。 */
    public static boolean supports(ModelType modelType, ProviderType providerType) {
        if (modelType == ModelType.IMAGE_GEN) {
            return providerType == ProviderType.DASHSCOPE || providerType == ProviderType.OPENAI;
        }
        return modelType == ModelType.VIDEO_GEN && providerType == ProviderType.DASHSCOPE;
    }

    /**
     * 返回具体模型是否能由当前 AgentScope 2.0.1 官方工具实现。其 OpenAI 图片工具按 DALL-E
     * 的 URL 响应协议实现，尚不能正确消费 GPT Image 的 Base64 响应，因此不对该系列宣称可用。
     */
    public static boolean supports(ModelType modelType, ProviderType providerType, String modelName) {
        if (!supports(modelType, providerType)) {
            return false;
        }
        return modelType != ModelType.IMAGE_GEN
                || providerType != ProviderType.OPENAI
                || !normalizedModelName(modelName).contains("gpt-image");
    }

    /** 构造已通过启用状态校验的模型能力；不支持的组合返回 {@code null}。 */
    public static MediaGenerationCapability create(LlmModelEntity model, ProviderType providerType) {
        if (model == null || !supports(model.getModelType(), providerType, model.getModelName())) {
            return null;
        }
        if (model.getModelType() == ModelType.VIDEO_GEN) {
            return videoCapability(model);
        }
        return imageCapability(model, providerType);
    }

    private static MediaGenerationCapability imageCapability(LlmModelEntity model, ProviderType providerType) {
        MediaGenerationCapability capability = base(model, IMAGE, IMAGE_TOOL_NAME);
        if (providerType == ProviderType.DASHSCOPE) {
            applyDashscopeImageProfile(capability, normalizedModelName(model));
            return capability;
        }

        String modelName = normalizedModelName(model);
        if (modelName.contains("dall-e-3")) {
            capability.setSizes(List.of(
                    option("auto", "auto", null),
                    option("1:1", "1K", "1024x1024"),
                    option("16:9", "1K", "1792x1024"),
                    option("9:16", "1K", "1024x1792")));
            capability.setCounts(List.of(1));
        } else if (modelName.contains("dall-e-2")) {
            capability.setSizes(List.of(
                    option("auto", "auto", null),
                    option("1:1", "256", "256x256"),
                    option("1:1", "512", "512x512"),
                    option("1:1", "1K", "1024x1024")));
            capability.setCounts(integerRange(1, 10));
        } else {
            // OpenAI 兼容端点可能有完全不同的尺寸约束，保守地只交给模型默认值。
            capability.setSizes(List.of(option("auto", "auto", null)));
            capability.setCounts(List.of(1));
        }
        return capability;
    }

    private static MediaGenerationCapability videoCapability(LlmModelEntity model) {
        MediaGenerationCapability capability = base(model, VIDEO, VIDEO_TOOL_NAME);
        capability.setCounts(List.of(1));
        applyDashscopeVideoProfile(capability, normalizedModelName(model));
        return capability;
    }

    private static void applyDashscopeImageProfile(MediaGenerationCapability capability, String modelName) {
        if (modelName.contains("wanx-v1")) {
            capability.setSizes(List.of(
                    option("auto", "auto", null),
                    option("1:1", "1K", "1024x1024"),
                    option("2:3", "1K", "768x1152"),
                    option("16:9", "1K", "1280x720"),
                    option("9:16", "1K", "720x1280")));
            capability.setCounts(integerRange(1, 4));
            return;
        }
        if (modelName.contains("wan2.6-t2i") || modelName.contains("wan2.5-t2i")) {
            capability.setSizes(List.of(
                    option("1:1", "1K", "1280x1280"),
                    option("3:4", "1K", "1104x1472"),
                    option("4:3", "1K", "1472x1104"),
                    option("9:16", "1K", "960x1696"),
                    option("16:9", "1K", "1696x960")));
            capability.setCounts(integerRange(1, 4));
            return;
        }
        if (modelName.contains("wan2.2-t2i")
                || modelName.contains("wanx2.1-t2i")
                || modelName.contains("wanx2.0-t2i")) {
            capability.setSizes(List.of(
                    option("auto", "auto", null),
                    option("1:1", "1K", "1024x1024"),
                    option("3:4", "1K", "768x1024"),
                    option("4:3", "1K", "1024x768"),
                    option("16:9", "1K", "1280x720"),
                    option("9:16", "1K", "720x1280")));
            capability.setCounts(integerRange(1, 4));
            return;
        }
        if (modelName.contains("qwen-image-3.0") || modelName.contains("qwen-image-2.0")) {
            capability.setSizes(List.of(
                    option("auto", "auto", null),
                    option("1:1", "2K", "2048x2048"),
                    option("4:3", "2K", "2368x1728"),
                    option("3:4", "2K", "1728x2368"),
                    option("16:9", "2K", "2688x1536"),
                    option("9:16", "2K", "1536x2688")));
            capability.setCounts(integerRange(1, 6));
            return;
        }
        if (modelName.equals("qwen-image")
                || modelName.startsWith("qwen-image-plus")
                || modelName.startsWith("qwen-image-max")) {
            capability.setSizes(List.of(
                    option("16:9", "1K", "1664x928"),
                    option("4:3", "1K", "1472x1104"),
                    option("1:1", "1K", "1328x1328"),
                    option("3:4", "1K", "1104x1472"),
                    option("9:16", "1K", "928x1664")));
            capability.setCounts(List.of(1));
            return;
        }
        capability.setSizes(List.of(option("auto", "auto", null)));
        capability.setCounts(List.of(1));
    }

    private static void applyDashscopeVideoProfile(MediaGenerationCapability capability, String modelName) {
        if ((modelName.contains("wan2.7-t2v") || modelName.contains("wan2.6-t2v")) && !modelName.contains("-us")) {
            capability.setSizes(videoSizes(false, true, true));
            capability.setDurations(integerRange(2, 15));
            return;
        }
        if (modelName.contains("wan2.7-t2v-us") || modelName.contains("wan2.6-t2v-us")) {
            capability.setSizes(videoSizes(false, true, true));
            capability.setDurations(List.of(5, 10));
            return;
        }
        if (modelName.contains("wan2.5-t2v")) {
            capability.setSizes(videoSizes(true, true, true));
            capability.setDurations(List.of(5, 10));
            return;
        }
        if (modelName.contains("wan2.2-t2v")) {
            capability.setSizes(videoSizes(true, false, true));
            capability.setDurations(List.of(5));
            return;
        }
        if (modelName.contains("wanx2.1-t2v-turbo")) {
            capability.setSizes(videoSizes(true, true, false));
            capability.setDurations(List.of(5));
            return;
        }
        if (modelName.contains("wanx2.1-t2v-plus")) {
            capability.setSizes(videoSizes(false, true, false));
            capability.setDurations(List.of(5));
            return;
        }
        if (modelName.contains("happyhorse-1.1-t2v")) {
            capability.setSizes(videoSizes(true, true, true));
            capability.setDurations(integerRange(3, 15));
            return;
        }
        capability.setSizes(List.of(option("auto", "auto", null)));
    }

    private static List<MediaGenerationSizeOption> videoSizes(
            boolean include480p, boolean include720p, boolean include1080p) {
        List<MediaGenerationSizeOption> sizes = new ArrayList<>(13);
        if (include480p) {
            sizes.add(option("16:9", "480P", "832x480"));
            sizes.add(option("9:16", "480P", "480x832"));
            sizes.add(option("1:1", "480P", "624x624"));
        }
        if (include720p) {
            sizes.add(option("16:9", "720P", "1280x720"));
            sizes.add(option("9:16", "720P", "720x1280"));
            sizes.add(option("1:1", "720P", "960x960"));
            sizes.add(option("4:3", "720P", "1088x832"));
            sizes.add(option("3:4", "720P", "832x1088"));
        }
        if (include1080p) {
            sizes.add(option("16:9", "1080P", "1920x1080"));
            sizes.add(option("9:16", "1080P", "1080x1920"));
            sizes.add(option("1:1", "1080P", "1440x1440"));
            sizes.add(option("4:3", "1080P", "1632x1248"));
            sizes.add(option("3:4", "1080P", "1248x1632"));
        }
        return List.copyOf(sizes);
    }

    private static List<Integer> integerRange(int startInclusive, int endInclusive) {
        List<Integer> values = new ArrayList<>(endInclusive - startInclusive + 1);
        for (int value = startInclusive; value <= endInclusive; value++) {
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static String normalizedModelName(LlmModelEntity model) {
        return normalizedModelName(model.getModelName());
    }

    private static String normalizedModelName(String modelName) {
        return StringUtils.defaultString(modelName).toLowerCase(Locale.ROOT);
    }

    private static MediaGenerationCapability base(LlmModelEntity model, String kind, String toolName) {
        MediaGenerationCapability capability = new MediaGenerationCapability();
        capability.setKind(kind);
        capability.setToolName(toolName);
        capability.setModelLabel(StringUtils.defaultIfBlank(model.getName(), "应用绑定模型"));
        return capability;
    }

    private static MediaGenerationSizeOption option(String aspectRatio, String resolution, String size) {
        return new MediaGenerationSizeOption(aspectRatio, resolution, size);
    }
}
