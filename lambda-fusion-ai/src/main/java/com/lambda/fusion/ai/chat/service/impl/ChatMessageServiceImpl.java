package com.lambda.fusion.ai.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.ai.chat.mapper.ChatMessageMapper;
import com.lambda.fusion.ai.chat.mapper.ChatSessionMapper;
import com.lambda.fusion.ai.chat.model.ChatMessagePage;
import com.lambda.fusion.ai.chat.model.ChatMessageView;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatMessageEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.chat.service.ChatMessageService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.core.utils.AuthUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class ChatMessageServiceImpl implements ChatMessageService {

    /** 默认页大小。 */
    private static final int DEFAULT_PAGE_SIZE = 50;

    /** 单页消息数上限；防止一次性加载整段会话与全部附件。 */
    private static final int MAX_PAGE_SIZE = 200;

    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatAttachmentService chatAttachmentService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity saveUserMessage(ChatSessionEntity session, String content) {
        return save(session, "user", content, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity saveAssistantMessage(ChatSessionEntity session, String content) {
        return save(session, "assistant", content, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity saveAssistantMessage(ChatSessionEntity session, String content, String toolCall) {
        return save(session, "assistant", content, toolCall);
    }

    @Override
    public ChatMessagePage listBySession(String sessionId, Long beforeId, Integer limit) {
        ChatSessionEntity session =
                chatSessionMapper.selectOwned(sessionId, AuthUtils.getUser().getUsername());
        if (session == null) {
            throw new AiBusinessException(AiErrorCode.CHAT_SESSION_NOT_FOUND, sessionId);
        }
        int pageSize = normalizePageSize(limit);
        // 游标向前翻页：按 ID 倒序多取一条判定 hasMore，再反转为升序返回
        LambdaQueryWrapper<ChatMessageEntity> query = new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getSessionId, sessionId)
                .orderByDesc(ChatMessageEntity::getId)
                .last("LIMIT " + (pageSize + 1));
        if (beforeId != null) {
            query.lt(ChatMessageEntity::getId, beforeId);
        }
        List<ChatMessageEntity> fetched = chatMessageMapper.selectList(query);
        boolean hasMore = fetched.size() > pageSize;
        List<ChatMessageEntity> page = hasMore ? fetched.subList(0, pageSize) : fetched;
        List<ChatMessageEntity> ascending = new ArrayList<>(page);
        Collections.reverse(ascending);

        List<Long> messageIds = ascending.stream().map(ChatMessageEntity::getId).toList();
        Map<Long, List<ChatAttachmentEntity>> attachmentsByMessage =
                chatAttachmentService.listByMessageIds(messageIds).stream()
                        .collect(Collectors.groupingBy(ChatAttachmentEntity::getMessageId));
        List<ChatMessageView> messages = ascending.stream()
                .map(m -> ChatMessageView.of(
                        m,
                        attachmentsByMessage.getOrDefault(m.getId(), List.of()).stream()
                                .map(chatAttachmentService::toView)
                                .toList()))
                .toList();

        ChatMessagePage result = new ChatMessagePage();
        result.setMessages(messages);
        result.setHasMore(hasMore);
        result.setNextCursor(hasMore && !ascending.isEmpty() ? ascending.get(0).getId() : null);
        return result;
    }

    /** 收敛页大小：null 取默认值，越界截断到 [1, MAX_PAGE_SIZE]。 */
    private static int normalizePageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
    }

    @Override
    public Optional<ChatMessageEntity> findByIdAndSession(Long messageId, String sessionId) {
        return Optional.ofNullable(chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getId, messageId)
                .eq(ChatMessageEntity::getSessionId, sessionId)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBySession(String sessionId) {
        chatMessageMapper.delete(
                new LambdaQueryWrapper<ChatMessageEntity>().eq(ChatMessageEntity::getSessionId, sessionId));
    }

    private ChatMessageEntity save(ChatSessionEntity session, String role, String content, String toolCall) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setSessionId(session.getId());
        entity.setRole(role);
        entity.setContent(content);
        if (StringUtils.isNotBlank(toolCall)) {
            entity.setToolCall(toolCall);
        }
        entity.setCreatedAt(LocalDateTime.now());
        chatMessageMapper.insert(entity);
        return entity;
    }
}
