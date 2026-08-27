package com.lambda.fusion.ai.chat.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.apps.service.AppService;
import com.lambda.fusion.ai.chat.mapper.ChatRunMapper;
import com.lambda.fusion.ai.chat.mapper.ChatSessionMapper;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.chat.service.ChatMessageService;
import com.lambda.fusion.ai.runtime.AgentFactory;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.core.utils.AuthUtils;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ChatSessionServiceImplTest {

    @Test
    void shouldClearExternalSessionResourcesOnlyAfterDeleteCommits() {
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ChatRunMapper runMapper = mock(ChatRunMapper.class);
        ChatMessageService messageService = mock(ChatMessageService.class);
        ChatAttachmentService attachmentService = mock(ChatAttachmentService.class);
        AgentFactory agentFactory = mock(AgentFactory.class);
        ChatSessionServiceImpl service = new ChatSessionServiceImpl(
                sessionMapper, runMapper, mock(AppService.class), messageService, attachmentService, agentFactory);
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setTenantId("tenant-1");
        session.setAppId("app-1");
        session.setUserId("user-1");
        UserDetails user = new UserDetails();
        user.setUsername("user-1");
        when(sessionMapper.selectOwnedForUpdate("session-1", "user-1")).thenReturn(session);
        when(runMapper.exists(any())).thenReturn(false);

        TransactionSynchronizationManager.initSynchronization();
        try (MockedStatic<AuthUtils> auth = Mockito.mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(user);

            service.delete("session-1");

            verify(attachmentService).deleteBySession("session-1");
            verify(messageService).deleteBySession("session-1");
            verify(sessionMapper).deleteById("session-1");
            verifyNoInteractions(agentFactory);

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(agentFactory).clearChatSessionWorkspace("app-1", "tenant-1", "user-1", "session-1");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldKeepExternalSessionResourcesWhenDeleteRollsBack() {
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ChatRunMapper runMapper = mock(ChatRunMapper.class);
        ChatMessageService messageService = mock(ChatMessageService.class);
        ChatAttachmentService attachmentService = mock(ChatAttachmentService.class);
        AgentFactory agentFactory = mock(AgentFactory.class);
        ChatSessionServiceImpl service = new ChatSessionServiceImpl(
                sessionMapper, runMapper, mock(AppService.class), messageService, attachmentService, agentFactory);
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setTenantId("tenant-1");
        session.setAppId("app-1");
        session.setUserId("user-1");
        UserDetails user = new UserDetails();
        user.setUsername("user-1");
        when(sessionMapper.selectOwnedForUpdate("session-1", "user-1")).thenReturn(session);
        when(runMapper.exists(any())).thenReturn(false);

        TransactionSynchronizationManager.initSynchronization();
        try (MockedStatic<AuthUtils> auth = Mockito.mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(user);

            service.delete("session-1");

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization ->
                            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verifyNoInteractions(agentFactory);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
