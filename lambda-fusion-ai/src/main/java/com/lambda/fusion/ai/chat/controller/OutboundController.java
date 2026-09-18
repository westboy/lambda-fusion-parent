package com.lambda.fusion.ai.chat.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.ai.chat.model.SendOutbound;
import com.lambda.fusion.ai.runtime.gateway.OutboundMessageService;
import com.lambda.fusion.ai.runtime.gateway.OutboundMessageService.OutboundDelivery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主动出站消息入口：向外部通道或会话最近入站通道推送 agent 消息。
 * 只编排服务调用；AgentScope/Harness 类型由 {@link OutboundMessageService} 收口。
 *
 * @author Jin
 */
@Tag(name = "AI 出站消息")
@RestController
@RequestMapping("/v1/ai/outbound")
@RequiredArgsConstructor
public class OutboundController {

    private final OutboundMessageService outboundMessageService;

    @SaCheckPermission(value = "ai:outbound:send")
    @OperationLog(module = "AI", value = "主动推送出站消息到外部通道")
    @Operation(summary = "主动推送出站消息到外部通道")
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestBody SendOutbound req) {
        OutboundDelivery result = outboundMessageService.send(req);
        if (result.error() != null) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "error", result.error()));
        }
        return ResponseEntity.ok(Map.of("status", result.status(), "matchedBy", result.matchedBy()));
    }
}
