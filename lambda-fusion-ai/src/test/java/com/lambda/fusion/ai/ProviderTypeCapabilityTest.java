package com.lambda.fusion.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiConstants.ProviderType;
import org.junit.jupiter.api.Test;

class ProviderTypeCapabilityTest {

    @Test
    void exposesAllAgentScopeModelProviders() {
        assertThat(ProviderType.values())
                .extracting(ProviderType::getCode)
                .containsExactly(
                        "dashscope", "openai", "ollama", "anthropic", "gemini", "deepseek", "glm", "kimi", "minimax");
    }

    @Test
    void distinguishesVendorCatalogFromIntegratedAgentScopeRuntime() {
        assertThat(ProviderType.DASHSCOPE.getSupportedModelTypes()).containsExactlyInAnyOrder(ModelType.values());
        assertThat(ProviderType.OPENAI.getSupportedModelTypes())
                .containsExactlyInAnyOrder(ModelType.CHAT, ModelType.EMBEDDING, ModelType.IMAGE_GEN);
        assertThat(ProviderType.OLLAMA.getSupportedModelTypes())
                .containsExactlyInAnyOrder(ModelType.CHAT, ModelType.EMBEDDING);
        assertThat(ProviderType.KIMI.supports(ModelType.CHAT)).isTrue();
        assertThat(ProviderType.KIMI.supports(ModelType.IMAGE_GEN)).isFalse();
        assertThat(ProviderType.MINIMAX.getSupportedModelTypes())
                .containsExactlyInAnyOrder(
                        ModelType.CHAT, ModelType.IMAGE_GEN, ModelType.VIDEO_GEN, ModelType.AUDIO_GEN);
        assertThat(ProviderType.MINIMAX.getRuntimeModelTypes()).containsExactly(ModelType.CHAT);
        assertThat(ProviderType.MINIMAX.supports(ModelType.VIDEO_GEN)).isTrue();
        assertThat(ProviderType.MINIMAX.supportsRuntime(ModelType.VIDEO_GEN)).isFalse();
    }
}
