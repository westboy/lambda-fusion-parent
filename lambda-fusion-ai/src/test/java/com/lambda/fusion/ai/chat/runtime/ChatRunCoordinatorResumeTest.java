package com.lambda.fusion.ai.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.ChatRunFailureCode;
import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.service.AppService;
import com.lambda.fusion.ai.chat.model.ChatRunFinalizationCommand;
import com.lambda.fusion.ai.chat.model.ChatRunFinalizationResult;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.agui.AguiBootstrapModel;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEventStore;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshotCodec;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.chat.service.ChatMessageService;
import com.lambda.fusion.ai.chat.service.ChatRunStateService;
import com.lambda.fusion.ai.runtime.workspace.WorkspaceAuditRecorder;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 重连引导路径的锁定测试：无本地实例的 RUNNING 运行按孤儿或待交互两种口径收敛。 */
class ChatRunCoordinatorResumeTest {

    private final ChatRunStateService runService = mock(ChatRunStateService.class);
    private final ChatRunEventStore eventStore = mock(ChatRunEventStore.class);
    private final ChatExecutionInstanceFactory instanceFactory = mock(ChatExecutionInstanceFactory.class);
    private final ScheduledExecutorService instanceScheduler = Executors.newSingleThreadScheduledExecutor();
    private ChatExecutionService coordinator;

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.shutdown();
        }
        instanceScheduler.shutdownNow();
    }

    @Test
    void shouldFinalizeOrphanedRunAsInterruptedWhenNoLocalInstanceAndNoPendingInteraction() {
        ChatRunEntity run = run(ChatRunStatus.RUNNING, snapshot("partial answer", true, false, List.of(), List.of()));
        ChatRunEntity failed = run(ChatRunStatus.FAILED, null);
        failed.setErrorCode(ChatRunFailureCode.INTERRUPTED.name());
        failed.setErrorMessage("服务重启导致对话运行中断，请重新发送消息");
        // 终态提交后回读数据库权威状态：快照已清空、状态已迁移。
        when(runService.loadCurrentOrIdentity(run)).thenReturn(run, failed);
        when(eventStore.latestCursor("run-1")).thenReturn(0L);
        when(eventStore.contains("run-1")).thenReturn(false);
        when(instanceFactory.createAgentBacked(eq(run), any(), any()))
                .thenThrow(new IllegalStateException("agent state unavailable"));
        when(instanceFactory.createTerminalOnly(eq(run), any(), any())).thenReturn(terminalInstance(run));
        when(runService.finalizeExecution(eq(run), any(ChatRunFinalizationCommand.class)))
                .thenAnswer(invocation -> {
                    ChatRunFinalizationCommand command = invocation.getArgument(1);
                    return new ChatRunFinalizationResult(
                            true,
                            command.targetStatus().name(),
                            command.finishReason() == null
                                    ? null
                                    : command.finishReason().name(),
                            command.errorCode() == null
                                    ? null
                                    : command.errorCode().name(),
                            command.errorMessage());
                });
        coordinator = newCoordinator();

        AguiBootstrapModel bootstrap = coordinator.bootstrap(run, session());

        assertThat(bootstrap.phaseClosed()).isTrue();
        assertThat(bootstrap.cursor()).isZero();
        assertThat(bootstrap.events().getLast())
                .contains("\"type\":\"RUN_ERROR\"")
                .contains("\"code\":\"INTERRUPTED\"")
                .contains("服务重启导致对话运行中断");
        ArgumentCaptor<ChatRunFinalizationCommand> captor = ArgumentCaptor.forClass(ChatRunFinalizationCommand.class);
        verify(runService).finalizeExecution(eq(run), captor.capture());
        assertThat(captor.getValue().targetStatus()).isEqualTo(ChatRunStatus.FAILED);
        assertThat(captor.getValue().errorCode()).isEqualTo(ChatRunFailureCode.INTERRUPTED);
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

    /** Agent 状态不可用时的降级实例：无适配器，仅承担终态落库。 */
    private ChatExecutionInstance terminalInstance(ChatRunEntity run) {
        return new ChatExecutionInstance(
                runService,
                eventStore,
                new AiProperties(),
                instanceScheduler,
                mock(WorkspaceAuditRecorder.class),
                run,
                session(),
                null,
                new ChatExecutionSnapshotBuilder(ChatRunSnapshotCodec.decode(run.getSnapshotJson())));
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
