package com.lambda.fusion.message.channel.inbox;

import com.lambda.cloud.sse.service.SseService;
import com.lambda.fusion.message.MessageConstants;
import java.util.Collection;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SSE 刷新信号通知器：向在线用户推送消息/公告的刷新信号（仅信号，正文不走路）
 * <p>
 * 仅在 lambda-cloud-starter-sse 存在时由 {@code MessageConfigure.SsePushConfigure} 装配；
 * 集群部署下定向推送经 starter-sse 的 TO_CLIENTS 路由、公告广播经 BROADCAST 路由。
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
     * 向在线收件人定向推送刷新信号；单个用户推送失败不影响其他用户
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

    /**
     * 全员广播刷新信号（公告发布/撤回；集群模式经 Redis 广播到各节点）
     *
     * @param eventName 事件名
     * @param payload   推送负载
     */
    public void broadcast(String eventName, Map<String, Object> payload) {
        SseService service = sseService.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            service.broadcast(eventName, payload);
        } catch (Exception e) {
            log.warn("SSE 广播刷新信号失败: {}", e.getMessage());
        }
    }
}
