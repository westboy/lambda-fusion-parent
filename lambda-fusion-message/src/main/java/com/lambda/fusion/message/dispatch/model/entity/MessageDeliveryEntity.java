package com.lambda.fusion.message.dispatch.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lambda.fusion.core.entity.BaseEntity;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 外部渠道投递记录实体：每个收件人 × 每个外部渠道一条（站内渠道的投递事实即 la_user_messages，不记本表）
 *
 * @author jin
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("la_message_deliveries")
@SuppressFBWarnings("EI_EXPOSE_REP")
public class MessageDeliveryEntity extends BaseEntity {

    @TableId
    private String id;

    @TableField("MESSAGE_ID")
    private String messageId;

    @TableField("TENANT_ID")
    private String tenantId;

    /** 渠道：email/sms */
    @TableField("CHANNEL")
    private String channel;

    /** 投递目标：邮箱/手机号 */
    @TableField("TARGET")
    private String target;

    @TableField("TEMPLATE_CODE")
    private String templateCode;

    /** 模板参数 JSON */
    @TableField("TEMPLATE_PARAMS")
    private String templateParams;

    /** 投递状态：0待发送，1成功，2失败 */
    @TableField("STATUS")
    private Integer status;

    @TableField("RETRY_COUNT")
    private Integer retryCount;

    /** 最近一次失败原因 */
    @TableField("LAST_ERROR")
    private String lastError;

    @TableField("SENT_AT")
    private LocalDateTime sentAt;
}
