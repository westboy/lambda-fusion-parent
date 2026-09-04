package com.lambda.fusion.message.channel.inbox;

import com.lambda.cloud.sse.service.SseService;
import com.lambda.fusion.message.MessageConstants;
import java.util.Collection;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SSE 推送通知器：向在线用户推送消息刷新信号（仅刷新信号，正文不走路）
 * <p>
 * 仅在 lambda-cloud-starter-sse 存在时由 {@code MessageConfigure.SsePushConfigure} 装配；
 * 集群部署下定向推送可能覆盖不到连接在其他节点的用户，由客户端拉取补偿（见 docs/design/message-center.md §8）。
 *
 * @author jin
 */
@Slf4j
public class SseNotifier {

    private final ObjectProvider<SseService> sseService;

    public SseNotifier(ObjectProvider<SseService> sseService) {
        this.sseService = sseService;
    }

    /**
     * 向在线收件人推送刷新信号；单个用户推送失败不影响其他用户
     *
     * @param receivers 收件人集合
     * @param payload   推送负载（消息摘要字段）
     */
    public void notify(Collection<String> receivers, Map<String, Object> payload) {
        SseService service = sseService.getIfAvailable();
        if (service == null) {
            return;
        }
        for (String receiver : receivers) {
            try {
                service.sendEvent(receiver, MessageConstants.SSE_EVENT_NOTIFICATION, payload);
            } catch (Exception e) {
                log.warn("SSE 推送刷新信号失败，receiver={}: {}", receiver, e.getMessage());
            }
        }
    }
}
