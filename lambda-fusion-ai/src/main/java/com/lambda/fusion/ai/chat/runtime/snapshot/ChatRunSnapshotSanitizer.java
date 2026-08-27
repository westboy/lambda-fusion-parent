package com.lambda.fusion.ai.chat.runtime.snapshot;

import com.lambda.fusion.ai.AiConstants.ChatRunToolStatus;
import io.agentscope.core.util.JsonUtils;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

/**
 * 执行快照数据清理工具：负责工具调用数据归一化，并在持久化前屏蔽常见凭据字段。
 *
 * @author Jin
 */
@UtilityClass
public class ChatRunSnapshotSanitizer {

    static final int MAX_TOOL_CONTENT_CHARS = 2048;

    private static final String TRUNCATION_MARKER = "\n…[快照内容已截断，完整内容未持久化]…\n";

    /**
     * 清理工具调用快照。
     *
     * @param tools 工具调用快照
     * @return 清理后的工具调用快照
     */
    public static List<ChatRunSnapshot.ToolCall> sanitizeTools(List<ChatRunSnapshot.ToolCall> tools) {
        return tools == null
                ? List.of()
                : tools.stream().map(ChatRunSnapshotSanitizer::sanitizeTool).toList();
    }

    /**
     * 清理待确认工具调用快照。
     *
     * @param tools 待确认工具调用
     * @return 不包含参数和结果的待确认工具调用快照
     */
    public static List<ChatRunSnapshot.ToolCall> sanitizePendingTools(List<ChatRunSnapshot.ToolCall> tools) {
        return tools == null
                ? List.of()
                : tools.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(tool -> new ChatRunSnapshot.ToolCall(
                                safe(tool.toolCallId()),
                                safe(tool.toolCallName()),
                                "",
                                "",
                                ChatRunToolStatus.ASKING.getCode()))
                        .toList();
    }

    /**
     * 清理待用户输入的挂起工具调用快照。
     *
     * @param inputs 待用户输入的挂起工具调用
     * @return 规整空值后的待输入投影
     */
    public static List<ChatRunSnapshot.PendingInput> sanitizePendingInputs(List<ChatRunSnapshot.PendingInput> inputs) {
        return inputs == null
                ? List.of()
                : inputs.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(input -> new ChatRunSnapshot.PendingInput(
                                safe(input.toolCallId()),
                                safe(input.toolCallName()),
                                safe(input.question()),
                                safe(input.inputKind()),
                                safe(input.responseSchemaJson())))
                        .toList();
    }

    /** 规整生成附件 ID：只保留平台数字 ID，保持首次出现顺序并去重。 */
    public static List<String> sanitizeAttachmentIds(List<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return List.of();
        }
        var sanitized = new LinkedHashSet<String>();
        attachmentIds.stream()
                .filter(java.util.Objects::nonNull)
                .filter(id -> id.matches("[0-9]{1,32}"))
                .forEach(sanitized::add);
        return List.copyOf(sanitized);
    }

    /**
     * 屏蔽文本中的常见凭据信息。
     *
     * @param value 原始文本
     * @return 清理后的文本
     */
    public static String redactText(String value) {
        return safe(value)
                .replaceAll("(?i)(bearer\\s+)[a-z0-9._~+/=-]+", "$1***")
                .replaceAll(
                        "(?i)(password|secret|token|api[_-]?key|authorization|credential)(\\s*[:=]\\s*)[^,;\\s]+",
                        "$1$2***");
    }

    /**
     * 生成可持久化的错误信息：空消息回退为异常类名，清理常见凭据并限制长度。
     *
     * @param error 异常
     * @return 已清理并限制长度的错误信息
     */
    public static String safeMessage(Throwable error) {
        String message =
                StringUtils.defaultIfBlank(error.getMessage(), error.getClass().getSimpleName());
        return StringUtils.left(redactText(message), 1000);
    }

    private static ChatRunSnapshot.ToolCall sanitizeTool(ChatRunSnapshot.ToolCall tool) {
        if (tool == null) {
            return new ChatRunSnapshot.ToolCall("", "", "", "", ChatRunToolStatus.UNKNOWN.getCode());
        }
        return new ChatRunSnapshot.ToolCall(
                safe(tool.toolCallId()),
                safe(tool.toolCallName()),
                toPreview(sanitizeJson(tool.args())),
                toPreview(sanitizeJson(tool.result())),
                safe(tool.status()));
    }

    private static String toPreview(String value) {
        if (value.length() <= MAX_TOOL_CONTENT_CHARS) {
            return value;
        }
        int remaining = MAX_TOOL_CONTENT_CHARS - TRUNCATION_MARKER.length();
        int headLength = remaining * 2 / 3;
        int tailLength = remaining - headLength;
        return value.substring(0, headLength) + TRUNCATION_MARKER + value.substring(value.length() - tailLength);
    }

    private static String sanitizeJson(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            Object decoded = JsonUtils.getJsonCodec().fromJson(value, Object.class);
            return JsonUtils.getJsonCodec().toJson(redact(decoded));
        } catch (RuntimeException notJson) {
            return redactText(value);
        }
    }

    private static Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                String name = String.valueOf(key);
                result.put(name, isSecret(name) ? "***" : redact(nested));
            });
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ChatRunSnapshotSanitizer::redact).toList();
        }
        return value;
    }

    private static boolean isSecret(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("apikey")
                || normalized.contains("authorization")
                || normalized.contains("credential");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
