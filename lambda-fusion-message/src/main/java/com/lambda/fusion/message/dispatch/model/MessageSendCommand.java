package com.lambda.fusion.message.dispatch.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
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
    @Schema(description = "标题（templateCode 存在时以模板渲染结果为准）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @Schema(description = "正文")
    private String content;

    @Schema(description = "正文类型：text/html/markdown", defaultValue = "text")
    private String contentType;

    @NotEmpty(message = "收件人不能为空")
    @Schema(description = "收件人用户名列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> receivers;

    @Schema(description = "投递渠道：inbox/email/sms，空则仅站内信", example = "[\"inbox\",\"sms\"]")
    private List<String> channels;

    @Size(max = 64, message = "模板编码长度不能超过64个字符")
    @Schema(description = "消息模板编码，存在时按模板渲染标题与正文")
    private String templateCode;

    @Schema(description = "模板渲染变量")
    private Map<String, Object> templateParams;

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
