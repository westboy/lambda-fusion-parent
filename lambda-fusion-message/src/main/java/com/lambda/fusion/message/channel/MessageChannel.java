package com.lambda.fusion.message.channel;

import com.lambda.fusion.message.dispatch.model.MessageEnvelope;
import com.lambda.fusion.message.dispatch.model.entity.MessageDeliveryEntity;
import java.util.Collection;

/**
 * 消息渠道 SPI：一条消息、多个渠道
 * <p>
 * 实现负责把消息信封投递到本渠道的目标（站内落库、邮件、短信、机器人 webhook 等），
 * 并自行回报投递结果（外部渠道经 la_message_deliveries 记录）。
 *
 * @author jin
 */
public interface MessageChannel {

    /**
     * 渠道标识
     *
     * @return 渠道标识（如 inbox）
     */
    String channel();

    /**
     * 向指定收件人投递消息
     *
     * @param envelope  消息信封
     * @param receivers 收件人集合（已去重）
     */
    void send(MessageEnvelope envelope, Collection<String> receivers);

    /**
     * 重试一条失败的投递记录（仅外部渠道实现；站内渠道的投递事实即 la_user_messages，不支持重试）
     *
     * @param delivery 投递记录（status=失败）
     * @return 重试是否成功
     */
    boolean retry(MessageDeliveryEntity delivery);
}
