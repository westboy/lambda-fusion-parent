package com.lambda.fusion.ai.chat.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.attachment.ChatAttachmentPreviewTokenService;
import com.lambda.fusion.ai.chat.mapper.ChatAttachmentMapper;
import com.lambda.fusion.ai.chat.mapper.ChatSessionMapper;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.storage.AiFileStorageResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatAttachmentServiceImplTest {

    private ChatAttachmentMapper attachmentMapper;
    private ChatSessionMapper sessionMapper;
    private ChatAttachmentServiceImpl service;

    @BeforeEach
    void setUp() {
        attachmentMapper = mock(ChatAttachmentMapper.class);
        sessionMapper = mock(ChatSessionMapper.class);
        service = new ChatAttachmentServiceImpl(
                attachmentMapper,
                sessionMapper,
                mock(AiFileStorageResolver.class),
                new AiProperties(),
                mock(ChatAttachmentPreviewTokenService.class));
    }

    @Test
    void shouldLoadOnlySentAttachmentOwnedByRuntimeUserAndSession() {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        when(sessionMapper.selectOwned("session-1", "zhangsan")).thenReturn(session);
        ChatAttachmentEntity attachment = attachment("session-1", 10L);
        when(attachmentMapper.selectById("att-1")).thenReturn(attachment);

        assertThat(service.loadBoundInSession("att-1", "session-1", "zhangsan")).isSameAs(attachment);
    }

    @Test
    void shouldRejectUnsentOrCrossSessionAttachment() {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        when(sessionMapper.selectOwned("session-1", "zhangsan")).thenReturn(session);

        when(attachmentMapper.selectById("att-1")).thenReturn(attachment("session-1", null));
        assertThatThrownBy(() -> service.loadBoundInSession("att-1", "session-1", "zhangsan"))
                .isInstanceOf(AiBusinessException.class);

        when(attachmentMapper.selectById("att-1")).thenReturn(attachment("session-2", 10L));
        assertThatThrownBy(() -> service.loadBoundInSession("att-1", "session-1", "zhangsan"))
                .isInstanceOf(AiBusinessException.class);
    }

    private static ChatAttachmentEntity attachment(String sessionId, Long messageId) {
        ChatAttachmentEntity attachment = new ChatAttachmentEntity();
        attachment.setId("att-1");
        attachment.setSessionId(sessionId);
        attachment.setMessageId(messageId);
        return attachment;
    }
}
