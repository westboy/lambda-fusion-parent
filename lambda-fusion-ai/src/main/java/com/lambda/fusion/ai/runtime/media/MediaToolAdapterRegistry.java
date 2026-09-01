package com.lambda.fusion.ai.runtime.media;

import com.lambda.fusion.ai.AiConstants.MediaGenerationOperation;
import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiConstants.ProviderType;
import com.lambda.fusion.ai.runtime.tools.AudioGenerationTool;
import com.lambda.fusion.ai.runtime.tools.ImageGenerationTool;
import com.lambda.fusion.ai.runtime.tools.VideoGenerationTool;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 应用绑定媒体工具的运行时注册表。
 *
 * <p>注册键既是管理端和保存校验使用的运行能力事实，也是运行时创建适配器的唯一入口，避免能力矩阵与
 * 工具工厂分别维护后发生漂移。
 */
public final class MediaToolAdapterRegistry {

    private static final Map<Key, AdapterFactory> FACTORIES = Map.ofEntries(
            entry(
                    ProviderType.DASHSCOPE,
                    MediaGenerationOperation.TEXT_TO_IMAGE,
                    config -> ImageGenerationTool.dashscope(config.apiKey(), config.modelName())),
            entry(
                    ProviderType.DASHSCOPE,
                    MediaGenerationOperation.TEXT_TO_VIDEO,
                    config -> VideoGenerationTool.dashscope(config.apiKey(), config.modelName())),
            entry(
                    ProviderType.DASHSCOPE,
                    MediaGenerationOperation.IMAGE_TO_VIDEO,
                    config -> VideoGenerationTool.dashscope(config.apiKey(), config.modelName())),
            entry(
                    ProviderType.DASHSCOPE,
                    MediaGenerationOperation.FIRST_LAST_FRAME_TO_VIDEO,
                    config -> VideoGenerationTool.dashscope(config.apiKey(), config.modelName())),
            entry(
                    ProviderType.DASHSCOPE,
                    MediaGenerationOperation.TEXT_TO_SPEECH,
                    config -> AudioGenerationTool.dashscope(config.apiKey(), config.modelName())),
            entry(
                    ProviderType.OPENAI,
                    MediaGenerationOperation.TEXT_TO_IMAGE,
                    config -> ImageGenerationTool.openai(config.apiKey(), config.baseUrl(), config.modelName())));

    private MediaToolAdapterRegistry() {}

    public static boolean supports(ProviderType providerType, MediaGenerationOperation operation) {
        return providerType != null && operation != null && FACTORIES.containsKey(new Key(providerType, operation));
    }

    /** 返回指定提供方当前真正具备适配器的媒体操作。 */
    public static Set<MediaGenerationOperation> operations(ProviderType providerType) {
        if (providerType == null) {
            return Set.of();
        }
        EnumSet<MediaGenerationOperation> operations = EnumSet.noneOf(MediaGenerationOperation.class);
        FACTORIES.keySet().stream()
                .filter(key -> key.providerType() == providerType)
                .map(Key::operation)
                .forEach(operations::add);
        return Set.copyOf(operations);
    }

    /** 合并 AgentScope 核心模型适配器与本注册表中的媒体适配器能力。 */
    public static Set<ModelType> modelTypes(ProviderType providerType) {
        if (providerType == null) {
            return Set.of();
        }
        EnumSet<ModelType> modelTypes = EnumSet.noneOf(ModelType.class);
        modelTypes.addAll(providerType.getRuntimeCoreModelTypes());
        operations(providerType).stream()
                .map(MediaGenerationOperation::getModelType)
                .forEach(modelTypes::add);
        return Set.copyOf(modelTypes);
    }

    /** 根据已校验的提供方、操作和绑定配置创建唯一匹配的 AgentScope 媒体适配器。 */
    public static Object create(
            ProviderType providerType,
            MediaGenerationOperation operation,
            String apiKey,
            String baseUrl,
            String modelName) {
        AdapterFactory factory = FACTORIES.get(new Key(
                Objects.requireNonNull(providerType, "providerType"), Objects.requireNonNull(operation, "operation")));
        if (factory == null) {
            throw new IllegalArgumentException(
                    "没有匹配的 AgentScope 媒体工具: provider=" + providerType + ", operation=" + operation);
        }
        return factory.create(new AdapterConfig(apiKey, baseUrl, modelName));
    }

    private static Map.Entry<Key, AdapterFactory> entry(
            ProviderType providerType, MediaGenerationOperation operation, AdapterFactory factory) {
        return Map.entry(new Key(providerType, operation), factory);
    }

    private record Key(ProviderType providerType, MediaGenerationOperation operation) {}

    private record AdapterConfig(String apiKey, String baseUrl, String modelName) {}

    @FunctionalInterface
    private interface AdapterFactory {

        Object create(AdapterConfig config);
    }
}
