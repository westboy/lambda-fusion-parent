package com.lambda.fusion.message.channel.bot;

import com.lambda.fusion.message.MessageConstants;
import com.lambda.fusion.message.MessageProperties;
import com.lambda.fusion.message.channel.MessageChannel;
import com.lambda.fusion.message.dispatch.model.MessageEnvelope;
import com.lambda.fusion.message.dispatch.model.entity.MessageDeliveryEntity;
import com.lambda.fusion.message.dispatch.service.MessageDeliveryService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 机器人 webhook 渠道：向配置的群机器人地址广播消息（钉钉/企业微信/飞书，单机器人 P3 简化）
 * <p>
 * 每个 webhook 一条投递记录；HTTP 200 且响应无错误码视为成功（各平台错误码字段经响应 JSON 判定）。
 * 仅在 lambda.fusion.message.bot.enabled=true 时装配。
 *
 * @author jin
 */
@Slf4j
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class BotMessageChannel implements MessageChannel {

    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final MessageProperties.Bot botProperties;
    private final MessageDeliveryService deliveryService;
    private final ObjectMapper objectMapper;

    public BotMessageChannel(
            MessageProperties.Bot botProperties, MessageDeliveryService deliveryService, ObjectMapper objectMapper) {
        this.botProperties = botProperties;
        this.deliveryService = deliveryService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String channel() {
        return MessageConstants.CHANNEL_BOT;
    }

    @Override
    public void send(MessageEnvelope envelope, Collection<String> receivers) {
        List<MessageDeliveryEntity> deliveries = new ArrayList<>();
        for (String webhook : botProperties.getWebhooks()) {
            deliveries.add(deliver(webhook, envelope));
        }
        deliveryService.recordAll(deliveries);
    }

    private MessageDeliveryEntity deliver(String webhook, MessageEnvelope envelope) {
        MessageDeliveryEntity delivery = new MessageDeliveryEntity();
        delivery.setMessageId(envelope.getMessageId());
        delivery.setTenantId(envelope.getTenantId());
        delivery.setChannel(MessageConstants.CHANNEL_BOT);
        delivery.setTarget(maskWebhook(webhook));
        delivery.setTemplateCode(envelope.getTemplateCode());
        delivery.setStatus(0);
        delivery.setRetryCount(0);

        try {
            String body = buildRequestBody(envelope);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhook))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return fail(delivery, "HTTP " + response.statusCode() + ": " + truncate(response.body()));
            }
            String error = resolvePlatformError(response.body());
            if (error != null) {
                return fail(delivery, error);
            }
            delivery.setStatus(1);
            delivery.setSentAt(LocalDateTime.now());
            return delivery;
        } catch (Exception e) {
            log.warn("机器人 webhook 投递失败: {}", e.getMessage());
            return fail(delivery, e.getMessage());
        }
    }

    /** 按机器人类型构造各平台消息体（协议互不兼容，不强行统一） */
    private String buildRequestBody(MessageEnvelope envelope) throws Exception {
        String title = envelope.getTitle() == null ? "" : envelope.getTitle();
        String content = envelope.getContent() == null ? "" : envelope.getContent();
        String type =
                botProperties.getType() == null ? "" : botProperties.getType().toUpperCase();
        return switch (type) {
            // 钉钉：markdown 消息
            case "DINGTALK" ->
                objectMapper.writeValueAsString(Map.of(
                        "msgtype",
                        "markdown",
                        "markdown",
                        Map.of("title", title, "text", "### " + title + "\n\n" + content)));
            // 企业微信：markdown 消息
            case "WECOM" ->
                objectMapper.writeValueAsString(
                        Map.of("msgtype", "markdown", "markdown", Map.of("content", "## " + title + "\n" + content)));
            // 飞书：text 消息
            case "FEISHU" ->
                objectMapper.writeValueAsString(
                        Map.of("msg_type", "text", "content", Map.of("text", title + "\n" + content)));
            default -> throw new IllegalArgumentException("不支持的机器人类型: " + botProperties.getType());
        };
    }

    /** HTTP 200 时按各平台响应 JSON 判定业务错误（钉钉/企微 errcode，飞书 code） */
    private String resolvePlatformError(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.hasNonNull("errcode") && root.get("errcode").asInt() != 0) {
                return "errcode=" + root.get("errcode").asInt() + ": "
                        + root.path("errmsg").asText("");
            }
            if (root.hasNonNull("code") && root.get("code").asInt() != 0) {
                return "code=" + root.get("code").asInt() + ": "
                        + root.path("msg").asText("");
            }
            return null;
        } catch (Exception e) {
            // 响应非 JSON 时不视为失败（个别平台可能返回纯文本）
            return null;
        }
    }

    /** webhook 地址含 token，投递记录中脱敏存储 */
    private String maskWebhook(String webhook) {
        int queryIndex = webhook.indexOf('?');
        return queryIndex > 0 ? webhook.substring(0, queryIndex) + "?***" : webhook;
    }

    private MessageDeliveryEntity fail(MessageDeliveryEntity entity, String error) {
        entity.setStatus(2);
        entity.setLastError(error != null && error.length() > 1000 ? error.substring(0, 1000) : error);
        return entity;
    }

    private String truncate(String message) {
        if (message == null || message.length() <= 1000) {
            return message;
        }
        return message.substring(0, 1000);
    }

    @Override
    public boolean retry(MessageDeliveryEntity delivery) {
        // 重试需要原始 webhook 明文，记录中已脱敏；P3 不支持机器人渠道重试
        delivery.setLastError("机器人渠道暂不支持重试（webhook 已脱敏）");
        return false;
    }
}
