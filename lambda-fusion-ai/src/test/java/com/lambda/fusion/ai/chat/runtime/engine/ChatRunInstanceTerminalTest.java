package com.lambda.fusion.ai.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.ChatRunFinishReason;
import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.model.ChatRunFinalizationCommand;
import com.lambda.fusion.ai.chat.model.ChatRunFinalizationResult;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEvent;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEventStore;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshotCodec;
import com.lambda.fusion.ai.chat.service.ChatRunStateService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.runtime.workspace.WorkspaceAuditRecorder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** 终态解释与关闭路径的锁定测试：终态事实由 {@link ChatExecutionInstance} 唯一解释。 */
class ChatRunInstanceTerminalTest {

    private final ChatRunStateService runService = mock(ChatRunStateService.class);
    private final ChatRunEventStore eventStore = mock(ChatRunEventStore.class);
    private final AgentExecutionAdapter adapter = mock(AgentExecutionAdapter.class);
    private final WorkspaceAuditRecorder workspaceAuditRecorder = mock(WorkspaceAuditRecorder.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ChatExecutionInstance instance;

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void shouldFinalizeCompletedOnceOnRootAgentEnd() {
        instance = newInstance(run(ChatRunStatus.RUNNING, snapshot("", false, false)));
        stubTerminalCommit();
        when(adapter.stream(any(Msg.class))).thenReturn(Flux.just(agentEnd(null)));

        instance.startPhase(
                Msg.builderForRole(io.agentscope.core.message.MsgRole.USER).build());

        ChatRunFinalizationCommand command = captureFinalize();
        assertThat(command.targetStatus()).isEqualTo(ChatRunStatus.COMPLETED);
        assertThat(command.finishReason()).isEqualTo(ChatRunFinishReason.SUCCESS);
        verify(runService, times(1)).finalizeExecution(eq(instance.run()), any(ChatRunFinalizationCommand.class));
    }

    @Test
    void shouldKeepExplicitStopWhenRootAgentEndArrivesLate() {
        ChatRunEntity run = run(ChatRunStatus.RUNNING, snapshot("", false, false));
        instance = newInstance(run);
        stubTerminalCommit();
        when(runService.checkpoint(eq(run), any(ChatRunSnapshot.class), anyLong()))
                .thenReturn(true);
        Sinks.Many<AgentEvent> source = Sinks.many().unicast().onBackpressureBuffer();
        when(adapter.stream(any(Msg.class))).thenReturn(source.asFlux());

        instance.startPhase(
                Msg.builderForRole(io.agentscope.core.message.MsgRole.USER).build());
        assertThat(instance.requestStop()).isTrue();
        source.tryEmitNext(agentEnd(null));

        ChatRunFinalizationCommand command = captureFinalize();
        assertThat(command.targetStatus()).isEqualTo(ChatRunStatus.STOPPED);
        assertThat(command.finishReason()).isEqualTo(ChatRunFinishReason.USER_STOP);
        verify(runService, times(1)).finalizeExecution(eq(run), any(ChatRunFinalizationCommand.class));
        source.tryEmitComplete();
    }

    @Test
    void shouldNotFinalizeOnChildAgentEnd() {
        instance = newInstance(run(ChatRunStatus.RUNNING, snapshot("", false, false)));
        // 子 Agent 的 AGENT_END 带 source，不得终结父 Run；流随后保持运行。
        when(adapter.stream(any(Msg.class))).thenReturn(Flux.concat(Flux.just(agentEnd("sub-agent")), Flux.never()));

        instance.startPhase(
                Msg.builderForRole(io.agentscope.core.message.MsgRole.USER).build());

        verify(runService, never()).finalizeExecution(any(), any());
        assertThat(instance.isRunning()).isTrue();
    }

    @Test
    void shouldCompleteNormallyWhenChildAgentConfirmArrives() {
        // 子 Agent 的确认事件（source != null）不构成运行级待交互：不进入待提交状态，
        // 根 AGENT_END 到达时 Run 正常终结为 COMPLETED，快照不产生待确认投影。
        instance = newInstance(run(ChatRunStatus.RUNNING, snapshot("", false, false)));
        stubTerminalCommit();
        AgentEvent childConfirm = new RequireUserConfirmEvent(
                        "child-reply",
                        List.of(ToolUseBlock.builder()
                                .id("child-call")
                                .name("demo_tool")
                                .state(ToolCallState.ASKING)
                                .build()))
                .withSource("root/worker");
        when(adapter.stream(any(Msg.class)))
                .thenReturn(Flux.concat(Flux.just(childConfirm, agentEnd(null)), Flux.never()));

        instance.startPhase(
                Msg.builderForRole(io.agentscope.core.message.MsgRole.USER).build());

        ChatRunFinalizationCommand command = captureFinalize();
        assertThat(command.targetStatus()).isEqualTo(ChatRunStatus.COMPLETED);
        assertThat(command.snapshot().pendingTools()).isEmpty();
    }

    @Test
    void shouldCompleteRunWhenSingleEventExceedsEventBufferCapacity() {
        // 单事件超过本地缓冲容量：实时推送被丢弃（快照已先行应用，重连后可补齐），Run 不被打死。
        AiProperties tinyBuffer = new AiProperties();
        tinyBuffer.getChat().getRun().setMaxBytes(1024);
        ChatRunEventStore realStore = new ChatRunEventStore(tinyBuffer);
        ChatRunEntity run = run(ChatRunStatus.RUNNING, snapshot("", false, false));
        try {
            instance = new ChatExecutionInstance(
                    runService,
                    realStore,
                    new AiProperties(),
                    scheduler,
                    workspaceAuditRecorder,
                    run,
                    session(),
                    adapter,
                    new ChatExecutionSnapshotBuilder(ChatRunSnapshotCodec.decode(run.getSnapshotJson())));
            stubTerminalCommit();
            String oversizeDelta = "x".repeat(2048);
            when(adapter.stream(any(Msg.class)))
                    .thenReturn(Flux.concat(
                            Flux.just(new TextBlockDeltaEvent("reply-1", "block-1", oversizeDelta), agentEnd(null)),
                            Flux.never()));

            instance.startPhase(
                    Msg.builderForRole(io.agentscope.core.message.MsgRole.USER).build());

            ChatRunFinalizationCommand command = captureFinalize();
            assertThat(command.targetStatus()).isEqualTo(ChatRunStatus.COMPLETED);
            // 超大增量的 Start/Content 未进事件窗口（只有终结前的 TextMessageEnd 关闭事件与终态事件），
            // 但快照完整保留文本。
            assertThat(command.snapshot().text()).isEqualTo(oversizeDelta);
            assertThat(realStore.latestCursor("run-1")).isEqualTo(2L);
        } finally {
            realStore.shutdown();
        }
    }

    @Test
    void shouldEmitCloseEventsOnceAndClosedSnapshotWhenMessagesOpen() {
        // 起始即带打开的文本/推理消息（模拟执行中产生过内容）。
        instance = newInstance(run(ChatRunStatus.RUNNING, snapshot("partial", true, true)));
        stubTerminalCommit();

        instance.finalizeCompleted();

        ChatRunFinalizationCommand command = captureFinalize();
        assertThat(command.snapshot().textOpen()).isFalse();
        assertThat(command.snapshot().reasoningOpen()).isFalse();
        // 关闭事件经快照增量一次性驱动，终态快照已闭合。
        verify(runService, times(1)).finalizeExecution(eq(instance.run()), any(ChatRunFinalizationCommand.class));
    }

    @Test
    void shouldClosePersistedSnapshotWhenInterpreterHasNoInMemoryMessages() {
        // 恢复实例：解释器为空（无内存消息 ID），但持久化快照处于打开状态。
        ChatRunSnapshot openSnapshot = new ChatRunSnapshot(
                "run-1", "agui-1", 1, "partial", "", "m-text", null, true, false, List.of(), List.of(), List.of(),
                List.of());
        ChatRunEntity run = run(ChatRunStatus.RUNNING, openSnapshot);
        instance = newInstance(run);
        stubTerminalCommit();

        instance.finalizeCompleted();

        ChatRunFinalizationCommand command = captureFinalize();
        // closeOpenMessages 无条件置 closeActiveMessages，闭合持久化快照。
        assertThat(command.snapshot().textOpen()).isFalse();
    }

    @Test
    void shouldRetryTerminalEventThroughIdempotentDatabaseFinalization() {
        ChatRunEntity run = run(ChatRunStatus.RUNNING, snapshot("partial", true, false));
        ChatRunEntity persisted = run(ChatRunStatus.COMPLETED, snapshot("partial", false, false));
        persisted.setFinishReason(ChatRunFinishReason.SUCCESS.name());
        instance = newInstance(run);
        when(runService.finalizeExecution(eq(run), any(ChatRunFinalizationCommand.class)))
                .thenReturn(
                        new ChatRunFinalizationResult(true, "COMPLETED", "SUCCESS", null, null),
                        new ChatRunFinalizationResult(false, "COMPLETED", "SUCCESS", null, null));
        when(runService.loadCurrentOrIdentity(run)).thenReturn(persisted);
        when(eventStore.appendTerminalIfAbsent(
                        anyString(), anyString(), any(AguiEvent.class), anyString(), anyString()))
                .thenThrow(new RuntimeException("event store unavailable"))
                .thenReturn(new ChatRunEvent(8L, "RUN_FINISHED", "{}"));

        instance.finalizeCompleted();

        assertThat(instance.drainedSignal().toCompletableFuture()).succeedsWithin(Duration.ofSeconds(3));
        verify(runService, times(2)).finalizeExecution(eq(run), any(ChatRunFinalizationCommand.class));
        verify(eventStore, times(2))
                .appendTerminalIfAbsent(anyString(), anyString(), any(AguiEvent.class), anyString(), anyString());
    }

    @Test
    void shouldFallbackImmediatelyWhenAttachmentProjectionCannotRecover() {
        ChatRunEntity run = run(ChatRunStatus.RUNNING, snapshot("partial", false, false));
        instance = newInstance(run);
        when(runService.finalizeExecution(
                        eq(run), argThat(command -> command.targetStatus() == ChatRunStatus.COMPLETED)))
                .thenThrow(new AiBusinessException(AiErrorCode.ATTACHMENT_NOT_FOUND, "attachment-1"));
        when(runService.finalizeExecution(eq(run), argThat(command -> command.targetStatus() == ChatRunStatus.FAILED)))
                .thenReturn(new ChatRunFinalizationResult(
                        true, ChatRunStatus.FAILED.name(), ChatRunFinishReason.ERROR.name(), "ERROR", "fallback"));
        when(eventStore.appendTerminalIfAbsent(
                        anyString(), anyString(), any(AguiEvent.class), anyString(), anyString()))
                .thenReturn(new ChatRunEvent(8L, "RUN_ERROR", "{}"));

        instance.finalizeCompleted();

        assertThat(instance.drainedSignal().toCompletableFuture()).succeedsWithin(Duration.ofSeconds(1));
        assertThat(run.getStatus()).isEqualTo(ChatRunStatus.FAILED.name());
        verify(runService, times(1))
                .finalizeExecution(eq(run), argThat(command -> command.targetStatus() == ChatRunStatus.COMPLETED));
        verify(runService, times(1))
                .finalizeExecution(
                        eq(run),
                        argThat(command -> command.targetStatus() == ChatRunStatus.FAILED
                                && command.snapshot().text().isEmpty()
                                && command.snapshot().generatedAttachmentIds().isEmpty()));
    }

    @Test
    void shouldRetryMinimalFailureFinalizationAfterTransientDatabaseFailure() {
        ChatRunEntity run = run(ChatRunStatus.RUNNING, snapshot("partial", false, false));
        instance = newInstance(run);
        when(runService.finalizeExecution(
                        eq(run), argThat(command -> command.targetStatus() == ChatRunStatus.COMPLETED)))
                .thenThrow(new AiBusinessException(AiErrorCode.ATTACHMENT_NOT_FOUND, "attachment-1"));
        when(runService.finalizeExecution(eq(run), argThat(command -> command.targetStatus() == ChatRunStatus.FAILED)))
                .thenThrow(new RuntimeException("database unavailable"))
                .thenReturn(new ChatRunFinalizationResult(
                        true, ChatRunStatus.FAILED.name(), ChatRunFinishReason.ERROR.name(), "ERROR", "fallback"));
        when(eventStore.appendTerminalIfAbsent(
                        anyString(), anyString(), any(AguiEvent.class), anyString(), anyString()))
                .thenReturn(new ChatRunEvent(8L, "RUN_ERROR", "{}"));

        instance.finalizeCompleted();

        assertThat(instance.drainedSignal().toCompletableFuture()).succeedsWithin(Duration.ofSeconds(3));
        verify(runService, times(2))
                .finalizeExecution(eq(run), argThat(command -> command.targetStatus() == ChatRunStatus.FAILED));
    }

    private void stubTerminalCommit() {
        when(runService.finalizeExecution(eq(instance.run()), any(ChatRunFinalizationCommand.class)))
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
        when(eventStore.appendTerminalIfAbsent(
                        anyString(), anyString(), any(AguiEvent.class), anyString(), anyString()))
                .thenReturn(new ChatRunEvent(8L, "RUN_FINISHED", "{}"));
    }

    private ChatRunFinalizationCommand captureFinalize() {
        ArgumentCaptor<ChatRunFinalizationCommand> captor = ArgumentCaptor.forClass(ChatRunFinalizationCommand.class);
        verify(runService).finalizeExecution(eq(instance.run()), captor.capture());
        return captor.getValue();
    }

    private ChatExecutionInstance newInstance(ChatRunEntity run) {
        ChatExecutionInstance created = new ChatExecutionInstance(
                runService,
                eventStore,
                new AiProperties(),
                scheduler,
                workspaceAuditRecorder,
                run,
                session(),
                adapter,
                new ChatExecutionSnapshotBuilder(ChatRunSnapshotCodec.decode(run.getSnapshotJson())));
        return created;
    }

    private static AgentEvent agentEnd(String source) {
        // 单参构造为根 Agent（source=null）；withSource 标记子 Agent 来源。
        AgentEvent event = new AgentEndEvent("reply-1");
        return source == null ? event : event.withSource(source);
    }

    private static ChatRunSnapshot snapshot(String text, boolean textOpen, boolean reasoningOpen) {
        return new ChatRunSnapshot(
                "run-1",
                "agui-1",
                1,
                text,
                "",
                textOpen ? "m-text" : null,
                reasoningOpen ? "m-reason" : null,
                textOpen,
                reasoningOpen,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static ChatRunEntity run(ChatRunStatus status, ChatRunSnapshot snapshot) {
        ChatRunEntity run = new ChatRunEntity();
        run.setId("run-1");
        run.setSessionId("session-1");
        run.setStatus(status.name());
        run.setPhaseNo(1);
        run.setAguiRunId("agui-1");
        run.setSnapshotJson(ChatRunSnapshotCodec.encode(snapshot));
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
