package com.lambda.fusion.ai.runtime.tools;

import com.lambda.fusion.ai.AiConstants.MediaToolName;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

/**
 * 自定义 HTTP 文生图薄适配器：直连各家「DashScope 多模态会话」兼容端点并返回官方图片块。
 *
 * <p>用于模型标记 {@code customMediaTool} 的厂商：用提供方 baseUrl + Bearer key 直连，请求体为
 * {@code {model, input:{messages:[{role:"user",content:[{text:prompt}]}]}, parameters:{size}}}，
 * 从 {@code output.choices[*].message.content[*].image} 提取图片 URL。尺寸分隔符用 {@code *}。
 * 与厂商无关：任何实现该契约的端点（如阿里云 MaaS 多模态生成）都复用本适配器，无需新增 Java 代码。
 *
 * @author Jin
 */
@Slf4j
public final class CustomHttpImageTool {

    /** 工具名。 */
    public static final String TOOL_NAME = MediaToolName.TEXT_TO_IMAGE;

    /** 文生图相对路径，拼在提供方 baseUrl 之后。 */
    static final String GENERATION_PATH = "/services/aigc/multimodal-generation/generation";

    private final Function<String, Mono<String>> poster;
    private final String endpoint;
    private final String modelName;

    /**
     * 包私有：供同包测试注入请求发送桩；对外请用 {@link #create} 工厂。
     *
     * @param poster 发送原始 JSON 请求体、返回原始 JSON 响应体的函数
     * @param endpoint 完整文生图地址
     * @param modelName 模型注册表绑定的生图模型名
     */
    CustomHttpImageTool(Function<String, Mono<String>> poster, String endpoint, String modelName) {
        this.poster = poster;
        this.endpoint = endpoint;
        this.modelName = modelName;
    }

    /**
     * 创建自定义 HTTP 文生图工具。
     *
     * @param apiKey 提供方 API Key（Bearer 注入请求头）
     * @param baseUrl 提供方服务地址，以 {@code /} 结尾与否均可
     * @param modelName 模型注册表绑定的生图模型名
     * @return 生图工具
     */
    public static CustomHttpImageTool create(String apiKey, String baseUrl, String modelName) {
        String endpoint = StringUtils.removeEnd(StringUtils.defaultString(baseUrl), "/") + GENERATION_PATH;
        return new CustomHttpImageTool(CustomHttpClient.poster(endpoint, apiKey), endpoint, modelName);
    }

    /**
     * 按文本提示生成图片。
     *
     * @param prompt 图片内容描述
     * @param size 图片尺寸（用 {@code 1024*1024}，{@code x} 分隔符会归一化为 {@code *}）
     * @param n 生成张数；端点单轮返回若干候选图，超出张数时截断
     * @return AgentScope 官方图片结果；失败时为 ERROR 状态结果
     */
    @Tool(
            name = TOOL_NAME,
            description = "Generate one or more images from a text prompt and return official image blocks.")
    public Mono<ToolResultBlock> generateImage(
            @ToolParam(name = "prompt", description = "The text prompt describing the image to generate") String prompt,
            @ToolParam(name = "size", description = "Image size, e.g. '1024*1024'", required = false) String size,
            @ToolParam(name = "n", description = "Number of images to generate", required = false) Integer n) {
        String normalizedSize = normalizeSize(size);
        return Mono.fromCallable(() -> buildRequestBody(normalizedSize, prompt))
                .flatMap(poster)
                .map(response -> parseResult(response, n))
                .onErrorResume(error -> {
                    log.warn("自定义文生图调用失败: {}", error.toString());
                    return Mono.just(ToolResultBlock.error("自定义文生图失败: " + error.getMessage()));
                });
    }

    private String buildRequestBody(String size, String prompt) {
        Map<String, Object> input =
                Map.of("messages", List.of(Map.of("role", "user", "content", List.of(Map.of("text", prompt)))));
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("model", modelName);
        body.put("input", input);
        if (StringUtils.isNotBlank(size)) {
            body.put("parameters", Map.of("size", size));
        }
        return JsonUtils.getJsonCodec().toJson(body);
    }

    /** 从响应提取图片 URL 列表并包装为官方图片块；无图片或结构异常抛错走 onErrorResume。 */
    private ToolResultBlock parseResult(String response, Integer n) {
        Map<String, Object> root = readJson(response);
        Map<String, Object> output = asMap(root.get("output"));
        List<Object> choices = output == null ? null : asList(output.get("choices"));
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("响应缺少 output.choices: " + abbreviate(response));
        }
        List<ImageBlock> images = new ArrayList<>();
        for (Object choiceObj : choices) {
            Map<String, Object> choice = asMap(choiceObj);
            Map<String, Object> message = choice == null ? null : asMap(choice.get("message"));
            List<Object> contents = message == null ? null : asList(message.get("content"));
            if (contents == null) {
                continue;
            }
            for (Object contentObj : contents) {
                Map<String, Object> content = asMap(contentObj);
                if (content == null) {
                    continue;
                }
                String imageUrl = asText(content.get("image"));
                if (StringUtils.isNotBlank(imageUrl)) {
                    images.add(new ImageBlock(URLSource.builder()
                            .url(imageUrl)
                            .mimeType("image/png")
                            .build()));
                }
            }
        }
        if (images.isEmpty()) {
            throw new IllegalStateException("响应未包含图片: " + abbreviate(response));
        }
        int limit = n != null && n > 0 ? Math.min(n, images.size()) : images.size();
        return ToolResultBlock.of(new ArrayList<>(images.subList(0, limit)));
    }

    private static Map<String, Object> readJson(String response) {
        try {
            Map<String, Object> root = JsonUtils.getJsonCodec().fromJson(response, Map.class);
            if (root == null) {
                throw new IllegalStateException("响应体为空");
            }
            return root;
        } catch (RuntimeException e) {
            throw new IllegalStateException("响应不是合法 JSON: " + abbreviate(response), e);
        }
    }

    /** 尺寸分隔符归一化：用 {@code *}，把 {@code x} 统一改写。 */
    private static String normalizeSize(String size) {
        if (StringUtils.isBlank(size)) {
            return null;
        }
        return size.trim().replace('x', '*');
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    private static String asText(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static String abbreviate(String response) {
        return StringUtils.abbreviate(StringUtils.defaultString(response), 400);
    }

    public String getEndpoint() {
        return endpoint;
    }
}
