package com.lambda.fusion.ai.runtime.tools;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.dashscope.tool.DashScopeMultiModalTool;
import io.agentscope.extensions.model.openai.tool.OpenAIMultiModalTool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文生图工具：调用 AgentScope 官方多模态工具的图像生成能力，返回结果在官方 {@link ImageBlock}
 * 之外前置一个图片 URL 的 JSON 文本块（如 {@code {"images":["..."]}}）。
 *
 * <p>JSON 文本块面向两类消费方：
 * <ul>
 * <li>对话模型：消息转换器把工具结果里的 ImageBlock 序列化为图像内容项（URL 不进入文本上下文），
 * 模型只能从 JSON 文本里拿到 URL，把图片以 Markdown 形式写进回复；
 * <li>前端工具组件：本系统事件流只投影文本工具结果，JSON 经 {@code TOOL_CALL_RESULT.content}
 * 下发，tdesign-chat 按工具名注册的组件 {@code JSON.parse} 后渲染图片（markdown 文本会解析失败）。
 * </ul>
 *
 * <p>官方 ImageBlock 原样保留：多模态对话模型可直读生成图片，富渲染迭代直接复用该块。
 *
 * <p>不在 {@code @AiTools} 全局注册：按应用绑定的 {@code IMAGE_GEN} 模型在 {@code ToolkitAssembler}
 * 装配期条件注册。生图模型名由模型注册表绑定固定（不交给模型自选），始终禁用 base64 返回
 * （防止兆级 base64 灌入上下文）；官方错误结果原样透传，其余失败以 ERROR 状态结果返回，
 * 由模型如实转告，不中断对话。
 *
 * @author Jin
 */
public final class ImageGenerationTool {

    /** 工具名。 */
    public static final String TOOL_NAME = "text_to_image";

    /** 生图 SDK 调用上限：任务型生图接口耗时可达数十秒，超时按失败处理。 */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(180);

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
     * 创建 OpenAI 兼容生图工具（dall-e-3 / gpt-image-1 / 兼容端点自有生图模型）。
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
     * @return 成功时为「URL JSON 文本块 + 官方图片块」的结果；失败时为 ERROR 状态结果
     */
    @Tool(
            name = TOOL_NAME,
            description = "Generate one or more images from a text prompt. The result contains the image"
                    + " URLs in JSON (e.g. {\"images\": [\"...\"]}) together with the image blocks."
                    + " Always embed the returned image URLs in your reply using Markdown image"
                    + " syntax, e.g. ![image](url), so the user can see the generated images.")
    public ToolResultBlock generateImage(
            @ToolParam(name = "prompt", description = "The text prompt describing the image to generate") String prompt,
            @ToolParam(name = "size", description = "Image size, e.g. '1024*1024' or '1024x1024'", required = false)
                    String size,
            @ToolParam(name = "n", description = "Number of images to generate", required = false) Integer n) {
        try {
            ToolResultBlock result;
            if (dashscopeTool != null) {
                result = dashscopeTool
                        .dashscopeTextToImage(prompt, modelName, n, normalizeSize(size, '*'), Boolean.FALSE)
                        .block(CALL_TIMEOUT);
            } else {
                result = openaiTool
                        .openaiTextToImage(prompt, modelName, n, normalizeSize(size, 'x'), null, null)
                        .block(CALL_TIMEOUT);
            }
            if (result == null || result.getState() == ToolResultState.ERROR) {
                // 官方错误结果原样透传：保留 ERROR 状态与官方错误文本
                return result != null ? result : ToolResultBlock.error("生图接口无响应");
            }
            List<String> urls = extractUrls(result.getOutput());
            if (urls.isEmpty()) {
                return ToolResultBlock.error("生图结果未包含可访问的图片");
            }
            return withJsonPayload(urls, result.getOutput());
        } catch (RuntimeException e) {
            return ToolResultBlock.error("图片生成失败：" + e.getMessage());
        }
    }

    /** 尺寸分隔符归一化：DashScope 用 {@code *}、OpenAI 用 {@code x}，统一到目标分隔符。 */
    private static String normalizeSize(String size, char separator) {
        if (size == null || size.isBlank()) {
            return null;
        }
        char other = separator == '*' ? 'x' : '*';
        return size.replace(other, separator);
    }

    /** 抽取结果中的图片 URL（按块顺序）。 */
    private static List<String> extractUrls(List<ContentBlock> output) {
        List<String> urls = new ArrayList<>();
        for (ContentBlock block : output) {
            if (block instanceof ImageBlock image && image.getSource() instanceof URLSource source) {
                urls.add(source.getUrl());
            }
        }
        return urls;
    }

    /** 官方结果块前插入图片 URL 的 JSON 文本块；文本在前、媒体在后。 */
    private static ToolResultBlock withJsonPayload(List<String> urls, List<ContentBlock> output) {
        String payload = JsonUtils.getJsonCodec().toJson(Map.of("images", urls));
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(TextBlock.builder().text(payload).build());
        blocks.addAll(output);
        return ToolResultBlock.of(blocks);
    }
}
