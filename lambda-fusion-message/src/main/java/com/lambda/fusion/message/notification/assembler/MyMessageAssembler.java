package com.lambda.fusion.message.notification.assembler;

import com.lambda.fusion.message.notification.mapper.MessageMapper;
import com.lambda.fusion.message.notification.model.entity.MessageEntity;
import com.lambda.fusion.message.notification.model.entity.UserMessageEntity;
import com.lambda.fusion.message.notification.model.vo.MyMessage;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 我的消息组装器：la_user_messages 投递实例 + la_messages 消息主表 → 用户侧视图
 *
 * @author jin
 */
@Component
@RequiredArgsConstructor
public class MyMessageAssembler {

    private final MessageMapper messageMapper;

    /**
     * 批量组装
     *
     * @param userMessages 用户消息实例（单页）
     * @return 我的消息列表，顺序与入参一致
     */
    public List<MyMessage> assemble(List<UserMessageEntity> userMessages) {
        List<String> messageIds = userMessages.stream()
                .map(UserMessageEntity::getMessageId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, MessageEntity> messages = messageIds.isEmpty()
                ? Map.of()
                : listMessagesByIds(messageIds).stream()
                        .collect(Collectors.toMap(MessageEntity::getId, Function.identity(), (a, b) -> a));

        return userMessages.stream()
                .map(um -> assemble(um, messages.get(um.getMessageId())))
                .toList();
    }

    private List<MessageEntity> listMessagesByIds(List<String> ids) {
        return messageMapper.selectBatchIds(ids);
    }

    /**
     * 单条组装；消息主表缺失（异常数据）时仅返回投递态字段
     *
     * @param userMessage 用户消息实例
     * @param message     消息主表，可为 null
     * @return 我的消息
     */
    public MyMessage assemble(UserMessageEntity userMessage, MessageEntity message) {
        MyMessage vo = new MyMessage();
        vo.setId(userMessage.getId());
        vo.setMessageId(userMessage.getMessageId());
        vo.setIsRead(userMessage.getIsRead());
        vo.setReadTime(userMessage.getReadTime());
        vo.setCreatedAt(userMessage.getCreatedAt());
        if (message != null) {
            vo.setCategory(message.getCategory());
            vo.setLevel(message.getLevel());
            vo.setTitle(message.getTitle());
            vo.setContent(message.getContent());
            vo.setContentType(message.getContentType());
            vo.setSender(message.getSender());
        }
        return vo;
    }
}
