package com.lambda.fusion.ai.runtime.tools;

import com.lambda.fusion.ai.AiConstants.MediaToolName;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.extensions.model.dashscope.tool.DashScopeMultiModalTool;
import reactor.core.publisher.Mono;

/**
 * 应用绑定的文生视频薄适配器。AgentScope 2.0.1 仅在 DashScope 官方多模态工具中提供视频生成；
 * 本类锁定应用绑定的模型名，保留官方文本生成视频参数，并原样返回 {@code VideoBlock} 结果。
 * 后续的 URL 下载、平台存储和签名预览由统一工具媒体物化链路负责。
 *
 * @author Jin
 */
public final class VideoGenerationTool {

    /** 工具名。 */
    public static final String TOOL_NAME = MediaToolName.TEXT_TO_VIDEO;

    /** 单图生视频工具名。 */
    public static final String IMAGE_TO_VIDEO_TOOL_NAME = MediaToolName.IMAGE_TO_VIDEO;

    /** 首尾帧生视频工具名。 */
    public static final String FIRST_LAST_FRAME_TO_VIDEO_TOOL_NAME = MediaToolName.FIRST_LAST_FRAME_TO_VIDEO;

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
    public Mono<ToolResultBlock> generateVideo(
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
        return dashscopeTool.dashscopeTextToVideo(
                prompt,
                modelName,
                negativePrompt,
                audioUrl,
                normalizeSize(size),
                duration,
                shotType,
                promptExtend,
                watermark,
                seed);
    }

    /** 调用 AgentScope 官方单图生视频能力，模型名仍由应用绑定锁定。 */
    @Tool(
            name = IMAGE_TO_VIDEO_TOOL_NAME,
            description = "Generate a video from one source image and return an official video block.")
    public Mono<ToolResultBlock> generateVideoFromImage(
            @ToolParam(name = "prompt", description = "Optional motion and content prompt", required = false)
                    String prompt,
            @ToolParam(name = "image_url", description = "Public URL of the source image") String imageUrl,
            @ToolParam(name = "audio_url", description = "Optional background audio URL", required = false)
                    String audioUrl,
            @ToolParam(name = "negative_prompt", description = "Elements to avoid", required = false)
                    String negativePrompt,
            @ToolParam(name = "template", description = "Optional video effect template", required = false)
                    String template,
            @ToolParam(name = "resolution", description = "Video resolution, e.g. 720P", required = false)
                    String resolution,
            @ToolParam(name = "duration", description = "Video duration in seconds", required = false) Integer duration,
            @ToolParam(name = "shot_type", description = "single or multi", required = false) String shotType,
            @ToolParam(name = "audio", description = "Whether the generated video includes audio", required = false)
                    Boolean audio,
            @ToolParam(
                            name = "prompt_extend",
                            description = "Whether to extend the prompt automatically",
                            required = false)
                    Boolean promptExtend,
            @ToolParam(name = "watermark", description = "Whether to include a watermark", required = false)
                    Boolean watermark,
            @ToolParam(name = "seed", description = "Seed for reproducibility", required = false) Integer seed) {
        return dashscopeTool.dashscopeImageToVideo(
                prompt,
                modelName,
                imageUrl,
                audioUrl,
                negativePrompt,
                template,
                resolution,
                duration,
                shotType,
                audio,
                promptExtend,
                watermark,
                seed);
    }

    /** 调用 AgentScope 官方首尾帧生视频能力，模型名仍由应用绑定锁定。 */
    @Tool(
            name = FIRST_LAST_FRAME_TO_VIDEO_TOOL_NAME,
            description = "Generate a video between first and last frame images and return an official video block.")
    public Mono<ToolResultBlock> generateVideoFromFrames(
            @ToolParam(name = "prompt", description = "Optional motion and content prompt", required = false)
                    String prompt,
            @ToolParam(name = "first_frame_url", description = "Public URL of the first frame image")
                    String firstFrameUrl,
            @ToolParam(name = "last_frame_url", description = "Public URL of the last frame image", required = false)
                    String lastFrameUrl,
            @ToolParam(name = "negative_prompt", description = "Elements to avoid", required = false)
                    String negativePrompt,
            @ToolParam(name = "template", description = "Optional video effect template", required = false)
                    String template,
            @ToolParam(name = "resolution", description = "Video resolution, e.g. 720P", required = false)
                    String resolution,
            @ToolParam(
                            name = "prompt_extend",
                            description = "Whether to extend the prompt automatically",
                            required = false)
                    Boolean promptExtend,
            @ToolParam(name = "watermark", description = "Whether to include a watermark", required = false)
                    Boolean watermark,
            @ToolParam(name = "seed", description = "Seed for reproducibility", required = false) Integer seed) {
        return dashscopeTool.dashscopeFirstAndLastFrameImageToVideo(
                prompt,
                modelName,
                firstFrameUrl,
                lastFrameUrl,
                negativePrompt,
                template,
                resolution,
                promptExtend,
                watermark,
                seed);
    }

    /** DashScope 使用 {@code *} 分隔视频宽高，前端和模型可统一提交更常见的 {@code x}。 */
    private static String normalizeSize(String size) {
        return size == null || size.isBlank() ? null : size.replace('x', '*');
    }
}
