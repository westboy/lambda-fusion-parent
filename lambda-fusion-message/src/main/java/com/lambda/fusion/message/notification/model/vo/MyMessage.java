package com.lambda.fusion.message.notification.model.vo;

import com.lambda.cloud.core.annotation.AutoConverter;
import com.lambda.fusion.message.notification.model.entity.UserMessageEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 我的消息 VO（用户侧，la_user_messages 投递态经转换器映射，跨表字段由 assembler 组装）
 *
 * @author jin
 */
@Data
@AutoConverter(target = UserMessageEntity.class)
@Schema(description = "我的消息")
public class MyMessage {

    @Schema(description = "实例ID")
    private String id;

    @Schema(description = "消息ID")
    private String messageId;

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

    @Schema(description = "是否已读：0未读，1已读")
    private Integer isRead;

    @Schema(description = "首次已读时间")
    private LocalDateTime readTime;

    @Schema(description = "消息时间")
    private LocalDateTime createdAt;
}
