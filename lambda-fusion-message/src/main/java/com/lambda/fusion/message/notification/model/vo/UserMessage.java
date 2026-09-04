package com.lambda.fusion.message.notification.model.vo;

import com.lambda.cloud.core.annotation.AutoConverter;
import com.lambda.cloud.core.shared.BaseVO;
import com.lambda.fusion.message.notification.model.entity.UserMessageEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户消息实例 VO（内部转换用）
 *
 * @author jin
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoConverter(target = UserMessageEntity.class)
@Schema(description = "用户消息实例")
public class UserMessage extends BaseVO<UserMessageEntity> {

    @Schema(description = "实例ID")
    private String id;

    @Schema(description = "消息ID")
    private String messageId;

    @Schema(description = "收件人")
    private String receiver;

    @Schema(description = "是否已读：0未读，1已读")
    private Integer isRead;

    @Schema(description = "首次已读时间")
    private LocalDateTime readTime;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
