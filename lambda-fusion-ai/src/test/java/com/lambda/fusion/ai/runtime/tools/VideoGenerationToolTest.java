package com.lambda.fusion.ai.runtime.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.extensions.model.dashscope.tool.DashScopeMultiModalTool;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

/** AgentScope 官方文生视频工具的绑定模型薄适配测试。 */
class VideoGenerationToolTest {

    @Test
    void shouldPassThroughOfficialVideoBlockAndNormalizeSize() {
        DashScopeMultiModalTool client = Mockito.mock(DashScopeMultiModalTool.class);
        ToolResultBlock official = ToolResultBlock.of(VideoBlock.builder()
                .source(URLSource.builder()
                        .url("https://video.example.com/clip.mp4")
                        .build())
                .build());
        when(client.dashscopeTextToVideo(
                        anyString(),
                        anyString(),
                        isNull(),
                        isNull(),
                        eq("1280*720"),
                        eq(5),
                        eq("single"),
                        eq(Boolean.TRUE),
                        eq(Boolean.FALSE),
                        eq(42)))
                .thenReturn(Mono.just(official));
        VideoGenerationTool tool = new VideoGenerationTool(client, "wan2.6-t2v");

        ToolResultBlock result = tool.generateVideo("海边日落", null, null, "1280x720", 5, "single", true, false, 42);

        assertThat(result).isSameAs(official);
        assertThat(result.getOutput()).singleElement().isInstanceOf(VideoBlock.class);
        verify(client).dashscopeTextToVideo("海边日落", "wan2.6-t2v", null, null, "1280*720", 5, "single", true, false, 42);
    }

    @Test
    void shouldPassThroughOfficialError() {
        DashScopeMultiModalTool client = Mockito.mock(DashScopeMultiModalTool.class);
        ToolResultBlock official = ToolResultBlock.error("视频模型未开通");
        when(client.dashscopeTextToVideo(
                        anyString(), anyString(), any(), any(), isNull(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(official));

        ToolResultBlock result = new VideoGenerationTool(client, "wan2.6-t2v")
                .generateVideo("测试", null, null, null, null, null, null, null, null);

        assertThat(result).isSameAs(official);
    }

    @Test
    void shouldConvertClientExceptionToErrorResult() {
        DashScopeMultiModalTool client = Mockito.mock(DashScopeMultiModalTool.class);
        when(client.dashscopeTextToVideo(
                        anyString(), anyString(), any(), any(), isNull(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("调用超时"));

        ToolResultBlock result = new VideoGenerationTool(client, "wan2.6-t2v")
                .generateVideo("测试", null, null, null, null, null, null, null, null);

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(result)).contains("视频生成失败：").contains("调用超时");
    }

    private static String textOf(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining("\n"));
    }
}
