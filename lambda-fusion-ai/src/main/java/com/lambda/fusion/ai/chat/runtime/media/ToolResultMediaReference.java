package com.lambda.fusion.ai.chat.runtime.media;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.message.DataBlock;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/** 从实时媒体事件中提取平台附件引用；持久化恢复直接读取快照的显式附件 ID。 */
@UtilityClass
public class ToolResultMediaReference {

    public static Optional<String> attachmentId(AgentEvent event) {
        if (event instanceof ToolResultDataDeltaEvent dataEvent && dataEvent.getData() instanceof DataBlock data) {
            return attachmentId(data.getId());
        }
        return Optional.empty();
    }

    private static Optional<String> attachmentId(String dataBlockId) {
        if (dataBlockId == null || !dataBlockId.startsWith(ToolResultMediaMaterializer.ATTACHMENT_ID_PREFIX)) {
            return Optional.empty();
        }
        String id = dataBlockId.substring(ToolResultMediaMaterializer.ATTACHMENT_ID_PREFIX.length());
        return id.matches("[0-9]{1,32}") ? Optional.of(id) : Optional.empty();
    }
}
