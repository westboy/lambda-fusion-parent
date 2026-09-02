package com.lambda.fusion.ai.chat.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.apps.service.AppService;
import com.lambda.fusion.ai.chat.mapper.ChatRunMapper;
import com.lambda.fusion.ai.chat.mapper.ChatSessionMapper;
import com.lambda.fusion.ai.chat.model.ChatSessionPage;
import com.lambda.fusion.ai.chat.model.CreateSession;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.chat.service.ChatMessageService;
import com.lambda.fusion.ai.chat.service.ChatSessionService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.runtime.AgentFactory;
import com.lambda.fusion.core.utils.AuthUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class ChatSessionServiceImpl implements ChatSessionService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatRunMapper chatRunMapper;
    private final AppService appService;
    private final ChatMessageService chatMessageService;
    private final ChatAttachmentService chatAttachmentService;
    private final AgentFactory agentFactory;

    @Override
    public Page<ChatSessionEntity> page(ChatSessionPage query) {
        return chatSessionMapper.selectPage(query.getPage(), query.getLambdaQueryWrapper());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatSessionEntity create(CreateSession dto) {
        var app = appService.loadAvailable(dto.getAppId());
        ChatSessionEntity entity = new ChatSessionEntity();
        entity.setId(IdUtil.getSnowflakeNextIdStr());
        entity.setAppId(dto.getAppId());
        entity.setUserId(AuthUtils.getUser().getUsername());
        entity.setTitle(StringUtils.defaultIfBlank(dto.getTitle(), app.getName()));
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setLastMessageAt(LocalDateTime.now());
        chatSessionMapper.insert(entity);
        return entity;
    }

    @Override
    public ChatSessionEntity get(String id) {
        return loadOwned(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        ChatSessionEntity session = loadOwnedForUpdate(id);
        LambdaQueryWrapper<ChatRunEntity> activeQuery = new LambdaQueryWrapper<ChatRunEntity>()
                .eq(ChatRunEntity::getSessionId, id)
                .notIn(
                        ChatRunEntity::getStatus,
                        ChatRunStatus.COMPLETED.name(),
                        ChatRunStatus.STOPPED.name(),
                        ChatRunStatus.FAILED.name());
        if (chatRunMapper.exists(activeQuery)) {
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_ALREADY_ACTIVE, id);
        }
        LambdaQueryWrapper<ChatRunEntity> runQuery =
                new LambdaQueryWrapper<ChatRunEntity>().eq(ChatRunEntity::getSessionId, id);
        chatRunMapper.delete(runQuery);
        chatAttachmentService.deleteBySession(id);
        chatMessageService.deleteBySession(id);
        chatSessionMapper.deleteById(id);
        runAfterCommit(() -> {
            agentFactory.clearChatSessionWorkspace(
                    session.getAppId(), session.getTenantId(), session.getUserId(), session.getId());
            agentFactory.clearChatSessionState(
                    session.getAppId(), session.getTenantId(), session.getUserId(), session.getId());
        });
    }

    /** 会话数据库删除提交后再清理外部工作区；无事务上下文时保持服务方法可独立调用。 */
    private static void runAfterCommit(Runnable cleanup) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
            return;
        }
        cleanup.run();
    }

    @Override
    public ChatSessionEntity loadOwned(String id) {
        ChatSessionEntity entity =
                chatSessionMapper.selectOwned(id, AuthUtils.getUser().getUsername());
        if (entity == null) {
            throw new AiBusinessException(AiErrorCode.CHAT_SESSION_NOT_FOUND, id);
        }
        return entity;
    }

    @Override
    public ChatSessionEntity loadOwnedForUpdate(String id) {
        ChatSessionEntity entity =
                chatSessionMapper.selectOwnedForUpdate(id, AuthUtils.getUser().getUsername());
        if (entity == null) {
            throw new AiBusinessException(AiErrorCode.CHAT_SESSION_NOT_FOUND, id);
        }
        return entity;
    }
}
