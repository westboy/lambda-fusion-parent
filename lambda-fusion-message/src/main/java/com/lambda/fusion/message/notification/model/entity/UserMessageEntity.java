package com.lambda.fusion.message.notification.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lambda.fusion.core.entity.BaseEntity;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户消息实例实体（写扩散投递态，该用户已读/删除状态的单一事实来源）
 *
 * @author jin
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("la_user_messages")
@SuppressFBWarnings("EI_EXPOSE_REP")
public class UserMessageEntity extends BaseEntity {

    @TableId
    private String id;

    @TableField("MESSAGE_ID")
    private String messageId;

    @TableField("TENANT_ID")
    private String tenantId;

    @TableField("RECEIVER")
    private String receiver;

    /** 是否已读：0未读，1已读 */
    @TableField("IS_READ")
    private Integer isRead;

    @TableField("READ_TIME")
    private LocalDateTime readTime;

    /** 用户侧删除：0正常，1已删除 */
    @TableField("IS_DELETED")
    private Integer isDeleted;
}
