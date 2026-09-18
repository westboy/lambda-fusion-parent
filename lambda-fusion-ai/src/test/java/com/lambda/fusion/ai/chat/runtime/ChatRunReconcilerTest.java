package com.lambda.fusion.ai.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.ChatRunFailureCode;
import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.mapper.ChatRunMapper;
import com.lambda.fusion.ai.chat.mapper.ChatSessionMapper;
import com.lambda.fusion.ai.chat.model.ChatRunFinalizationCommand;
import com.lambda.fusion.ai.chat.model.ChatRunFinalizationResult;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEvent;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEventStore;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshotCodec;
import com.lambda.fusion.ai.chat.service.ChatRunStateService;
import com.lambda.fusion.ai.runtime.workspace.WorkspaceAuditRecorder;
import io.agentscope.core.agui.event.AguiEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Run 对账任务测试：孤儿运行按 {@code INTERRUPTED} 收敛、HITL 待交互超时按 {@code AWAIT_TIMEOUT}
 * 收敛并优先补写 AgentScope 拒绝结果、未到类别阈值的记录跳过。
 */
class ChatRunReconcilerTest {

    private ChatRunMapper runMapper;
    private ChatSessionMapper sessionMapper;
    private ChatExecutionInstanceFactory instanceFactory;
    private ChatRunStateService runService;
    private ChatRunEventStore eventStore;
    private AgentExecutionAdapter adapter;
    private WorkspaceAuditRecorder workspaceAuditRecorder;
    private ScheduledExecutorService instanceScheduler;
    private AiProperties properties;
    private ChatRunReconciler reconciler;

    @BeforeEach
    void setUp() {
        runMapper = mock(ChatRunMapper.class);
        sessionMapper = mock(ChatSessionMapper.class);
        instanceFactory = mock(ChatExecutionInstanceFactory.class);
        runService = mock(ChatRunStateService.class);
        eventStore = mock(ChatRunEventStore.class);
        adapter = mock(AgentExecutionAdapter.class);
        workspaceAuditRecorder = mock(WorkspaceAuditRecorder.class);
        instanceScheduler = Executors.newSingleThreadScheduledExecutor();
        properties = new AiProperties();
        reconciler = new ChatRunReconciler(runMapper, sessionMapper, instanceFactory, properties);

        when(sessionMapper.selectById("session-1")).thenReturn(session());
        lenient()
                .when(runService.finalizeExecution(any(), any(ChatRunFinalizationCommand.class)))
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
        lenient()
                .when(eventStore.appendTerminalIfAbsent(any(), any(), any(AguiEvent.class), any(), any()))
                .thenReturn(new ChatRunEvent(1L, "RUN_ERROR", "{}"));
        lenient()
                .when(instanceFactory.createTerminalOnly(any(), any(), any()))
                .thenAnswer(invocation -> terminalInstance(invocation.getArgument(0), invocation.getArgument(1), null));
        lenient()
                .when(instanceFactory.createAgentBacked(any(), any(), any()))
                .thenAnswer(
                        invocation -> terminalInstance(invocation.getArgument(0), invocation.getArgument(1), adapter));
    }

    @AfterEach
    void tearDown() {
        instanceScheduler.shutdownNow();
    }

    @Test
    void shouldConvergeOrphanRunAsInterrupted() {
        ChatRunEntity orphan = staleRun(7200, false);
        when(runMapper.selectList(any())).thenReturn(List.of(orphan));

        reconciler.reconcile();

        ChatRunFinalizationCommand command = captureFinalize();
        assertThat(command.targetStatus()).isEqualTo(ChatRunStatus.FAILED);
        assertThat(command.errorCode()).isEqualTo(ChatRunFailureCode.INTERRUPTED);
    }

    @Test
    void shouldConvergeAwaitingRunAsTimeoutAndDenyPendingToolCalls() {
        ChatRunEntity awaiting = staleRun(90_000, true);
        when(runMapper.selectList(any())).thenReturn(List.of(awaiting));

        reconciler.reconcile();

        // 与 stop 路径同语义：带 Agent 实例终结时会补写 AgentScope 拒绝结果。
        verify(adapter).denyPendingToolCalls();
        ChatRunFinalizationCommand command = captureFinalize();
        assertThat(command.targetStatus()).isEqualTo(ChatRunStatus.FAILED);
        assertThat(command.errorCode()).isEqualTo(ChatRunFailureCode.AWAIT_TIMEOUT);
    }

