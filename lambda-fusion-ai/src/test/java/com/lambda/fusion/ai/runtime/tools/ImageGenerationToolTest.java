package com.lambda.fusion.ai.runtime.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.URLSource;
import io.agentscope.extensions.model.dashscope.tool.DashScopeMultiModalTool;
import io.agentscope.extensions.model.openai.tool.OpenAIMultiModalTool;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

/**
 * 文生图工具的行为测试：成功结果同时包含 URL 的 JSON 文本块（模型可转写进回复、前端工具组件可
 * {@code JSON.parse} 渲染）与官方图片块（多模态模型可直读、富渲染可复用）；官方错误结果原样透传、
 * 调用异常转为 ERROR 结果；绑定的模型名与调用参数按提供方约定透传官方工具（DashScope 尺寸用
 * {@code *}、OpenAI 用 {@code x}）。
 *
 * @author Jin
 */
class ImageGenerationToolTest {

    @Test
    void shouldReturnJsonPayloadAndOfficialImageBlocksForDashscope() {
        DashScopeMultiModalTool client = Mockito.mock(DashScopeMultiModalTool.class);
        when(client.dashscopeTextToImage(anyString(), anyString(), any(), eq("1024*1024"), eq(Boolean.FALSE)))
                .thenReturn(Mono.just(resultOf("https://img.example.com/1.png")));
        ImageGenerationTool tool = new ImageGenerationTool(client, null, "wanx2.1-t2i-turbo");

        ToolResultBlock result = tool.generateImage("一只猫", "1024x1024", 1);

        // 文本块携带 URL 的 JSON：前端工具组件 JSON.parse 后渲染图片
        assertThat(textOf(result)).contains("\"images\"").contains("https://img.example.com/1.png");
        // 官方图片块原样保留：多模态模型可直读生成图片
        assertThat(result.getOutput()).anyMatch(ImageBlock.class::isInstance);
        // 绑定的模型名与调用参数透传：模型名锁定、禁用 base64、尺寸分隔符归一化为 *
        verify(client).dashscopeTextToImage("一只猫", "wanx2.1-t2i-turbo", 1, "1024*1024", Boolean.FALSE);
    }

    @Test
    void shouldReturnJsonPayloadAndOfficialImageBlocksForOpenai() {
        OpenAIMultiModalTool client = Mockito.mock(OpenAIMultiModalTool.class);
        when(client.openaiTextToImage(anyString(), anyString(), any(), eq("1024x1024"), isNull(), isNull()))
                .thenReturn(Mono.just(resultOf("https://img.example.com/2.png")));
        ImageGenerationTool tool = new ImageGenerationTool(null, client, "dall-e-3");

        ToolResultBlock result = tool.generateImage("一只猫", "1024*1024", 2);

        assertThat(textOf(result)).contains("\"images\"").contains("https://img.example.com/2.png");
        assertThat(result.getOutput()).anyMatch(ImageBlock.class::isInstance);
        // OpenAI 分隔符为 x；质量与响应格式交由默认值，不做二选一
        verify(client).openaiTextToImage("一只猫", "dall-e-3", 2, "1024x1024", null, null);
    }

    @Test
    void shouldPassThroughOfficialErrorResult() {
        ToolResultBlock officialError = ToolResultBlock.error("key 未开通生图权限");
        DashScopeMultiModalTool client = Mockito.mock(DashScopeMultiModalTool.class);
        when(client.dashscopeTextToImage(anyString(), anyString(), any(), isNull(), eq(Boolean.FALSE)))
                .thenReturn(Mono.just(officialError));
        ImageGenerationTool tool = new ImageGenerationTool(client, null, "wanx2.1-t2i-turbo");

        ToolResultBlock result = tool.generateImage("一只猫", null, null);

        // 官方 ERROR 结果原样透传，保留官方错误文本供模型转告
        assertThat(result).isSameAs(officialError);
        assertThat(textOf(result)).contains("key 未开通生图权限");
    }

    @Test
    void shouldWrapClientExceptionAsErrorResult() {
        DashScopeMultiModalTool client = Mockito.mock(DashScopeMultiModalTool.class);
        when(client.dashscopeTextToImage(anyString(), anyString(), any(), isNull(), eq(Boolean.FALSE)))
                .thenThrow(new IllegalStateException("调用超时"));
        ImageGenerationTool tool = new ImageGenerationTool(client, null, "wanx2.1-t2i-turbo");

        ToolResultBlock result = tool.generateImage("一只猫", null, null);

        // 异常转为 ERROR 结果而非抛出：模型如实转告用户，不中断对话流程
        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(result)).contains("图片生成失败：").contains("调用超时");
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
