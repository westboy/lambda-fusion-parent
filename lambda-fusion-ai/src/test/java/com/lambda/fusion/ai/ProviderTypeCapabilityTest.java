package com.lambda.fusion.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiConstants.ProviderType;
import com.lambda.fusion.ai.runtime.media.MediaToolAdapterRegistry;
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
        assertThat(MediaToolAdapterRegistry.modelTypes(ProviderType.MINIMAX)).containsExactly(ModelType.CHAT);
        assertThat(ProviderType.MINIMAX.supports(ModelType.VIDEO_GEN)).isTrue();
        assertThat(MediaToolAdapterRegistry.supports(
                        ProviderType.MINIMAX, AiConstants.MediaGenerationOperation.TEXT_TO_VIDEO))
                .isFalse();
    }
}
