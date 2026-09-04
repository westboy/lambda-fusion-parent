package com.lambda.fusion.message.dispatch.service.impl;

import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.core.service.AbstractCrudService;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.message.MessageConstants;
import com.lambda.fusion.message.channel.MessageChannelRegistry;
import com.lambda.fusion.message.dispatch.model.MessageEnvelope;
import com.lambda.fusion.message.dispatch.model.MessageSendCommand;
import com.lambda.fusion.message.dispatch.service.MessageDispatchService;
import com.lambda.fusion.message.exception.MessageBusinessException;
import com.lambda.fusion.message.notification.mapper.MessageMapper;
import com.lambda.fusion.message.notification.model.entity.MessageEntity;
import com.lambda.fusion.message.notification.model.query.MessageQuery;
import com.lambda.fusion.message.notification.model.vo.Message;
import com.lambda.fusion.message.notification.service.MessageService;
import com.lambda.fusion.message.template.model.entity.MessageTemplateEntity;
import com.lambda.fusion.message.template.service.MessageTemplateService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息主表服务 + 发送编排实现：继承单表 CRUD 基类（save/toVO/pageView），
 * 同时承载管理端分页与发送编排（渲染 → 多渠道路由投递）
 *
 * @author jin
 */
@Slf4j
@SuppressFBWarnings("EI_EXPOSE_REP2")
@Service
public class MessageDispatchServiceImpl extends AbstractCrudService<MessageEntity, Message, MessageMapper>
        implements MessageDispatchService, MessageService {

    private final MessageChannelRegistry channelRegistry;
    private final MessageTemplateService templateService;

    public MessageDispatchServiceImpl(MessageChannelRegistry channelRegistry, MessageTemplateService templateService) {
        this.channelRegistry = channelRegistry;
        this.templateService = templateService;
    }

    @Override
    public PageView<Message> page(MessageQuery query) {
        return pageView(query.getPage(), query.getLambdaQueryWrapper());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Message send(MessageSendCommand command) {
        LinkedHashSet<String> receivers = new LinkedHashSet<>(command.getReceivers());
        if (receivers.isEmpty()) {
            throw MessageBusinessException.recipientsEmpty();
        }

        // 模板渲染优先：指定 templateCode 时标题与正文以模板渲染结果为准
        String title = command.getTitle();
        String content = command.getContent();
        String templateCode = command.getTemplateCode();
        Map<String, Object> templateParams = command.getTemplateParams();
        if (StringUtils.isNotBlank(templateCode)) {
            MessageTemplateEntity template = templateService.getEnabledByCode(templateCode);
            if (template == null) {
                throw MessageBusinessException.templateNotFound(templateCode);
            }
            title = templateService.render(template.getTitle(), templateParams);
            content = templateService.render(template.getContent(), templateParams);
        }

        MessageEntity entity = new MessageEntity();
        entity.setCategory(defaultIfBlank(command.getCategory(), MessageConstants.CATEGORY_SYSTEM));
        entity.setLevel(defaultIfBlank(command.getLevel(), MessageConstants.LEVEL_INFO));
        entity.setContentType(defaultIfBlank(command.getContentType(), MessageConstants.CONTENT_TYPE_TEXT));
        entity.setTitle(title);
        entity.setContent(content);
        entity.setSender(AuthUtils.getUsername());
        entity.setBizType(command.getBizType());
        entity.setBizId(command.getBizId());
        entity.setRemarks(command.getRemarks());
        save(entity);

        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(entity.getId())
                .tenantId(entity.getTenantId())
                .category(entity.getCategory())
                .level(entity.getLevel())
                .title(title)
                .content(content)
                .contentType(entity.getContentType())
                .sender(entity.getSender())
                .bizType(entity.getBizType())
                .bizId(entity.getBizId())
                .templateCode(templateCode)
                .templateParams(templateParams)
                .receivers(receivers)
                .build();
        List<String> channels =
                command.getChannels() == null || command.getChannels().isEmpty()
                        ? List.of(MessageConstants.CHANNEL_INBOX)
                        : command.getChannels();
        for (String channel : channels) {
            try {
                channelRegistry.get(channel).send(envelope, receivers);
            } catch (MessageBusinessException e) {
                // 渠道未装配（开关关闭或依赖缺失）时跳过该渠道，不影响其他渠道投递
                log.warn("消息渠道 {} 不可用，已跳过: {}", channel, e.getMessage());
            }
        }
        return toVO(entity);
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
