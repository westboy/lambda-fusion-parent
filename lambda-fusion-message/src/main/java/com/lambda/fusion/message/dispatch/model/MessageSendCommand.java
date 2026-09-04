package com.lambda.fusion.message.dispatch.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/**
 * 系统消息发送命令（管理端）
 *
 * @author jin
 */
@Data
@Schema(description = "系统消息发送参数")
public class MessageSendCommand {

    @Schema(description = "消息分类：system/user/biz", defaultValue = "system")
    private String category;

    @Schema(description = "级别：info/important/urgent", defaultValue = "info")
    private String level;

    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题长度不能超过255个字符")
    @Schema(description = "标题", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @Schema(description = "正文")
    private String content;

    @Schema(description = "正文类型：text/html/markdown", defaultValue = "text")
    private String contentType;

    @NotEmpty(message = "收件人不能为空")
    @Schema(description = "收件人用户名列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> receivers;

    @Size(max = 64, message = "来源业务类型长度不能超过64个字符")
    @Schema(description = "来源业务类型")
    private String bizType;

    @Size(max = 64, message = "来源业务单据ID长度不能超过64个字符")
    @Schema(description = "来源业务单据ID")
    private String bizId;

    @Size(max = 280, message = "备注长度不能超过280个字符")
    @Schema(description = "备注")
    private String remarks;
}
