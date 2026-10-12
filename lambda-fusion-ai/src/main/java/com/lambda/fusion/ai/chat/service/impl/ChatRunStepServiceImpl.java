package com.lambda.fusion.ai.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.ai.chat.mapper.ChatRunMapper;
import com.lambda.fusion.ai.chat.mapper.ChatRunStepMapper;
import com.lambda.fusion.ai.chat.mapper.ChatSessionMapper;
import com.lambda.fusion.ai.chat.model.ChatRunStep;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatRunStepEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.service.ChatRunStepService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.core.utils.AuthUtils;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对话运行执行步骤存储实现：按 (run_id, step_no) 唯一键幂等追加，写入失败由执行实例重试。
 *
 * @author Jin
 */
@Service
@RequiredArgsConstructor
public class ChatRunStepServiceImpl implements ChatRunStepService {

    private final ChatRunStepMapper chatRunStepMapper;
    private final ChatRunMapper chatRunMapper;
    private final ChatSessionMapper chatSessionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendSteps(String sessionId, String runId, List<ChatRunStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (ChatRunStep step : steps) {
            ChatRunStepEntity entity = new ChatRunStepEntity();
            entity.setSessionId(sessionId);
            entity.setRunId(runId);
            entity.setPhaseNo(step.phaseNo());
            entity.setStepNo(step.position());
            entity.setKind(step.kind());
            entity.setContent(step.content());
            entity.setToolCallId(step.toolCallId());
            entity.setToolName(step.toolName());
            entity.setToolArgs(step.toolArgs());
            entity.setToolResult(step.toolResult());
            entity.setStatus(step.status());
            entity.setStartedAt(step.startedAt());
            entity.setFinishedAt(step.finishedAt());
            entity.setCreatedAt(now);
            try {
                chatRunStepMapper.insert(entity);
            } catch (DuplicateKeyException duplicated) {
                // 步骤事实只落一次：重复 (runId, stepNo) 视为已持久化，继续处理后续步骤。
            }
        }
    }

    @Override
    public int maxStepNo(String runId) {
        ChatRunStepEntity latest = chatRunStepMapper.selectOne(new LambdaQueryWrapper<ChatRunStepEntity>()
                .eq(ChatRunStepEntity::getRunId, runId)
                .orderByDesc(ChatRunStepEntity::getStepNo)
                .last("LIMIT 1"));
        return latest == null || latest.getStepNo() == null ? 0 : latest.getStepNo();
    }

    @Override
    public List<ChatRunStep> listByMessage(String sessionId, Long messageId) {
        ChatSessionEntity session =
                chatSessionMapper.selectOwned(sessionId, AuthUtils.getUser().getUsername());
        if (session == null) {
            throw new AiBusinessException(AiErrorCode.CHAT_SESSION_NOT_FOUND, sessionId);
        }
        ChatRunEntity run = chatRunMapper.selectOne(new LambdaQueryWrapper<ChatRunEntity>()
                .eq(ChatRunEntity::getSessionId, sessionId)
                .eq(ChatRunEntity::getAssistantMessageId, messageId)
                .last("LIMIT 1"));
        if (run == null) {
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_NOT_FOUND, String.valueOf(messageId));
        }
        return chatRunStepMapper
                .selectList(new LambdaQueryWrapper<ChatRunStepEntity>()
                        .eq(ChatRunStepEntity::getRunId, run.getId())
                        .orderByAsc(ChatRunStepEntity::getStepNo))
                .stream()
                .map(ChatRunStepServiceImpl::toStep)
                .toList();
    }

    private static ChatRunStep toStep(ChatRunStepEntity entity) {
        return new ChatRunStep(
                entity.getStepNo() == null ? 0 : entity.getStepNo(),
                entity.getPhaseNo() == null ? 0 : entity.getPhaseNo(),
                entity.getKind(),
                entity.getContent(),
                entity.getToolCallId(),
                entity.getToolName(),
                entity.getToolArgs(),
                entity.getToolResult(),
                entity.getStatus(),
                entity.getStartedAt(),
                entity.getFinishedAt());
    }
}
