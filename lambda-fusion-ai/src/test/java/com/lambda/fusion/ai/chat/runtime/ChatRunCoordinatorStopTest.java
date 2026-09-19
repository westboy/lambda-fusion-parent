package com.lambda.fusion.ai.chat.runtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.service.AppService;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEventStore;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshotCodec;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.chat.service.ChatMessageService;
import com.lambda.fusion.ai.chat.service.ChatRunStateService;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ChatRunCoordinatorStopTest {

    private ChatRunCoordinator coordinator;

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.shutdown();
        }
    }

    @Test
    void shouldOnlyCloseBusinessRunWhenThereIsNoLocalExecution() {
        ChatRunInstanceFactory instanceFactory = mock(ChatRunInstanceFactory.class);
        ChatRunInstance finalizer = mock(ChatRunInstance.class);
        ChatRunEntity run = new ChatRunEntity();
        run.setId("run-1");
        run.setStatus(ChatRunStatus.RUNNING.name());
        run.setSnapshotJson(ChatRunSnapshotCodec.encode(ChatRunSnapshot.empty("run-1", "agui-1", 1)));
        ChatSessionEntity session = new ChatSessionEntity();
        session.setTenantId("tenant-1");
        when(instanceFactory.createTerminalOnly(any(), any(), any(ScheduledExecutorService.class)))
                .thenReturn(finalizer);

        coordinator = coordinator(instanceFactory);
        coordinator.stop(run, session);

        verify(finalizer).requestStop();
        verify(instanceFactory, never()).createAgentBacked(any(), any(), any(ScheduledExecutorService.class));
        verify(finalizer, never()).interruptAgent();
    }

    @Test
    void shouldClosePersistedAskingStateWithoutInterruptingAgent() {
        ChatRunInstanceFactory instanceFactory = mock(ChatRunInstanceFactory.class);
        ChatRunInstance finalizer = mock(ChatRunInstance.class);
        ChatRunEntity run = new ChatRunEntity();
        run.setId("run-1");
        run.setStatus(ChatRunStatus.RUNNING.name());
        run.setSnapshotJson(ChatRunSnapshotCodec.encode(new ChatRunSnapshot(
                "run-1",
                "agui-1",
                1,
                "",
                "",
                null,
                null,
                false,
                false,
                List.of(),
                List.of(new ChatRunSnapshot.ToolCall("call-1", "dangerous", "", "", "asking")),
                List.of(),
                List.of())));
        ChatSessionEntity session = new ChatSessionEntity();
        session.setTenantId("tenant-1");
        when(instanceFactory.createAgentBacked(any(), any(), any(ScheduledExecutorService.class)))
                .thenReturn(finalizer);

        coordinator = coordinator(instanceFactory);
        coordinator.stop(run, session);

        verify(finalizer).requestStop();
        verify(finalizer, never()).interruptAgent();
        verify(instanceFactory, never()).createTerminalOnly(any(), any(), any(ScheduledExecutorService.class));
    }

    private static ChatRunCoordinator coordinator(ChatRunInstanceFactory instanceFactory) {
        ChatRunStateService runService = mock(ChatRunStateService.class);
        when(runService.loadCurrentOrIdentity(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new ChatRunCoordinator(
                runService,
                mock(ChatRunEventStore.class),
                mock(ChatMessageService.class),
                mock(ChatAttachmentService.class),
                null,
                mock(AppService.class),
                instanceFactory,
                new AiProperties());
    }
}
