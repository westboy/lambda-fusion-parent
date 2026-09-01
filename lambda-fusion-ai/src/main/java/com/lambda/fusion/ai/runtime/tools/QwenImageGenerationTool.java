package com.lambda.fusion.ai.runtime.tools;

import cn.hutool.core.util.StrUtil;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

/**
 * 独立的 Qwen Image HTTP 工具。它只依赖自身配置，不读取应用、技能、模型表或媒体能力绑定。
 *
 * @author Jin
 */
@Slf4j
public final class QwenImageGenerationTool {

    /** 独立工具名，避免与应用绑定的通用 {@code text_to_image} 工具冲突。 */
    public static final String TOOL_NAME = "qwen_text_to_image";

    static final String GENERATION_PATH = "/services/aigc/multimodal-generation/generation";

    private final String modelName;
    private final Function<String, Mono<String>> poster;

    QwenImageGenerationTool(String modelName, Function<String, Mono<String>> poster) {
        this.modelName = requiredModel(modelName);
        this.poster = poster;
    }

    /** 使用独立的 DashScope 凭据、地址、模型名和超时创建工具。 */
    public static QwenImageGenerationTool create(
            String apiKey, String baseUrl, String modelName, Duration connectTimeout, Duration requestTimeout) {
        String endpoint = generationEndpoint(baseUrl);
        Duration safeConnectTimeout = requirePositive(connectTimeout, "Qwen Image connectTimeout");
        Duration safeRequestTimeout = requirePositive(requestTimeout, "Qwen Image requestTimeout");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(safeConnectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new QwenImageGenerationTool(
                modelName, body -> post(client, endpoint, StringUtils.trimToEmpty(apiKey), safeRequestTimeout, body));
    }

    private static Mono<String> post(
            HttpClient client, String endpoint, String apiKey, Duration requestTimeout, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return Mono.fromFuture(client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)))
                .flatMap(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return Mono.error(new IOException(
                                "Qwen Image HTTP状态异常: " + response.statusCode() + " " + abbreviate(response.body())));
                    }
                    return Mono.just(response.body());
                });
    }

    /** 按 Qwen Image 参数契约生成图片。 */
    @Tool(name = TOOL_NAME, description = "Generate images with Qwen Image and return official image URL blocks.")
    public Mono<ToolResultBlock> generateImage(
            @ToolParam(name = "prompt", description = "The text prompt describing the image") String prompt,
            @ToolParam(name = "size", description = "Image size such as 1024*1024", required = false) String size,
            @ToolParam(name = "n", description = "Number of images to generate", required = false) Integer n,
            @ToolParam(name = "negative_prompt", description = "Elements to avoid", required = false)
                    String negativePrompt,
            @ToolParam(name = "prompt_extend", description = "Enable prompt enhancement", required = false)
                    Boolean promptExtend,
            @ToolParam(
                            name = "prompt_extend_mode",
                            description = "Prompt enhancement mode: direct or agent",
                            required = false)
                    String promptExtendMode,
            @ToolParam(name = "enable_thinking", description = "Enable image-generation thinking", required = false)
                    Boolean enableThinking,
            @ToolParam(name = "seed", description = "Random seed", required = false) Integer seed,
            @ToolParam(name = "watermark", description = "Include a watermark", required = false) Boolean watermark) {
        return Mono.fromCallable(() -> buildRequestBody(
                        prompt,
                        normalizeSize(size),
                        n,
                        negativePrompt,
                        promptExtend,
                        promptExtendMode,
                        enableThinking,
                        seed,
                        watermark))
                .flatMap(poster)
                .map(this::parseResult)
                .onErrorResume(error -> {
                    log.warn("Qwen文生图调用失败: {}", error.toString());
                    return Mono.just(ToolResultBlock.error("Qwen文生图失败: " + error.getMessage()));
                });
    }

    private String buildRequestBody(
            String prompt,
            String size,
            Integer n,
            String negativePrompt,
            Boolean promptExtend,
            String promptExtendMode,
            Boolean enableThinking,
            Integer seed,
            Boolean watermark) {
        Map<String, Object> input =
                Map.of("messages", List.of(Map.of("role", "user", "content", List.of(Map.of("text", prompt)))));
        Map<String, Object> parameters = new LinkedHashMap<>();
        putIfNotNull(parameters, "size", size);
        putIfNotNull(parameters, "n", n);
        putIfNotNull(parameters, "negative_prompt", StringUtils.trimToNull(negativePrompt));
        putIfNotNull(parameters, "prompt_extend", promptExtend);
        putIfNotNull(parameters, "prompt_extend_mode", StringUtils.trimToNull(promptExtendMode));
        putIfNotNull(parameters, "enable_thinking", enableThinking);
        putIfNotNull(parameters, "seed", seed);
        putIfNotNull(parameters, "watermark", watermark);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("input", input);
        if (!parameters.isEmpty()) {
            body.put("parameters", parameters);
        }
        return JsonUtils.getJsonCodec().toJson(body);
    }

    private ToolResultBlock parseResult(String response) {
        Map<String, Object> root = readJson(response);
        Map<String, Object> output = asMap(root.get("output"));
        List<Object> choices = output == null ? null : asList(output.get("choices"));
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("响应缺少 output.choices: " + abbreviate(response));
        }
        List<ContentBlock> images = new ArrayList<>();
        for (Object choiceObject : choices) {
            Map<String, Object> choice = asMap(choiceObject);
            Map<String, Object> message = choice == null ? null : asMap(choice.get("message"));
            List<Object> contents = message == null ? null : asList(message.get("content"));
            if (contents == null) {
                continue;
            }
            for (Object contentObject : contents) {
                Map<String, Object> content = asMap(contentObject);
                String imageUrl = content == null ? null : asText(content.get("image"));
                if (StringUtils.isNotBlank(imageUrl)) {
                    images.add(new ImageBlock(URLSource.builder().url(imageUrl).build()));
                }
            }
        }
        if (images.isEmpty()) {
            throw new IllegalStateException("响应未包含图片: " + abbreviate(response));
        }
        return ToolResultBlock.of(images);
    }

    static String generationEndpoint(String baseUrl) {
        String normalized = StrUtil.removeAllSuffix(StringUtils.trimToEmpty(baseUrl), "/");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Qwen Image baseUrl不能为空");
        }
        return normalized.endsWith(GENERATION_PATH) ? normalized : normalized + GENERATION_PATH;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "必须大于0");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(String response) {
        try {
            Map<String, Object> root = JsonUtils.getJsonCodec().fromJson(response, Map.class);
            if (root == null) {
                throw new IllegalStateException("响应体为空");
            }
            return root;
        } catch (RuntimeException error) {
            throw new IllegalStateException("响应不是合法 JSON: " + abbreviate(response), error);
        }
    }

    private static String normalizeSize(String size) {
        return StringUtils.isBlank(size) ? null : size.trim().replace('x', '*');
    }

    private static String requiredModel(String model) {
        String normalized = StringUtils.trimToNull(model);
        if (normalized == null) {
            throw new IllegalArgumentException("Qwen Image model不能为空");
        }
        return normalized;
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    private static String asText(Object value) {
        return value instanceof String text ? text : null;
    }

    private static String abbreviate(String response) {
        return StringUtils.abbreviate(StringUtils.defaultString(response), 400);
    }
}
