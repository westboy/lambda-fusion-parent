package com.lambda.fusion.ai.runtime.gateway;

import com.lambda.fusion.ai.chat.model.SendOutbound;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 主动出站消息投递服务：向外部通道或会话最近入站通道推送 Agent 消息。
 * 防腐边界：AgentScope/Harness 类型（Msg、HarnessGateway、OutboundAddress）不溢出本包。
 *
 * @author Jin
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboundMessageService {

    private final ObjectProvider<HarnessGateway> gatewayProvider;

    /**
     * 投递出站消息。
     *
     * @param req 出站请求（{@code sessionId} 或 {@code channelId + to} 二选一）
     * @return 投递结果；{@code error} 非空表示参数非法或网关不可用
     */
    public OutboundDelivery send(SendOutbound req) {
        if (req == null || req.getMessages() == null || req.getMessages().isEmpty()) {
            return OutboundDelivery.error("messages 不能为空");
        }
        HarnessGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            return OutboundDelivery.error("Gateway 未启用，无法投递出站消息");
        }
        List<Msg> msgs = req.getMessages().stream()
                .map(text ->
                        Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build())
                .toList();

        boolean hasSession = req.getSessionId() != null && !req.getSessionId().isBlank();
        boolean hasChannel = req.getChannelId() != null
                && !req.getChannelId().isBlank()
                && req.getTo() != null
                && !req.getTo().isBlank();
        if (hasSession == hasChannel) {
            return OutboundDelivery.error("需提供 sessionId 或 (channelId + to) 二选一");
        }

        if (hasSession) {
            boolean delivered = gateway.deliverToSession(req.getSessionId(), msgs);
            return new OutboundDelivery(delivered ? "ok" : "skipped", "session:" + req.getSessionId(), null);
        }
        ChannelManager channelManager = gateway.channelManager();
        if (channelManager == null) {
            return OutboundDelivery.error("ChannelManager 未配置");
        }
        channelManager.deliver(OutboundAddress.direct(req.getChannelId(), req.getTo()), msgs);
        return new OutboundDelivery("ok", "channel:" + req.getChannelId() + ":" + req.getTo(), null);
    }

    /** 出站投递结果。 */
    public record OutboundDelivery(String status, String matchedBy, String error) {

        static OutboundDelivery error(String message) {
            return new OutboundDelivery("error", null, message);
        }
    }
}
