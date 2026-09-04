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
import java.util.LinkedHashSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息主表服务 + 发送编排实现：继承单表 CRUD 基类（save/toVO/pageView），
 * 同时承载管理端分页与发送编排（编排范围：落库 → 渠道投递）
 *
 * @author jin
 */
@Service
public class MessageDispatchServiceImpl extends AbstractCrudService<MessageEntity, Message, MessageMapper>
        implements MessageDispatchService, MessageService {

    private final MessageChannelRegistry channelRegistry;

    public MessageDispatchServiceImpl(MessageChannelRegistry channelRegistry) {
        this.channelRegistry = channelRegistry;
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

        MessageEntity entity = new MessageEntity();
        entity.setCategory(defaultIfBlank(command.getCategory(), MessageConstants.CATEGORY_SYSTEM));
        entity.setLevel(defaultIfBlank(command.getLevel(), MessageConstants.LEVEL_INFO));
        entity.setContentType(defaultIfBlank(command.getContentType(), MessageConstants.CONTENT_TYPE_TEXT));
        entity.setTitle(command.getTitle());
        entity.setContent(command.getContent());
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
                .title(entity.getTitle())
                .content(entity.getContent())
                .contentType(entity.getContentType())
                .sender(entity.getSender())
                .bizType(entity.getBizType())
                .bizId(entity.getBizId())
                .receivers(receivers)
                .build();
        channelRegistry.get(MessageConstants.CHANNEL_INBOX).send(envelope, receivers);
        return toVO(entity);
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
