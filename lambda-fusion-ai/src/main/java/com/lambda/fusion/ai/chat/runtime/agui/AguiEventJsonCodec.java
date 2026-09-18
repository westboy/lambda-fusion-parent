package com.lambda.fusion.ai.chat.runtime.agui;

import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.util.JsonUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** AG-UI 官方事件编码与最小业务元数据合并工具。 */
public final class AguiEventJsonCodec {

    private static final AguiEventEncoder ENCODER = new AguiEventEncoder();

    private AguiEventJsonCodec() {}

    /** 编码实时事件。进程内游标不会写入协议载荷。 */
    public static String encodeRunEvent(AguiEvent event, String chatRunId, String aguiRunId) {
        String json = ENCODER.encodeToJson(event);
        if (Objects.equals(aguiRunId, event.getRunId()) && json.endsWith("}")) {
            return json.substring(0, json.length() - 1) + ",\"chatRunId\":"
                    + JsonUtils.getJsonCodec().toJson(chatRunId) + "}";
        }
        return withRunMetadata(json, chatRunId, aguiRunId);
    }

    /** 为已经编码的事件补充业务 Run 与当前 AG-UI phase 标识；已有同名字段时覆盖，不产生重复键。 */
    public static String withRunMetadata(String json, String chatRunId, String aguiRunId) {
        return enrichJson(json, Map.of("runId", aguiRunId, "chatRunId", chatRunId));
    }

    /** 编码浏览器恢复引导事件；仅 RUN_STARTED 需要附带业务 phase。 */
    public static String encodeBootstrapEvent(AguiEvent event, String chatRunId, Integer phaseNo) {
        String encoded = encodeRunEvent(event, chatRunId, event.getRunId());
        if (phaseNo == null) {
            return encoded;
        }
        String trimmed = encoded.trim();
        if (trimmed.endsWith("}")) {
            return trimmed.substring(0, trimmed.length() - 1) + ",\"phaseNo\":" + phaseNo + "}";
        }
        return enrichJson(encoded, Map.of("phaseNo", phaseNo));
    }

    /** 为终态事件补充业务状态和结束原因。 */
    public static String withTerminalMetadata(String json, String status, String finishReason) {
        String trimmed = json.trim();
        if (trimmed.endsWith("}")) {
            return trimmed.substring(0, trimmed.length() - 1)
                    + ",\"chatRunStatus\":" + JsonUtils.getJsonCodec().toJson(status)
                    + ",\"finishReason\":" + JsonUtils.getJsonCodec().toJson(finishReason)
                    + "}";
        }
        return enrichJson(json, Map.of("chatRunStatus", status, "finishReason", finishReason));
    }

    @SuppressWarnings("unchecked")
    private static String enrichJson(String json, Map<String, ?> fields) {
        Map<String, Object> source = JsonUtils.getJsonCodec().fromJson(json.trim(), Map.class);
        Map<String, Object> enriched = new LinkedHashMap<>(source);
        enriched.putAll(fields);
        return JsonUtils.getJsonCodec().toJson(enriched);
    }
}
