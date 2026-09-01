package com.lambda.fusion.ai.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.model.ChatRunFinalizationCommand;
import com.lambda.fusion.ai.chat.model.ChatRunFinalizationResult;
import com.lambda.fusion.ai.chat.model.ConfirmToolCall;
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
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 执行实例排空语义测试：固定「业务终态不提前取消源流、最终源流排空后完成 drainedSignal」、
 * 「Workspace 审计移到源流终止后、用 phase 起始时刻」、「记忆尾部失败不改 COMPLETED」等目标语义。
 *
 * @author Jin
 */
class ChatRunInstanceDrainTest {

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
    void shouldKeepDrainedOpenUntilSourceTerminatesAfterBusinessTerminal() {
        instance = newInstance(run(ChatRunStatus.RUNNING));
        stubTerminalCommit();
        // 源流：先发根 AGENT_END，随后保持打开（模拟记忆尾部），由测试手动 complete。
        Sinks.Many<AgentEvent> source = Sinks.many().unicast().onBackpressureBuffer();
        when(adapter.stream(any(Msg.class))).thenReturn(source.asFlux());

        long beforeStart = System.currentTimeMillis();
        instance.startPhase(userMsg());
        source.tryEmitNext(agentEnd());

        // 根 AGENT_END：业务终态已提交，但源流未终止，drainedSignal 不应完成，且未审计。
        verify(runService).finalizeExecution(eq(instance.run()), any(ChatRunFinalizationCommand.class));
        assertThat(instance.drainedSignal().toCompletableFuture()).isNotDone();
        verify(workspaceAuditRecorder, never()).recordChanges(any(), anyLong());

        // 源流终止（含记忆尾部结束）：完成 drainedSignal，并在此时执行审计，审计下界 ≥ startPhase 时刻。
        source.tryEmitComplete();
        assertThat(instance.drainedSignal().toCompletableFuture()).succeedsWithin(Duration.ofSeconds(2));
        ArgumentCaptor<Long> auditSince = ArgumentCaptor.forClass(Long.class);
        verify(workspaceAuditRecorder).recordChanges(eq(instance.session()), auditSince.capture());
        assertThat(auditSince.getValue()).isGreaterThanOrEqualTo(beforeStart);
    }

    @Test
    void shouldKeepCompletedWhenPostProcessingTailFails() {
        instance = newInstance(run(ChatRunStatus.RUNNING));
        stubTerminalCommit();
        Sinks.Many<AgentEvent> source = Sinks.many().unicast().onBackpressureBuffer();
        when(adapter.stream(any(Msg.class))).thenReturn(source.asFlux());

        instance.startPhase(userMsg());
        source.tryEmitNext(agentEnd());

        // 业务终态后的记忆/维护尾部失败：Run 保持 COMPLETED，不改为 FAILED；源流终止仍完成排空。
        source.tryEmitError(new RuntimeException("memory flush failed"));
        assertThat(instance.drainedSignal().toCompletableFuture()).succeedsWithin(Duration.ofSeconds(2));
        assertThat(instance.run().getStatus()).isEqualTo(ChatRunStatus.COMPLETED.name());
    }

    @Test
    void shouldCompleteDrainImmediatelyWhenFinalizingWithoutActiveSource() {
        // 纯终结恢复实例（无源流）：finalize 后 drainedSignal 应立即完成，供协调器摘除。
        instance = newInstance(run(ChatRunStatus.RUNNING));
        stubTerminalCommit();

        instance.finalizeCompleted();

        assertThat(instance.drainedSignal().toCompletableFuture()).isDone();
    }

