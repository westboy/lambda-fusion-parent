package com.lambda.fusion.ai.apps.service;

import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiConstants.ProviderType;
import com.lambda.fusion.ai.apps.model.MediaGenerationCapability;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import com.lambda.fusion.ai.llm.model.entity.LlmProviderEntity;
import com.lambda.fusion.ai.llm.service.LlmModelService;
import com.lambda.fusion.ai.llm.service.LlmProviderService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 解析应用媒体模型绑定的唯一入口。安全视图与运行时工具装配共同消费本解析结果，避免分别解释
 * 模型类型、启用状态、provider 开关和 AgentScope 实现范围。
 *
 * @author Jin
 */
@Component
@RequiredArgsConstructor
public class MediaGenerationBindingResolver {

    private final LlmModelService llmModelService;
    private final LlmProviderService llmProviderService;

    /** 解析一个已绑定且当前可执行的媒体能力；未绑定返回空，无效绑定显式报错。 */
    public Optional<Binding> resolve(AppEntity app, ModelType expectedType) {
        String modelId = modelId(app, expectedType);
        if (StringUtils.isBlank(modelId)) {
            return Optional.empty();
        }
        return Optional.of(requireExecutable(modelId, expectedType));
    }

    /**
     * 校验并解析一个媒体模型。应用保存、能力视图和工具装配共用此入口，避免管理端接受、
     * 运行时再静默过滤同一份绑定。
     */
    public Binding requireExecutable(String modelId, ModelType expectedType) {
        if (StringUtils.isBlank(modelId)) {
            throw invalid("媒体生成模型不能为空");
        }
        LlmModelEntity model = llmModelService.loadById(modelId);
        if (model.getModelType() != expectedType) {
            throw invalid("模型类型与绑定不符，期望 " + expectedType);
        }
        if (!Boolean.TRUE.equals(model.getEnabled())) {
            throw invalid("媒体生成模型未启用: " + modelLabel(model));
        }
        LlmProviderEntity provider = llmProviderService.loadById(model.getProviderId());
        if (!Boolean.TRUE.equals(provider.getEnabled())) {
            throw invalid("媒体生成模型的提供方未启用: " + modelLabel(model));
        }
        if (!Boolean.TRUE.equals(provider.getEnableMediaGen())) {
            throw invalid("媒体生成模型的提供方未开启媒体生成: " + modelLabel(model));
        }
        ProviderType providerType = ProviderType.of(provider.getProviderType());
        MediaGenerationCapability capability = MediaGenerationCapabilityFactory.create(model, providerType);
        if (capability == null) {
            throw invalid("AgentScope 官方工具暂不支持该模型与提供方组合: " + modelLabel(model));
        }
        return new Binding(model, provider, providerType, capability);
    }

    private static String modelId(AppEntity app, ModelType type) {
        return switch (type) {
            case IMAGE_GEN -> app.getImageModelId();
            case VIDEO_GEN -> app.getVideoModelId();
            case AUDIO_GEN -> app.getAudioModelId();
            default -> null;
        };
    }

    private static String modelLabel(LlmModelEntity model) {
        return StringUtils.defaultIfBlank(model.getName(), model.getModelName());
    }

    private static AiBusinessException invalid(String message) {
        return new AiBusinessException(AiErrorCode.APP_MEDIA_BINDING_INVALID, message);
    }

    /** 已完成全部可用性校验的内部绑定。 */
    public record Binding(
            LlmModelEntity model,
            LlmProviderEntity provider,
            ProviderType providerType,
            MediaGenerationCapability capability) {}
}
