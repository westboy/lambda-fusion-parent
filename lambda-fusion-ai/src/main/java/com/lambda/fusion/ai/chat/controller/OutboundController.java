package com.lambda.fusion.ai.chat.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.ai.chat.model.SendOutbound;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主动出站消息入口：向外部通道或会话最近入站通道推送 agent 消息。
 *
 * @author Jin
 */
@Tag(name = "AI 出站消息")
@RestController
@RequestMapping("/v1/ai/outbound")
@RequiredArgsConstructor
public class OutboundController {

    private final ObjectProvider<HarnessGateway> gatewayProvider;

    @SaCheckPermission(value = "ai:outbound:send")
    @OperationLog(module = "AI", value = "主动推送出站消息到外部通道")
    @Operation(summary = "主动推送出站消息到外部通道")
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestBody SendOutbound req) {
        if (req == null || req.getMessages() == null || req.getMessages().isEmpty()) {
            return bad("messages 不能为空");
        }
        HarnessGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            return bad("Gateway 未启用，无法投递出站消息");
        }
        List<Msg> msgs = req.getMessages().stream()
                .map(t -> Msg.builder().role(MsgRole.ASSISTANT).textContent(t).build())
                .toList();

        boolean hasSession = req.getSessionId() != null && !req.getSessionId().isBlank();
        boolean hasChannel = req.getChannelId() != null
                && !req.getChannelId().isBlank()
                && req.getTo() != null
                && !req.getTo().isBlank();
        if (hasSession == hasChannel) {
            return bad("需提供 sessionId 或 (channelId + to) 二选一");
        }

        if (hasSession) {
            boolean delivered = gateway.deliverToSession(req.getSessionId(), msgs);
            return ResponseEntity.ok(
                    Map.of("status", delivered ? "ok" : "skipped", "matchedBy", "session:" + req.getSessionId()));
        }
        ChannelManager cm = gateway.channelManager();
        if (cm == null) {
            return bad("ChannelManager 未配置");
        }
        cm.deliver(OutboundAddress.direct(req.getChannelId(), req.getTo()), msgs);
        return ResponseEntity.ok(
                Map.of("status", "ok", "matchedBy", "channel:" + req.getChannelId() + ":" + req.getTo()));
    }

    private ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("status", "error", "error", msg));
    }
}
