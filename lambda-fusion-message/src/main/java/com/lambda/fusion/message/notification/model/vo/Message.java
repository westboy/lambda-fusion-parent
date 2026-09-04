package com.lambda.fusion.message.notification.model.vo;

import com.lambda.cloud.core.annotation.AutoConverter;
import com.lambda.cloud.core.shared.BaseVO;
import com.lambda.fusion.message.notification.model.entity.MessageEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 消息 VO（管理端）
 *
 * @author jin
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoConverter(target = MessageEntity.class, isReverse = true)
@Schema(description = "消息")
public class Message extends BaseVO<MessageEntity> {

    @Schema(description = "消息ID")
    private String id;

    @Schema(description = "租户ID")
    private String tenantId;

    @Schema(description = "消息分类：system/user/biz")
    private String category;

    @Schema(description = "级别：info/important/urgent")
    private String level;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "正文")
    private String content;

    @Schema(description = "正文类型：text/html/markdown")
    private String contentType;

    @Schema(description = "发送者")
    private String sender;

    @Schema(description = "来源业务类型")
    private String bizType;

    @Schema(description = "来源业务单据ID")
    private String bizId;

    @Schema(description = "备注")
    private String remarks;

    @Schema(description = "创建人")
    private String createdBy;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
