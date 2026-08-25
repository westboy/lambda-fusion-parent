package com.lambda.fusion.ai.runtime;

import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiConstants.ProviderType;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import com.lambda.fusion.ai.llm.model.entity.LlmProviderEntity;
import com.lambda.fusion.ai.llm.service.LlmModelService;
import com.lambda.fusion.ai.llm.service.LlmProviderService;
import com.lambda.fusion.ai.security.KeyEncryptionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 运行时模型解析器：按 modelId 从数据库加载模型/提供方配置并解密 API Key，构建 AgentScope
 * {@link Model} 客户端。无缓存——由 AgentFactory 在应用维度缓存 {@code HarnessAgent}，配置变更
 * 经失效缓存重建；实现 {@link Function} 便于后续作为 modelResolver 注入。
 *
 * @author Jin
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class ModelResolver implements Function<String, Model> {

    private final LlmModelService llmModelService;
    private final LlmProviderService llmProviderService;
    private final KeyEncryptionService keyEncryptionService;

    @Override
    public Model apply(String modelId) {
        LlmModelEntity model = llmModelService.loadById(modelId);
        if (model.getModelType() != ModelType.CHAT) {
            throw new AiBusinessException(AiErrorCode.LLM_MODEL_TYPE_NOT_SUPPORTED, model.getModelType());
        }
        if (Boolean.FALSE.equals(model.getEnabled())) {
            throw new AiBusinessException(AiErrorCode.LLM_MODEL_DISABLED, modelId);
        }
        LlmProviderEntity provider = llmProviderService.loadById(model.getProviderId());
        if (Boolean.FALSE.equals(provider.getEnabled())) {
            throw new AiBusinessException(AiErrorCode.LLM_PROVIDER_DISABLED, provider.getId());
        }
        return buildModel(model, provider);
    }

    private Model buildModel(LlmModelEntity model, LlmProviderEntity provider) {
        ProviderType type = ProviderType.of(provider.getProviderType());
        if (type == null) {
            throw new AiBusinessException(AiErrorCode.LLM_PROVIDER_TYPE_NOT_SUPPORTED, provider.getProviderType());
        }
        String apiKey = keyEncryptionService.decrypt(provider.getApiKeyEncrypted());
        GenerateOptions defaultOptions = resolveDefaultGenerateOptions(model);
        ModelCreationContext.Builder context =
                ModelCreationContext.builder().apiKey(apiKey).baseUrl(provider.getBaseUrl()).stream(true);
        if (type == ProviderType.OLLAMA) {
            context.component(OllamaOptions.class, OllamaOptions.fromGenerateOptions(defaultOptions));
        } else {
            context.component(GenerateOptions.class, defaultOptions);
        }
        if (model.getContextWindowTokens() != null) {
            context.option("contextWindowSize", model.getContextWindowTokens());
        }
        return ModelRegistry.resolve(type.getCode() + ":" + model.getModelName(), context.build());
    }

    /**
     * 将模型表中的默认生成参数映射为 AgentScope 统一配置。最大 Token 表示单次最大输出量，
     * 不用于表达模型上下文窗口；空值保留服务商默认行为。
     */
    static GenerateOptions resolveDefaultGenerateOptions(LlmModelEntity model) {
        GenerateOptions.Builder builder = GenerateOptions.builder();
        if (model.getDefaultTemperature() != null) {
            builder.temperature(model.getDefaultTemperature().doubleValue());
        }
        if (model.getDefaultMaxTokens() != null) {
            builder.maxTokens(model.getDefaultMaxTokens());
        }
        return builder.build();
    }
}
