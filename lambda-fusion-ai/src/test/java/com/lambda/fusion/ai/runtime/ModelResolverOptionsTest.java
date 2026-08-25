package com.lambda.fusion.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import com.lambda.fusion.ai.llm.model.entity.LlmProviderEntity;
import com.lambda.fusion.ai.llm.service.LlmModelService;
import com.lambda.fusion.ai.llm.service.LlmProviderService;
import com.lambda.fusion.ai.security.KeyEncryptionService;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 验证模型表中的默认温度与最大输出 Token 能正确映射到各模型客户端使用的生成参数。 */
class ModelResolverOptionsTest {

    @Test
    void mapsConfiguredDefaultsToGenerateOptions() {
        LlmModelEntity model = new LlmModelEntity();
        model.setDefaultTemperature(new BigDecimal("0.70"));
        model.setDefaultMaxTokens(4096);

        GenerateOptions options = ModelResolver.resolveDefaultGenerateOptions(model);

        assertThat(options.getTemperature()).isEqualTo(0.7D);
        assertThat(options.getMaxTokens()).isEqualTo(4096);
    }

    @Test
    void keepsUnsetDefaultsNull() {
        GenerateOptions options = ModelResolver.resolveDefaultGenerateOptions(new LlmModelEntity());

        assertThat(options.getTemperature()).isNull();
        assertThat(options.getMaxTokens()).isNull();
    }

    @Test
    void adaptsDefaultsForOllama() {
        LlmModelEntity model = new LlmModelEntity();
        model.setDefaultTemperature(new BigDecimal("0.20"));
        model.setDefaultMaxTokens(8192);

        OllamaOptions options = OllamaOptions.fromGenerateOptions(ModelResolver.resolveDefaultGenerateOptions(model));

        assertThat(options.getTemperature()).isEqualTo(0.2D);
        assertThat(options.getMaxTokens()).isEqualTo(8192);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"openai", "dashscope", "ollama", "anthropic", "gemini", "deepseek", "glm", "kimi", "minimax"})
    void appliesConfiguredContextWindowToProviderModel(String providerType) {
        LlmModelEntity model = new LlmModelEntity();
        model.setId("model-1");
        model.setProviderId("provider-1");
        model.setModelName("test-model");
        model.setModelType(ModelType.CHAT);
        model.setContextWindowTokens(262_144);
        model.setEnabled(true);

        LlmProviderEntity provider = new LlmProviderEntity();
        provider.setId("provider-1");
        provider.setProviderType(providerType);
        provider.setApiKeyEncrypted("encrypted-key");
        provider.setEnabled(true);

        LlmModelService modelService = mock(LlmModelService.class);
        LlmProviderService providerService = mock(LlmProviderService.class);
        KeyEncryptionService encryptionService = mock(KeyEncryptionService.class);
        when(modelService.loadById("model-1")).thenReturn(model);
        when(providerService.loadById("provider-1")).thenReturn(provider);
        when(encryptionService.decrypt("encrypted-key")).thenReturn("test-key");

        Model resolved = new ModelResolver(modelService, providerService, encryptionService).apply("model-1");

        assertThat(resolved.getContextWindowSize()).isEqualTo(262_144);
    }
}
