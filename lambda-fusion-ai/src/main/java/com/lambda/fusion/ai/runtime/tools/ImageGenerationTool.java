package com.lambda.fusion.ai.runtime.tools;

import com.lambda.fusion.ai.AiConstants.MediaToolName;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.extensions.model.dashscope.tool.DashScopeMultiModalTool;
import io.agentscope.extensions.model.openai.tool.OpenAIMultiModalTool;
import reactor.core.publisher.Mono;

/**
 * 应用绑定的文生图薄适配器：调用 AgentScope 官方多模态工具并原样返回官方媒体块。
 *
 * <p>仅保留平台必须施加的策略：模型名由应用绑定而非交给对话模型自选；图片始终返回 URL，避免
 * Base64 进入上下文；不同提供方的尺寸分隔符在边界归一化。媒体持久化、事件信封和前端展示均消费
 * 官方媒体块，不再额外拼 URL JSON 或要求模型生成 Markdown。
 *
 * @author Jin
 */
public final class ImageGenerationTool {

    /** 工具名。 */
    public static final String TOOL_NAME = MediaToolName.TEXT_TO_IMAGE;

    private final DashScopeMultiModalTool dashscopeTool;
    private final OpenAIMultiModalTool openaiTool;
    private final String modelName;

    /** 包私有：供同包测试注入官方工具桩；对外请用 {@link #dashscope}/{@link #openai} 工厂。 */
    ImageGenerationTool(DashScopeMultiModalTool dashscopeTool, OpenAIMultiModalTool openaiTool, String modelName) {
        this.dashscopeTool = dashscopeTool;
        this.openaiTool = openaiTool;
        this.modelName = modelName;
    }

    /**
     * 创建 DashScope 生图工具（wanx/qwen-image 系列）。
     *
     * @param apiKey 提供方 API Key
     * @param modelName 模型注册表绑定的生图模型名
     * @return 生图工具
     */
    public static ImageGenerationTool dashscope(String apiKey, String modelName) {
        return new ImageGenerationTool(new DashScopeMultiModalTool(apiKey), null, modelName);
    }

    /**
     * 创建 OpenAI 兼容生图工具（DALL-E 2/3 或兼容端点自有的 URL 响应生图模型）。
     *
     * @param apiKey 提供方 API Key
     * @param baseUrl 服务地址；空走官方默认
     * @param modelName 模型注册表绑定的生图模型名
     * @return 生图工具
     */
    public static ImageGenerationTool openai(String apiKey, String baseUrl, String modelName) {
        // 构造校验要求非空默认模型名；实际调用始终显式传入绑定的模型名，默认值不会被使用
        return new ImageGenerationTool(null, new OpenAIMultiModalTool(apiKey, baseUrl, modelName), modelName);
    }

    /**
     * 按文本提示生成图片。
     *
     * @param prompt 图片内容描述
     * @param size 图片尺寸（格式随提供方，DashScope 用 {@code 1024*1024}，OpenAI 用 {@code 1024x1024}）
     * @param n 生成张数
     * @return AgentScope 官方图片结果；失败时为 ERROR 状态结果
     */
    @Tool(
            name = TOOL_NAME,
            description = "Generate one or more images from a text prompt and return official image blocks.")
    public Mono<ToolResultBlock> generateImage(
            @ToolParam(name = "prompt", description = "The text prompt describing the image to generate") String prompt,
            @ToolParam(name = "size", description = "Image size, e.g. '1024*1024' or '1024x1024'", required = false)
                    String size,
            @ToolParam(name = "n", description = "Number of images to generate", required = false) Integer n,
            @ToolParam(name = "quality", description = "Image quality, e.g. standard or hd", required = false)
                    String quality) {
        if (dashscopeTool != null) {
            return dashscopeTool.dashscopeTextToImage(prompt, modelName, n, normalizeSize(size, '*'), Boolean.FALSE);
        }
        return openaiTool.openaiTextToImage(prompt, modelName, n, normalizeSize(size, 'x'), quality, "url");
    }

    /** 尺寸分隔符归一化：DashScope 用 {@code *}、OpenAI 用 {@code x}，统一到目标分隔符。 */
    private static String normalizeSize(String size, char separator) {
        if (size == null || size.isBlank()) {
            return null;
        }
        char other = separator == '*' ? 'x' : '*';
        return size.replace(other, separator);
    }
}
