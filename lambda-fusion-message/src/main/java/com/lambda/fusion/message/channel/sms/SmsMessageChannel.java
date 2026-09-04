package com.lambda.fusion.message.channel.sms;

import com.lambda.cloud.sms.SmsISP;
import com.lambda.cloud.sms.SmsMessageSender;
import com.lambda.cloud.sms.model.SmsSendResult;
import com.lambda.fusion.message.MessageConstants;
import com.lambda.fusion.message.MessageProperties;
import com.lambda.fusion.message.channel.MessageChannel;
import com.lambda.fusion.message.dispatch.model.MessageEnvelope;
import com.lambda.fusion.message.dispatch.model.entity.MessageDeliveryEntity;
import com.lambda.fusion.message.dispatch.resolver.MessageContactResolver;
import com.lambda.fusion.message.dispatch.service.MessageDeliveryService;
import com.lambda.fusion.message.template.model.entity.MessageTemplateEntity;
import com.lambda.fusion.message.template.service.MessageTemplateService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 短信渠道：复用 lambda-cloud-starter-sms（阿里云/腾讯云/Mock），按收件人写 la_message_deliveries 投递记录
 * <p>
 * 短信必须使用运营商报备模板：模板 CHANNEL_PARAMS 中取 sms.templateId，正文渲染变量作为模板参数下发。
 * 仅在 starter-sms 存在且 lambda.fusion.message.sms.enabled=true 时装配；
 * 运营商未配置时记录投递失败，不阻断其他渠道。
 *
 * @author jin
 */
@Slf4j
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class SmsMessageChannel implements MessageChannel {

    private final ObjectProvider<SmsMessageSender> senders;
    private final List<MessageContactResolver> contactResolvers;
    private final MessageDeliveryService deliveryService;
    private final MessageTemplateService templateService;
    private final MessageProperties properties;
    private final ObjectMapper objectMapper;

    public SmsMessageChannel(
            ObjectProvider<SmsMessageSender> senders,
            List<MessageContactResolver> contactResolvers,
            MessageDeliveryService deliveryService,
            MessageTemplateService templateService,
            MessageProperties properties,
            ObjectMapper objectMapper) {
        this.senders = senders;
        this.contactResolvers = contactResolvers;
        this.deliveryService = deliveryService;
        this.templateService = templateService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String channel() {
        return MessageConstants.CHANNEL_SMS;
    }

    @Override
    public void send(MessageEnvelope envelope, Collection<String> receivers) {
        SmsMessageSender sender = pickSender();
        // 短信运营商模板 ID：来自模板 CHANNEL_PARAMS 的 sms.templateId
        String smsTemplateId = resolveSmsTemplateId(envelope);
        String paramsJson = toJson(envelope.getTemplateParams());
        List<MessageDeliveryEntity> deliveries = new ArrayList<>(receivers.size());
        for (String receiver : receivers) {
            deliveries.add(deliver(receiver, envelope, sender, smsTemplateId, paramsJson));
        }
        deliveryService.recordAll(deliveries);
    }

    /**
     * 按配置挑选短信发送器：指定 isp 时按 isp() 匹配（兼容多运营商同时启用），否则取首个
     */
    private SmsMessageSender pickSender() {
        List<SmsMessageSender> available = senders.stream().toList();
        if (available.isEmpty()) {
            return null;
        }
        String isp = properties.getSms().getIsp();
        if (isp == null || isp.isBlank()) {
            return available.getFirst();
        }
        SmsISP target = SmsISP.deserialize(isp);
        return available.stream()
                .filter(sender -> sender.isp() == target)
                .findFirst()
                .orElse(null);
    }

    private String resolveSmsTemplateId(MessageEnvelope envelope) {
        String code = envelope.getTemplateCode();
        if (code == null || code.isBlank()) {
            return null;
        }
        MessageTemplateEntity template = templateService.getEnabledByCode(code);
        if (template == null || template.getChannelParams() == null) {
            return null;
        }
        try {
            Map<String, Map<String, Object>> channelParams =
                    objectMapper.readValue(template.getChannelParams(), new TypeReference<>() {});
            Object templateId = channelParams.get("sms") == null
                    ? null
                    : channelParams.get("sms").get("templateId");
            return templateId == null ? null : String.valueOf(templateId);
        } catch (Exception e) {
            log.warn("模板 CHANNEL_PARAMS 解析失败，code={}: {}", code, e.getMessage());
            return null;
        }
    }

    private MessageDeliveryEntity deliver(
            String receiver,
            MessageEnvelope envelope,
            SmsMessageSender sender,
            String smsTemplateId,
            String paramsJson) {
        MessageDeliveryEntity delivery = new MessageDeliveryEntity();
        delivery.setMessageId(envelope.getMessageId());
        delivery.setTenantId(envelope.getTenantId());
        delivery.setChannel(MessageConstants.CHANNEL_SMS);
        delivery.setTemplateCode(envelope.getTemplateCode());
        delivery.setTemplateParams(paramsJson);
        delivery.setStatus(0);
        delivery.setRetryCount(0);

        String mobile = resolveContact(receiver);
        if (mobile == null || mobile.isBlank()) {
            return fail(delivery, "无法解析收件人手机号（联系方式解析器不可用或用户未配置手机号）");
        }
        if (sender == null) {
            return fail(delivery, "短信发送器未装配（请检查 lambda.sms.* 配置）");
        }
        if (smsTemplateId == null || smsTemplateId.isBlank()) {
            return fail(delivery, "短信运营商模板ID缺失（模板 CHANNEL_PARAMS 未配置 sms.templateId）");
        }
        try {
            SmsSendResult result = sender.sendMessage(mobile, smsTemplateId, paramsJson);
            delivery.setTarget(mobile);
            delivery.setStatus(result.isSuccess() ? 1 : 2);
            delivery.setSentAt(java.time.LocalDateTime.now());
            if (!result.isSuccess()) {
                delivery.setLastError(truncate(result.getMessage()));
            }
            return delivery;
        } catch (Exception e) {
            log.warn("短信投递异常，receiver={} mobile={}: {}", receiver, mobile, e.getMessage());
            return fail(delivery, e.getMessage());
        }
    }

    private String resolveContact(String username) {
        return contactResolvers.stream()
                .filter(resolver -> "mobile".equals(resolver.type()))
                .findFirst()
                .map(resolver -> resolver.resolve(username))
                .orElse(null);
    }

    private MessageDeliveryEntity fail(MessageDeliveryEntity entity, String error) {
        entity.setStatus(2);
        entity.setLastError(error != null && error.length() > 1000 ? error.substring(0, 1000) : error);
        return entity;
    }

    private String toJson(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            log.warn("模板参数序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private String truncate(String message) {
        if (message == null || message.length() <= 1000) {
            return message;
        }
        return message.substring(0, 1000);
    }
}
