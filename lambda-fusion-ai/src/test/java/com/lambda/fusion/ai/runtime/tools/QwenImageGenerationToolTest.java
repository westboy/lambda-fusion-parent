package com.lambda.fusion.ai.runtime.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.URLSource;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** Qwen Image 多模态会话协议的请求体构造与响应解析单测。 */
class QwenImageGenerationToolTest {

    private static final String ENDPOINT =
            "https://dashscope.test/api/v1/services/aigc/multimodal-generation/generation";
    private static final String MODEL = "qwen-image-plus";

    @Test
    void shouldBuildNestedMultimodalRequestAndParseImageUrls() {
        AtomicReference<String> captured = new AtomicReference<>();
        String response =
                """
                {"output":{"choices":[{"message":{"content":[{"image":"https://cdn.test/a.png"},{"image":"https://cdn.test/b.png"}]}}]}}
                """;
        QwenImageGenerationTool tool = new QwenImageGenerationTool(MODEL, body -> {
            captured.set(body);
            return Mono.just(response);
        });

        ToolResultBlock result = tool.generateImage("一只猫", "1024x1024", 2, "模糊", true, "agent", false, 42, false)
                .block();

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        List<ContentBlock> output = result.getOutput();
        assertThat(output).hasSize(2);
        assertThat(((URLSource) ((ImageBlock) output.getFirst()).getSource()).getUrl())
                .isEqualTo("https://cdn.test/a.png");
        assertThat(captured.get())
                .contains("\"model\":\"qwen-image-plus\"")
                .contains("\"input\":{\"messages\":[{")
                .contains("\"role\":\"user\"")
                .contains("\"content\":[{\"text\":\"一只猫\"}]")
                .contains("\"parameters\":{")
                .contains("\"size\":\"1024*1024\"")
                .contains("\"negative_prompt\":\"模糊\"")
                .contains("\"prompt_extend_mode\":\"agent\"")
                .contains("\"enable_thinking\":false")
                .contains("\"watermark\":false");
    }

    @Test
    void shouldNormalizeGenerationEndpoint() {
        assertThat(QwenImageGenerationTool.generationEndpoint("https://dashscope.test/api/v1"))
                .isEqualTo(ENDPOINT);
        assertThat(QwenImageGenerationTool.generationEndpoint(ENDPOINT)).isEqualTo(ENDPOINT);
    }

    @Test
    void shouldRejectNonPositiveHttpTimeouts() {
        assertThatThrownBy(() -> QwenImageGenerationTool.create(
                        "sk-test", "https://dashscope.test/api/v1", MODEL, Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QwenImageGenerationTool.create(
                        "sk-test", "https://dashscope.test/api/v1", MODEL, Duration.ofSeconds(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReturnErrorWhenNoImageInResponse() {
        String response = "{\"output\":{\"choices\":[{\"message\":{\"content\":[{\"text\":\"无图\"}]}}]}}";
        QwenImageGenerationTool tool = new QwenImageGenerationTool(MODEL, body -> Mono.just(response));

        ToolResultBlock result = tool.generateImage("一只猫", null, null, null, null, null, null, null, null)
                .block();

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
    }

    @Test
    void shouldReturnErrorWhenRequestFails() {
        QwenImageGenerationTool tool =
                new QwenImageGenerationTool(MODEL, body -> Mono.error(new java.io.IOException("连接超时")));

        ToolResultBlock result = tool.generateImage("一只猫", null, null, null, null, null, null, null, null)
                .block();

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
    }

    @Test
    void shouldRejectMissingConfiguredModel() {
        assertThatThrownBy(() -> new QwenImageGenerationTool(" ", body -> Mono.just("{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
    }
}