    @Test
    void shouldExposeAwaitingConfirmationOnlyAfterSourceDrains() {
        instance = newInstance(run(ChatRunStatus.RUNNING));
        Sinks.Many<AgentEvent> source = Sinks.many().unicast().onBackpressureBuffer();
        when(adapter.stream(any(Msg.class))).thenReturn(source.asFlux());
        when(runService.checkpoint(eq(instance.run()), any(ChatRunSnapshot.class)))
                .thenReturn(true);

        instance.startPhase(userMsg());
        source.tryEmitNext(requireConfirm("call-1"));
        source.tryEmitNext(agentEnd());

        // 根 AGENT_END 后源流仍保持打开（模拟 MemoryFlush 尾部）：Run 仍是 RUNNING，提前确认必须拒绝且不暂存。
        assertThat(instance.run().getStatus()).isEqualTo(ChatRunStatus.RUNNING.name());
        assertThatThrownBy(() -> instance.confirm(command(1)))
                .isInstanceOf(AiBusinessException.class)
                .satisfies(error -> assertThat(((AiBusinessException) error).getCode())
                        .isEqualTo(AiErrorCode.CHAT_RUN_STATE_CONFLICT.getCode()));
        verify(runService, never()).advanceConfirmation(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(runService, never()).checkpoint(any(), any());

        // 整个 AgentScope/Harness 源流排空后才提交待确认事实并发布中断事件。
        source.tryEmitComplete();

        assertThat(instance.run().getStatus()).isEqualTo(ChatRunStatus.RUNNING.name());
        assertThat(instance.bootstrap().phaseClosed()).isTrue();
        assertThat(instance.drainedSignal().toCompletableFuture()).isNotDone();
        verify(runService).checkpoint(eq(instance.run()), any(ChatRunSnapshot.class));
        verify(workspaceAuditRecorder).recordChanges(eq(instance.session()), anyLong());
    }

    @Test
    void shouldKeepPersistedConfirmationWhenLocalInterruptPublishFails() {
        instance = newInstance(run(ChatRunStatus.RUNNING));
        Sinks.Many<AgentEvent> source = Sinks.many().unicast().onBackpressureBuffer();
        when(adapter.stream(any(Msg.class))).thenReturn(source.asFlux());
        when(runService.checkpoint(eq(instance.run()), any(ChatRunSnapshot.class)))
                .thenReturn(true);
        doThrow(new IllegalStateException("local buffer unavailable"))
                .when(eventStore)
                .appendAll(anyString(), anyString(), any());

        instance.startPhase(userMsg());
        source.tryEmitNext(requireConfirm("call-1"));
        source.tryEmitNext(agentEnd());
        source.tryEmitComplete();

        assertThat(instance.run().getStatus()).isEqualTo(ChatRunStatus.RUNNING.name());
        assertThat(instance.bootstrap().phaseClosed()).isTrue();
        assertThat(instance.drainedSignal().toCompletableFuture()).isNotDone();
        verify(runService, never()).finalizeExecution(any(), any());
    }

    @Test
    void shouldCheckpointDirtySnapshotFromInstanceOwnedTimer() {
        AiProperties properties = new AiProperties();
        properties.getChat().getRun().setSnapshotIntervalSeconds(1);
        instance = newInstance(run(ChatRunStatus.RUNNING), properties);
        Sinks.Many<AgentEvent> source = Sinks.many().unicast().onBackpressureBuffer();
        when(adapter.stream(any(Msg.class))).thenReturn(source.asFlux());
        when(runService.checkpoint(eq(instance.run()), any(ChatRunSnapshot.class)))
                .thenReturn(true);

        instance.startPhase(userMsg());
        source.tryEmitNext(new TextBlockDeltaEvent("reply-1", "block-1", "partial"));

        verify(runService, timeout(2_500)).checkpoint(eq(instance.run()), any(ChatRunSnapshot.class));
    }

    @Test
    void shouldInterruptAndDisposeSourceAfterInteractionTimeout() throws Exception {
        AiProperties properties = new AiProperties();
        properties.getChat().getRun().setMaxRunDurationSeconds(0);
        properties.getChat().getRun().setStopGraceSeconds(1);
        instance = newInstance(run(ChatRunStatus.RUNNING), properties);
        stubTerminalCommit();
        CountDownLatch cancelled = new CountDownLatch(1);
        when(adapter.stream(any(Msg.class))).thenReturn(Flux.<AgentEvent>never().doOnCancel(cancelled::countDown));

        instance.startPhase(userMsg());

        verify(adapter, timeout(2_000)).interrupt();
        assertThat(cancelled.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(instance.run().getStatus()).isEqualTo(ChatRunStatus.FAILED.name());
        assertThat(instance.drainedSignal().toCompletableFuture()).succeedsWithin(Duration.ofSeconds(2));
    }

    private static ToolUseBlock askingBlock(String toolCallId) {
        return ToolUseBlock.builder()
                .id(toolCallId)
                .name("demo_tool")
                .state(ToolCallState.ASKING)
                .build();
    }

    private static AgentEvent requireConfirm(String toolCallId) {
        return new RequireUserConfirmEvent("reply-1", List.of(askingBlock(toolCallId)));
    }

    private static ConfirmToolCall command(int phaseNo) {
        ConfirmToolCall command = new ConfirmToolCall();
        command.setPhaseNo(phaseNo);
        ConfirmToolCall.Decision decision = new ConfirmToolCall.Decision();
        decision.setToolCallId("call-1");
        decision.setConfirmed(true);
        command.setDecisions(List.of(decision));
        return command;
    }

    private void stubTerminalCommit() {
        lenient()
                .when(runService.finalizeExecution(eq(instance.run()), any(ChatRunFinalizationCommand.class)))
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
                .when(eventStore.appendTerminalIfAbsent(
                        anyString(), anyString(), any(AguiEvent.class), anyString(), anyString()))
                .thenReturn(new ChatRunEvent(8L, "RUN_FINISHED", "{}"));
        lenient().when(eventStore.latestCursor(anyString())).thenReturn(1L);
    }

    private ChatExecutionInstance newInstance(ChatRunEntity run) {
        return newInstance(run, new AiProperties());
    }

    private ChatExecutionInstance newInstance(ChatRunEntity run, AiProperties properties) {
        return new ChatExecutionInstance(
                runService,
                eventStore,
                properties,
                scheduler,
                workspaceAuditRecorder,
                run,
                session(),
                adapter,
                new ChatExecutionSnapshotBuilder(ChatRunSnapshotCodec.decode(run.getSnapshotJson())));
    }

    private static AgentEvent agentEnd() {
        return new AgentEndEvent("reply-1");
    }

    private static Msg userMsg() {
        return Msg.builderForRole(MsgRole.USER).textContent("hi").build();
    }

    private static ChatRunEntity run(ChatRunStatus status) {
        ChatRunEntity run = new ChatRunEntity();
        run.setId("run-1");
        run.setSessionId("session-1");
        run.setStatus(status.name());
        run.setPhaseNo(1);
        run.setAguiRunId("agui-1");
        run.setSnapshotJson(ChatRunSnapshotCodec.encode(ChatRunSnapshot.empty("run-1", "agui-1", 1)));
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
