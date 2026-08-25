package com.lambda.fusion.ai.runtime.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.extensions.model.dashscope.tool.DashScopeMultiModalTool;
import io.agentscope.extensions.model.openai.tool.OpenAIMultiModalTool;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

/**
 * 文生图薄适配器行为测试：官方成功/错误结果原样透传，调用异常交还 AgentScope；绑定模型名、URL
 * 返回策略和尺寸分隔符按提供方约定传给 AgentScope 官方工具。
 *
 * @author Jin
 */
class ImageGenerationToolTest {

    @Test
    void shouldPassThroughOfficialImageBlocksForDashscope() {
        DashScopeMultiModalTool client = Mockito.mock(DashScopeMultiModalTool.class);
        ToolResultBlock official = resultOf("https://img.example.com/1.png");
        when(client.dashscopeTextToImage(anyString(), anyString(), any(), eq("1024*1024"), eq(Boolean.FALSE)))
                .thenReturn(Mono.just(official));
        ImageGenerationTool tool = new ImageGenerationTool(client, null, "wanx2.1-t2i-turbo");

        ToolResultBlock result =
                tool.generateImage("一只猫", "1024x1024", 1, null, null).block(Duration.ofSeconds(5));

        assertThat(result).isSameAs(official);
        assertThat(result.getOutput()).anyMatch(ImageBlock.class::isInstance);
        // 绑定的模型名与调用参数透传：模型名锁定、禁用 base64、尺寸分隔符归一化为 *
        verify(client).dashscopeTextToImage("一只猫", "wanx2.1-t2i-turbo", 1, "1024*1024", Boolean.FALSE);
    }

    @Test
    void shouldPassThroughOfficialImageBlocksForOpenai() {
        OpenAIMultiModalTool client = Mockito.mock(OpenAIMultiModalTool.class);
        ToolResultBlock official = resultOf("https://img.example.com/2.png");
        when(client.openaiTextToImage(anyString(), anyString(), any(), eq("1024x1024"), isNull(), eq("url")))
                .thenReturn(Mono.just(official));
        ImageGenerationTool tool = new ImageGenerationTool(null, client, "dall-e-3");

        ToolResultBlock result =
                tool.generateImage("一只猫", "1024*1024", 1, null, null).block(Duration.ofSeconds(5));

        assertThat(result).isSameAs(official);
        assertThat(result.getOutput()).anyMatch(ImageBlock.class::isInstance);
        // OpenAI 分隔符为 x；强制 URL 响应，避免 Base64 进入 Agent 上下文
        verify(client).openaiTextToImage("一只猫", "dall-e-3", 1, "1024x1024", null, "url");
    }

    @Test
    void shouldPassThroughOfficialErrorResult() {
        ToolResultBlock officialError = ToolResultBlock.error("key 未开通生图权限");
        DashScopeMultiModalTool client = Mockito.mock(DashScopeMultiModalTool.class);
        when(client.dashscopeTextToImage(anyString(), anyString(), any(), isNull(), eq(Boolean.FALSE)))
                .thenReturn(Mono.just(officialError));
        ImageGenerationTool tool = new ImageGenerationTool(client, null, "wanx2.1-t2i-turbo");

        ToolResultBlock result =
                tool.generateImage("一只猫", null, null, null, null).block(Duration.ofSeconds(5));

        // 官方 ERROR 结果原样透传，保留官方错误文本供模型转告
        assertThat(result).isSameAs(officialError);
        assertThat(textOf(result)).contains("key 未开通生图权限");
    }

    @Test
    void shouldLeaveClientExceptionToAgentScopeToolExecutor() {
        DashScopeMultiModalTool client = Mockito.mock(DashScopeMultiModalTool.class);
        when(client.dashscopeTextToImage(anyString(), anyString(), any(), isNull(), eq(Boolean.FALSE)))
                .thenThrow(new IllegalStateException("调用超时"));
        ImageGenerationTool tool = new ImageGenerationTool(client, null, "wanx2.1-t2i-turbo");

        assertThatThrownBy(
                        () -> tool.generateImage("一只猫", null, null, null, null).block(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("调用超时");
    }

    /** 构造含单张 URL 图片的成功工具结果。 */
    private static ToolResultBlock resultOf(String url) {
        return ToolResultBlock.of(List.of(ImageBlock.builder()
                .source(URLSource.builder().url(url).build())
                .build()));
    }

    /** 拼接结果中的全部文本块。 */
    private static String textOf(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining("\n"));
    }
}
