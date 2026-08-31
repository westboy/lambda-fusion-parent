package com.lambda.fusion.ai.runtime.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.URLSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** {@link CustomHttpImageTool} 的请求体构造与响应解析单测。 */
class CustomHttpImageToolTest {

    private static final String ENDPOINT = "https://vendor.test/api/v1/services/aigc/multimodal-generation/generation";
    private static final String MODEL = "qwen-image-2.0";

    @Test
    void shouldBuildRequestBodyAndParseImageUrls() {
        AtomicReference<String> captured = new AtomicReference<>();
        String response =
                """
                {"output":{"choices":[{"message":{"content":[{"image":"https://cdn.test/a.png"},{"image":"https://cdn.test/b.png"}]}}]}}
                """;
        CustomHttpImageTool tool = new CustomHttpImageTool(
                body -> {
                    captured.set(body);
                    return Mono.just(response);
                },
                ENDPOINT,
                MODEL);

        ToolResultBlock result = tool.generateImage("一只猫", "1024x1024", 2).block();

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        List<ContentBlock> output = result.getOutput();
        assertThat(output).hasSize(2);
        assertThat(((URLSource) ((ImageBlock) output.get(0)).getSource()).getUrl())
                .isEqualTo("https://cdn.test/a.png");
        String body = captured.get();
        assertThat(body).contains("\"model\":\"qwen-image-2.0\"");
        assertThat(body).contains("\"text\":\"一只猫\"");
        // 尺寸分隔符归一化为 *
        assertThat(body).contains("\"size\":\"1024*1024\"");
    }

    @Test
    void shouldTruncateToRequestedCount() {
        String response =
                """
                {"output":{"choices":[{"message":{"content":[{"image":"https://cdn.test/a.png"},{"image":"https://cdn.test/b.png"}]}}]}}
                """;
        CustomHttpImageTool tool = new CustomHttpImageTool(body -> Mono.just(response), ENDPOINT, MODEL);

        ToolResultBlock result = tool.generateImage("一只猫", null, 1).block();

        assertThat(result.getOutput()).hasSize(1);
    }

    @Test
    void shouldReturnErrorWhenNoImageInResponse() {
        String response = "{\"output\":{\"choices\":[{\"message\":{\"content\":[{\"text\":\"无图\"}]}}]}}";
        CustomHttpImageTool tool = new CustomHttpImageTool(body -> Mono.just(response), ENDPOINT, MODEL);

        ToolResultBlock result = tool.generateImage("一只猫", null, null).block();

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
    }

    @Test
    void shouldReturnErrorWhenRequestFails() {
        CustomHttpImageTool tool =
                new CustomHttpImageTool(body -> Mono.error(new java.io.IOException("连接超时")), ENDPOINT, MODEL);

        ToolResultBlock result = tool.generateImage("一只猫", null, null).block();

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
    }
}
