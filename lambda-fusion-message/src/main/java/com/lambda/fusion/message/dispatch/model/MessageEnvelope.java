package com.lambda.fusion.message.dispatch.model;

import java.util.Collection;
import lombok.Builder;
import lombok.Getter;

/**
 * 消息信封：渠道投递的统一入参（内容视角），由发送编排构造、各渠道实现消费
 *
 * @author jin
 */
@Getter
@Builder
public class MessageEnvelope {

    /** 消息ID（la_messages 主键） */
    private final String messageId;

    /** 租户ID */
    private final String tenantId;

    /** 消息分类 */
    private final String category;

    /** 级别 */
    private final String level;

    /** 标题 */
    private final String title;

    /** 正文 */
    private final String content;

    /** 正文类型 */
    private final String contentType;

    /** 发送者 */
    private final String sender;

    /** 来源业务类型 */
    private final String bizType;

    /** 来源业务单据ID */
    private final String bizId;

    /** 收件人集合 */
    private final Collection<String> receivers;
}