    @Test
    void shouldFallbackToTerminalOnlyWhenAgentStateUnavailable() {
        ChatRunEntity awaiting = staleRun(90_000, true);
        when(runMapper.selectList(any())).thenReturn(List.of(awaiting));
        // 该方法已有 Answer 桩，重新打桩须用 doThrow 避免触发既有 Answer。
        org.mockito.Mockito.doThrow(new IllegalStateException("agentscope state store unavailable"))
                .when(instanceFactory)
                .createAgentBacked(any(), any(), any());

        reconciler.reconcile();

        ChatRunFinalizationCommand command = captureFinalize();
        assertThat(command.targetStatus()).isEqualTo(ChatRunStatus.FAILED);
        assertThat(command.errorCode()).isEqualTo(ChatRunFailureCode.AWAIT_TIMEOUT);
    }

    @Test
    void shouldSkipAwaitingRunBeforeAwaitTimeout() {
        // 待交互运行超过孤儿阈值但未超过待交互阈值：不收敛。
        ChatRunEntity awaiting = staleRun(4_000, true);
        when(runMapper.selectList(any())).thenReturn(List.of(awaiting));

        reconciler.reconcile();

        verify(instanceFactory, never()).createTerminalOnly(any(), any(), any());
        verify(instanceFactory, never()).createAgentBacked(any(), any(), any());
    }

    @Test
    void shouldSkipRunWithFreshProgress() {
        ChatRunEntity fresh = staleRun(10, false);
        when(runMapper.selectList(any())).thenReturn(List.of(fresh));

        reconciler.reconcile();

        verify(instanceFactory, never()).createTerminalOnly(any(), any(), any());
    }

    @Test
    void shouldTreatUndecodableSnapshotAsOrphan() {
        ChatRunEntity broken = staleRun(7_200, false);
        broken.setSnapshotJson("not-a-json");
        when(runMapper.selectList(any())).thenReturn(List.of(broken));

        reconciler.reconcile();

        ChatRunFinalizationCommand command = captureFinalize();
        assertThat(command.errorCode()).isEqualTo(ChatRunFailureCode.INTERRUPTED);
    }

    @Test
    void shouldUseStartedAtWhenUpdatedAtIsNull() {
        // 新建后从未写入任何行（updated_at 由 UPDATE 填充，可能为空）：以 started_at 起算。
        ChatRunEntity orphan = staleRun(7_200, false);
        orphan.setUpdatedAt(null);
        when(runMapper.selectList(any())).thenReturn(List.of(orphan));

        reconciler.reconcile();

        ChatRunFinalizationCommand command = captureFinalize();
        assertThat(command.errorCode()).isEqualTo(ChatRunFailureCode.INTERRUPTED);
    }

    private ChatRunFinalizationCommand captureFinalize() {
        ArgumentCaptor<ChatRunFinalizationCommand> captor = ArgumentCaptor.forClass(ChatRunFinalizationCommand.class);
        verify(runService).finalizeExecution(any(), captor.capture());
        return captor.getValue();
    }

    private ChatExecutionInstance terminalInstance(
            ChatRunEntity run, ChatSessionEntity session, AgentExecutionAdapter executionAdapter) {
        return new ChatExecutionInstance(
                runService,
                eventStore,
                properties,
                instanceScheduler,
                (Runnable task) -> task.run(),
                workspaceAuditRecorder,
                run,
                session,
                executionAdapter,
                new ChatExecutionSnapshotBuilder(ChatRunSnapshotCodec.decode(
                        run.getSnapshotJson() == null || !run.getSnapshotJson().startsWith("{")
                                ? ChatRunSnapshotCodec.encode(ChatRunSnapshot.empty("run-1", "agui-1", 1))
                                : run.getSnapshotJson())));
    }

    private static ChatRunEntity staleRun(long staleSeconds, boolean pendingInteraction) {
        ChatRunEntity run = new ChatRunEntity();
        run.setId("run-1");
        run.setSessionId("session-1");
        run.setStatus(ChatRunStatus.RUNNING.name());
        run.setPhaseNo(1);
        run.setAguiRunId("agui-1");
        run.setSnapshotVersion(0L);
        ChatRunSnapshot snapshot = pendingInteraction
                ? new ChatRunSnapshot(
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
                        List.of(new ChatRunSnapshot.ToolCall("call-1", "demo_tool", "", "", "asking")),
                        List.of(),
                        List.of())
                : ChatRunSnapshot.empty("run-1", "agui-1", 1);
        run.setSnapshotJson(ChatRunSnapshotCodec.encode(snapshot));
        run.setStartedAt(LocalDateTime.now().minusSeconds(staleSeconds));
        run.setUpdatedAt(LocalDateTime.now().minusSeconds(staleSeconds));
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
