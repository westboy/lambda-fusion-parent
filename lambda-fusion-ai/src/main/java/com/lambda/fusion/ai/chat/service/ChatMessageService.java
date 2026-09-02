package com.lambda.fusion.ai.chat.service;

import com.lambda.fusion.ai.chat.model.ChatMessagePage;
import com.lambda.fusion.ai.chat.model.entity.ChatMessageEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import java.util.Optional;

public interface ChatMessageService {

    /** 保存用户消息并返回（含自增主键，供附件回填 message_id）。 */
    ChatMessageEntity saveUserMessage(ChatSessionEntity session, String content);

    /** 保存助手消息并返回（含自增主键）。 */
    ChatMessageEntity saveAssistantMessage(ChatSessionEntity session, String content);

    /**
     * 保存助手消息（含工具调用 JSON，供历史回放）。
     *
     * @param toolCall 工具调用快照 JSON，空或 null 表示无工具调用
     */
    ChatMessageEntity saveAssistantMessage(ChatSessionEntity session, String content, String toolCall);

    /**
     * 游标分页查询会话消息历史（含当前页各消息附件）。{@code beforeId} 为空时返回最新一页；
     * 消息按 ID 升序返回，{@code hasMore} 为 true 时以 {@code nextCursor}（当前页最早消息 ID）
     * 作为 {@code beforeId} 继续向前翻页。
     *
     * @param sessionId 会话标识
     * @param beforeId 翻页游标；null 表示从最新消息开始
     * @param limit 页大小；null 或越界时收敛到默认值与上限之间
     */
    ChatMessagePage listBySession(String sessionId, Long beforeId, Integer limit);

    Optional<ChatMessageEntity> findByIdAndSession(Long messageId, String sessionId);

    /**
     * 删除该会话的全部消息。不做租户隔离校验，应由上层调用方先验证会话所有权。
     */
    void deleteBySession(String sessionId);
}
