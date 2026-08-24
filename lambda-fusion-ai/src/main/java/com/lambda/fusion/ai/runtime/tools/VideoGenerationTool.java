package com.lambda.fusion.ai.runtime.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.extensions.model.dashscope.tool.DashScopeMultiModalTool;
import java.time.Duration;

/**
 * 应用绑定的文生视频薄适配器。AgentScope 2.0.1 仅在 DashScope 官方多模态工具中提供视频生成；
 * 本类锁定应用绑定的模型名，保留官方文本生成视频参数，并原样返回 {@code VideoBlock} 结果。
 * 后续的 URL 下载、平台存储和签名预览由统一工具媒体物化链路负责。
 *
 * @author Jin
 */
public final class VideoGenerationTool {

    /** 工具名。 */
    public static final String TOOL_NAME = "text_to_video";

    /** 视频任务可能耗时数分钟；官方调用本身会阻塞直到任务成功或失败。 */
    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(10);

    private final DashScopeMultiModalTool dashscopeTool;
    private final String modelName;

    /** 包私有：供同包测试注入官方工具桩；对外使用 {@link #dashscope}。 */
    VideoGenerationTool(DashScopeMultiModalTool dashscopeTool, String modelName) {
        this.dashscopeTool = dashscopeTool;
        this.modelName = modelName;
    }

    /** 创建绑定指定 DashScope 视频模型的工具。 */
    public static VideoGenerationTool dashscope(String apiKey, String modelName) {
        return new VideoGenerationTool(new DashScopeMultiModalTool(apiKey), modelName);
    }

    /**
     * 根据文本生成视频。除模型名由应用配置锁定外，其余参数与 AgentScope 官方工具一致。
     *
     * @return AgentScope 官方视频结果；失败时为 ERROR 状态结果
     */
    @Tool(name = TOOL_NAME, description = "Generate a video from a text prompt and return an official video block.")
    public ToolResultBlock generateVideo(
            @ToolParam(name = "prompt", description = "The text prompt describing the video") String prompt,
            @ToolParam(name = "negative_prompt", description = "Elements to avoid", required = false)
                    String negativePrompt,
            @ToolParam(name = "audio_url", description = "Optional background audio URL", required = false)
                    String audioUrl,
            @ToolParam(name = "size", description = "Video size, e.g. 1280x720", required = false) String size,
            @ToolParam(name = "duration", description = "Video duration in seconds", required = false) Integer duration,
            @ToolParam(name = "shot_type", description = "single or multi", required = false) String shotType,
            @ToolParam(
                            name = "prompt_extend",
                            description = "Whether to extend the prompt automatically",
                            required = false)
                    Boolean promptExtend,
            @ToolParam(name = "watermark", description = "Whether to include a watermark", required = false)
                    Boolean watermark,
            @ToolParam(name = "seed", description = "Seed for reproducibility", required = false) Integer seed) {
        try {
            ToolResultBlock result = dashscopeTool
                    .dashscopeTextToVideo(
                            prompt,
                            modelName,
                            negativePrompt,
                            audioUrl,
                            normalizeSize(size),
                            duration,
                            shotType,
                            promptExtend,
                            watermark,
                            seed)
                    .block(CALL_TIMEOUT);
            return result != null ? result : ToolResultBlock.error("视频生成接口无响应");
        } catch (RuntimeException e) {
            return ToolResultBlock.error("视频生成失败：" + e.getMessage());
        }
    }

    /** DashScope 使用 {@code *} 分隔视频宽高，前端和模型可统一提交更常见的 {@code x}。 */
    private static String normalizeSize(String size) {
        return size == null || size.isBlank() ? null : size.replace('x', '*');
    }
}
