package com.lambda.fusion.ai.runtime.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.lambda.fusion.ai.AiConstants.MediaGenerationOperation;
import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiConstants.ProviderType;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

class MediaToolAdapterRegistryTest {

    @Test
    void everyDeclaredRuntimeOperationCreatesItsRegisteredTool() {
        for (ProviderType providerType : ProviderType.values()) {
            for (MediaGenerationOperation operation : MediaToolAdapterRegistry.operations(providerType)) {
                Object adapter = MediaToolAdapterRegistry.create(
                        providerType, operation, "sk-test", "https://api.example.com/v1", "provider-model");
                Toolkit toolkit = new Toolkit();
                toolkit.registerTool(adapter);

                assertThat(toolkit.getToolNames()).contains(operation.getToolName());
                assertThat(MediaToolAdapterRegistry.supports(providerType, operation))
                        .isTrue();
            }
        }
    }

    @Test
    void derivesRuntimeModelTypesFromTheSameAdapterKeys() {
        assertThat(MediaToolAdapterRegistry.modelTypes(ProviderType.DASHSCOPE))
                .containsExactlyInAnyOrder(ModelType.values());
        assertThat(MediaToolAdapterRegistry.modelTypes(ProviderType.OPENAI))
                .containsExactlyInAnyOrder(ModelType.CHAT, ModelType.EMBEDDING, ModelType.IMAGE_GEN);
        assertThat(MediaToolAdapterRegistry.modelTypes(ProviderType.MINIMAX)).containsExactly(ModelType.CHAT);
        assertThat(MediaToolAdapterRegistry.operations(ProviderType.MINIMAX)).isEmpty();
    }
}
