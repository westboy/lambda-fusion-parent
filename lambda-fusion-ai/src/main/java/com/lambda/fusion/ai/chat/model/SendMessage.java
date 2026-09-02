package com.lambda.fusion.ai.chat.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

@Data
@Schema(description = "发送消息")
public class SendMessage {

    @Schema(description = "客户端请求幂等键(UUID)")
    @NotBlank(message = "clientRequestId不能为空")
    @Size(max = 64, message = "clientRequestId长度不能超过64")
    private String clientRequestId;

    @Schema(description = "消息内容(纯附件消息可为空)")
    private String content;

    @Schema(description = "附件ID列表(先经 /v1/ai/chat/attachments 上传)")
    @Size(max = 10, message = "附件数量超限")
    private List<@NotBlank(message = "附件ID不能为空") String> attachmentIds;

    @AssertTrue(message = "消息内容与附件不能同时为空")
    @Schema(hidden = true)
    public boolean isContentOrAttachmentPresent() {
        return StringUtils.isNotBlank(content) || (attachmentIds != null && !attachmentIds.isEmpty());
    }
}
