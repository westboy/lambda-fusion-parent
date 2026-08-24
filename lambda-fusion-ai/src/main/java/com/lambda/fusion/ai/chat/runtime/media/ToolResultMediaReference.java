package com.lambda.fusion.ai.chat.runtime.media;

import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.experimental.UtilityClass;

/** 从实时媒体事件或持久化工具结果信封中提取平台附件引用。 */
@UtilityClass
public class ToolResultMediaReference {

    private static final String ENVELOPE_SCHEMA = "lambda-fusion/tool-result";

    public static Optional<String> attachmentId(AgentEvent event) {
        if (event instanceof ToolResultDataDeltaEvent dataEvent && dataEvent.getData() instanceof DataBlock data) {
            return attachmentId(data.getId());
        }
        return Optional.empty();
    }

    public static List<String> attachmentIds(ChatRunSnapshot snapshot) {
        Set<String> ids = new LinkedHashSet<>();
        for (ChatRunSnapshot.ToolCall tool : snapshot.tools()) {
            extractEnvelope(tool.result(), ids);
        }
        return List.copyOf(ids);
    }

    private static void extractEnvelope(String result, Set<String> ids) {
        if (result == null || !result.startsWith("{")) {
            return;
        }
        try {
            Map<?, ?> envelope = JsonUtils.getJsonCodec().fromJson(result, Map.class);
            if (!ENVELOPE_SCHEMA.equals(envelope.get("schema"))
                    || !(envelope.get("content") instanceof List<?> content)) {
                return;
            }
            for (Object item : content) {
                if (item instanceof Map<?, ?> block && "data".equals(block.get("type"))) {
                    attachmentId(String.valueOf(block.get("id"))).ifPresent(ids::add);
                }
            }
        } catch (RuntimeException ignored) {
            // 旧版纯文本或非本信封结果不参与附件生命周期。
        }
    }

    private static Optional<String> attachmentId(String dataBlockId) {
        if (dataBlockId == null || !dataBlockId.startsWith(ToolResultMediaMaterializer.ATTACHMENT_ID_PREFIX)) {
            return Optional.empty();
        }
        String id = dataBlockId.substring(ToolResultMediaMaterializer.ATTACHMENT_ID_PREFIX.length());
        return id.matches("[0-9]{1,32}") ? Optional.of(id) : Optional.empty();
    }
}
