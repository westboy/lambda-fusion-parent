package com.lambda.fusion.message.channel.email;

import com.lambda.fusion.message.MessageConstants;
import com.lambda.fusion.message.MessageProperties;
import com.lambda.fusion.message.channel.MessageChannel;
import com.lambda.fusion.message.dispatch.model.MessageEnvelope;
import com.lambda.fusion.message.dispatch.model.entity.MessageDeliveryEntity;
import com.lambda.fusion.message.dispatch.resolver.MessageContactResolver;
import com.lambda.fusion.message.dispatch.service.MessageDeliveryService;
import com.lambda.fusion.message.notification.mapper.MessageMapper;
import com.lambda.fusion.message.notification.model.entity.MessageEntity;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * 邮件渠道：正文按信封已渲染内容发送，按收件人写 la_message_deliveries 投递记录
 * <p>
 * 仅在 spring-boot-starter-mail 存在且 lambda.fusion.message.email.enabled=true 时装配；
 * JavaMailSender Bean 缺失（未配置 spring.mail.*）或联系方式解析失败时记录投递失败，不阻断其他收件人。
 *
 * @author jin
 */
@Slf4j
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class EmailMessageChannel implements MessageChannel {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final List<MessageContactResolver> contactResolvers;
    private final MessageDeliveryService deliveryService;
    private final MessageProperties properties;
    private final MessageMapper messageMapper;

    public EmailMessageChannel(
            ObjectProvider<JavaMailSender> mailSender,
            List<MessageContactResolver> contactResolvers,
            MessageDeliveryService deliveryService,
            MessageProperties properties,
            MessageMapper messageMapper) {
        this.mailSender = mailSender;
        this.contactResolvers = contactResolvers;
        this.deliveryService = deliveryService;
        this.properties = properties;
        this.messageMapper = messageMapper;
    }

    @Override
    public String channel() {
        return MessageConstants.CHANNEL_EMAIL;
    }

    @Override
    public void send(MessageEnvelope envelope, Collection<String> receivers) {
        JavaMailSender sender = mailSender.getIfAvailable();
        List<MessageDeliveryEntity> deliveries = new ArrayList<>(receivers.size());
        for (String receiver : receivers) {
            deliveries.add(deliver(receiver, envelope, sender));
        }
        deliveryService.recordAll(deliveries);
    }

    private MessageDeliveryEntity deliver(String receiver, MessageEnvelope envelope, JavaMailSender sender) {
        MessageDeliveryEntity delivery = baseDelivery(receiver, envelope);
        String email = resolveContact(receiver);
        if (email == null || email.isBlank()) {
            return fail(delivery, "无法解析收件人邮箱（联系方式解析器不可用或用户未配置邮箱）");
        }
        if (sender == null) {
            return fail(delivery, "邮件发送器未装配（请检查 spring.mail.* 配置）");
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            String from = properties.getEmail().getFrom();
            if (from != null && !from.isBlank()) {
                helper.setFrom(from);
            }
            helper.setTo(email);
            helper.setSubject(envelope.getTitle() == null ? "" : envelope.getTitle());
            boolean html = MessageConstants.CONTENT_TYPE_HTML.equals(envelope.getContentType());
            helper.setText(envelope.getContent() == null ? "" : envelope.getContent(), html);
            sender.send(message);
            return success(delivery, email);
        } catch (MessagingException e) {
            log.warn("邮件投递失败，receiver={} email={}: {}", receiver, email, e.getMessage());
            return fail(delivery, e.getMessage());
        }
    }

    private String resolveContact(String username) {
        return contactResolvers.stream()
                .filter(resolver -> "email".equals(resolver.type()))
                .findFirst()
                .map(resolver -> resolver.resolve(username))
                .orElse(null);
    }

    private MessageDeliveryEntity baseDelivery(String receiver, MessageEnvelope envelope) {
        MessageDeliveryEntity entity = new MessageDeliveryEntity();
        entity.setMessageId(envelope.getMessageId());
        entity.setTenantId(envelope.getTenantId());
        entity.setChannel(MessageConstants.CHANNEL_EMAIL);
        entity.setTemplateCode(envelope.getTemplateCode());
        entity.setStatus(0);
        entity.setRetryCount(0);
        return entity;
    }

    private MessageDeliveryEntity success(MessageDeliveryEntity entity, String target) {
        entity.setTarget(target);
        entity.setStatus(1);
        entity.setSentAt(java.time.LocalDateTime.now());
        return entity;
    }

    private MessageDeliveryEntity fail(MessageDeliveryEntity entity, String error) {
        entity.setStatus(2);
        entity.setLastError(error != null && error.length() > 1000 ? error.substring(0, 1000) : error);
        return entity;
    }

    @Override
    public boolean retry(MessageDeliveryEntity delivery) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            delivery.setLastError("邮件发送器未装配（请检查 spring.mail.* 配置）");
            return false;
        }
        // 正文取自消息主表（发送时已渲染）；目标直接用记录中的邮箱，不重新解析
        MessageEntity message =
                delivery.getMessageId() == null ? null : messageMapper.selectById(delivery.getMessageId());
        if (message == null) {
            delivery.setLastError("关联消息不存在，无法重试");
            return false;
        }
        try {
            MimeMessage mimeMessage = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, "UTF-8");
            String from = properties.getEmail().getFrom();
            if (from != null && !from.isBlank()) {
                helper.setFrom(from);
            }
            helper.setTo(delivery.getTarget());
            helper.setSubject(message.getTitle() == null ? "" : message.getTitle());
            boolean html = MessageConstants.CONTENT_TYPE_HTML.equals(message.getContentType());
            helper.setText(message.getContent() == null ? "" : message.getContent(), html);
            sender.send(mimeMessage);
            return true;
        } catch (MessagingException e) {
            log.warn("邮件重试失败，target={}: {}", delivery.getTarget(), e.getMessage());
            delivery.setLastError(
                    e.getMessage() != null && e.getMessage().length() > 1000
                            ? e.getMessage().substring(0, 1000)
                            : e.getMessage());
            return false;
        }
    }
}
