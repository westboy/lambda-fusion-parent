package com.lambda.fusion.message.channel.inbox;

import com.lambda.fusion.message.MessageConstants;
import com.lambda.fusion.message.channel.MessageChannel;
import com.lambda.fusion.message.dispatch.model.MessageEnvelope;
import com.lambda.fusion.message.dispatch.model.entity.MessageDeliveryEntity;
import com.lambda.fusion.message.notification.service.UserMessageService;
import java.util.Collection;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 站内信渠道（内置）：写用户消息实例 + 向在线用户推送刷新信号
 * <p>
 * 站内投递事实即 la_user_messages 本身，不再另建投递记录；
 * SSE 通知器缺失（starter 不在 classpath 或推送开关关闭）时降级为纯落库。
 *
 * @author jin
 */
@Component
public class InboxMessageChannel implements MessageChannel {

    private final UserMessageService userMessageService;
    private final ObjectProvider<SseNotifier> sseNotifier;

    public InboxMessageChannel(UserMessageService userMessageService, ObjectProvider<SseNotifier> sseNotifier) {
        this.userMessageService = userMessageService;
        this.sseNotifier = sseNotifier;
    }

    @Override
    public String channel() {
        return MessageConstants.CHANNEL_INBOX;
    }

    @Override
    public void send(MessageEnvelope envelope, Collection<String> receivers) {
        userMessageService.deliver(envelope, receivers);
        SseNotifier notifier = sseNotifier.getIfAvailable();
        if (notifier != null) {
            Map<String, Object> payload = Map.of(
                    "messageId", envelope.getMessageId(),
                    "title", envelope.getTitle(),
                    "category", envelope.getCategory(),
                    "level", envelope.getLevel());
            notifier.notify(receivers, payload);
        }
    }

    @Override
    public boolean retry(MessageDeliveryEntity delivery) {
        // 站内渠道不产生投递记录，重试服务在入口处已排除本渠道；防御性返回失败
        return false;
    }
}
