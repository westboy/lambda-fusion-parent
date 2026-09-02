package com.lambda.fusion.ai.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.service.AppService;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.agui.AguiBootstrapModel;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEventStore;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshotCodec;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.chat.service.ChatMessageService;
import com.lambda.fusion.ai.chat.service.ChatRunStateService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 重连引导路径的锁定测试：本地执行句柄缺失时只读数据库投影，不改变业务 Run。 */
class ChatRunCoordinatorResumeTest {

    private final ChatRunStateService runService = mock(ChatRunStateService.class);
    private final ChatRunEventStore eventStore = mock(ChatRunEventStore.class);
    private final ChatExecutionInstanceFactory instanceFactory = mock(ChatExecutionInstanceFactory.class);
    private ChatExecutionService coordinator;

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.shutdown();
        }
    }

    @Test
    void shouldNotFinalizeRunningRunWhenNoLocalExecutionExists() {
        ChatRunEntity run = run(ChatRunStatus.RUNNING, snapshot("partial answer", true, false, List.of(), List.of()));
        when(runService.loadCurrentOrIdentity(run)).thenReturn(run);
        when(eventStore.latestCursor("run-1")).thenReturn(0L);
        when(eventStore.contains("run-1")).thenReturn(false);
        coordinator = newCoordinator();

        AguiBootstrapModel bootstrap = coordinator.bootstrap(run, session());

        assertThat(bootstrap.phaseClosed()).isTrue();
        assertThat(bootstrap.cursor()).isZero();
        assertThat(bootstrap.events()).anyMatch(event -> event.contains("partial answer"));
        assertThat(bootstrap.events()).noneMatch(event -> event.contains("\"type\":\"RUN_ERROR\""));
        verify(runService, never()).finalizeExecution(any(), any());
        verifyNoInteractions(instanceFactory);
    }

    @Test
    void shouldReplayAwaitingInteractionWithoutFinalizingWhenNoLocalInstance() {
        // HITL 挂起由用户显式确认/输入恢复，不得按孤儿收敛。
        ChatRunEntity run = run(
                ChatRunStatus.RUNNING,
                snapshot(
                        "partial",
                        false,
                        false,
                        List.of(),
                        List.of(new ChatRunSnapshot.ToolCall("tool-1", "dangerous", "", "", "asking"))));
        when(runService.loadCurrentOrIdentity(run)).thenReturn(run);
        when(eventStore.latestCursor("run-1")).thenReturn(0L);
        when(eventStore.contains("run-1")).thenReturn(false);
        coordinator = newCoordinator();

        AguiBootstrapModel bootstrap = coordinator.bootstrap(run, session());

        assertThat(bootstrap.phaseClosed()).isTrue();
        assertThat(bootstrap.events())
                .anyMatch(event -> event.contains("\"type\":\"RUN_FINISHED\"") && event.contains("tool-1"));
        verify(runService, never()).finalizeExecution(any(), any());
    }

    private ChatExecutionService newCoordinator() {
        return new ChatExecutionService(
                runService,
                eventStore,
                mock(ChatMessageService.class),
                mock(ChatAttachmentService.class),
                null,
                mock(AppService.class),
                instanceFactory,
                new AiProperties());
    }

    private static ChatRunSnapshot snapshot(
            String text,
            boolean textOpen,
            boolean reasoningOpen,
            List<ChatRunSnapshot.ToolCall> tools,
            List<ChatRunSnapshot.ToolCall> pendingTools) {
        return new ChatRunSnapshot(
                "run-1",
                "agui-1",
                1,
                text,
                "",
                textOpen ? "m-text" : null,
                null,
                textOpen,
                reasoningOpen,
                tools,
                pendingTools,
                List.of(),
                List.of());
    }

    private static ChatRunEntity run(ChatRunStatus status, ChatRunSnapshot snapshot) {
        ChatRunEntity run = new ChatRunEntity();
        run.setId("run-1");
        run.setSessionId("session-1");
        run.setAguiRunId("agui-1");
        run.setPhaseNo(1);
        run.setStatus(status.name());
        run.setSnapshotJson(snapshot == null ? null : ChatRunSnapshotCodec.encode(snapshot));
        return run;
    }

    private static ChatSessionEntity session() {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        session.setAppId("app-1");
        session.setTenantId("tenant-1");
        return session;
    }
}
