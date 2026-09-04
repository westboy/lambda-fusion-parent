package com.lambda.fusion.message.template.model.vo;

import com.lambda.cloud.core.annotation.AutoConverter;
import com.lambda.cloud.core.shared.BaseVO;
import com.lambda.fusion.message.template.model.entity.MessageTemplateEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 消息模板 VO（管理端，同时作为创建/更新入参）
 *
 * @author jin
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoConverter(target = MessageTemplateEntity.class)
@Schema(description = "消息模板")
public class MessageTemplate extends BaseVO<MessageTemplateEntity> {

    @Schema(description = "模板ID")
    private String id;

    @Schema(description = "租户ID")
    private String tenantId;

    @NotBlank(message = "模板编码不能为空")
    @Size(max = 64, message = "模板编码长度不能超过64个字符")
    @Schema(description = "模板编码（业务幂等标识）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;

    @NotBlank(message = "模板名称不能为空")
    @Size(max = 64, message = "模板名称长度不能超过64个字符")
    @Schema(description = "模板名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Size(max = 255, message = "标题模板长度不能超过255个字符")
    @Schema(description = "标题模板，支持 ${var} 占位符")
    private String title;

    @Schema(description = "正文模板，支持 ${var} 占位符")
    private String content;

    @Schema(description = "正文类型：text/html/markdown", defaultValue = "text")
    private String contentType;

    @Schema(description = "渠道附加参数 JSON，如 {\"sms\":{\"templateId\":\"...\"}}")
    private String channelParams;

    @Schema(description = "状态：0停用，1启用", defaultValue = "1")
    private Integer status;

    @Size(max = 280, message = "备注长度不能超过280个字符")
    @Schema(description = "备注")
    private String remarks;

    @Schema(description = "创建人")
    private String createdBy;

    @Schema(description = "创建时间")
    private java.time.LocalDateTime createdAt;

    @Schema(description = "更新人")
    private String updatedBy;

    @Schema(description = "更新时间")
    private java.time.LocalDateTime updatedAt;
}
