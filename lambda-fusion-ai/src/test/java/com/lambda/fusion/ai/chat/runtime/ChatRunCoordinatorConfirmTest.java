package com.lambda.fusion.ai.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.service.AppService;
import com.lambda.fusion.ai.chat.model.ConfirmToolCall;
import com.lambda.fusion.ai.chat.model.ConfirmTransition;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEventStore;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.chat.service.ChatMessageService;
import com.lambda.fusion.ai.chat.service.ChatRunStateService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.runtime.AgentFactory;
import com.lambda.fusion.ai.runtime.workspace.WorkspaceAuditRecorder;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

class ChatRunCoordinatorConfirmTest {

    private ChatRunStateService runService;
    private ChatRunEventStore eventStore;
    private AgentFactory agentFactory;
    private ChatRunCoordinator coordinator;
    private ChatRunInstanceFactory instanceFactory;
    private HarnessAgent agent;
    private ReActAgent delegate;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @BeforeEach
    void setUp() {
        runService = mock(ChatRunStateService.class);
        eventStore = mock(ChatRunEventStore.class);
        agentFactory = mock(AgentFactory.class);
        agent = mock(HarnessAgent.class);
        delegate = mock(ReActAgent.class);
        when(agent.getDelegate()).thenReturn(delegate);
        when(agentFactory.getOrBuildForChatRun(anyString(), anyString())).thenReturn(agent);
        AiProperties properties = new AiProperties();
        instanceFactory = new ChatRunInstanceFactory(
                runService,
                eventStore,
                agentFactory,
                mock(WorkspaceAuditRecorder.class),
                mock(ObjectProvider.class),
                properties);
        coordinator = new ChatRunCoordinator(
                runService,
                eventStore,
                mock(ChatMessageService.class),
                mock(ChatAttachmentService.class),
                null,
                mock(AppService.class),
                instanceFactory,
                properties);
    }

    @AfterEach
    void tearDown() {
        coordinator.shutdown();
        scheduler.shutdownNow();
    }

    @Test
    void shouldRehydratePausedConfirmationAfterRestart() {
        ChatRunEntity run = awaitingRun(2);
        ChatSessionEntity session = session();
        when(delegate.getAgentState("user-1", "session-1")).thenReturn(stateWithAskingBlock("call_1"));
        stubResumed(run, 2);
        when(agent.streamEvents(any(Msg.class), any())).thenReturn(Flux.never());

        ConfirmTransition transition = coordinator.confirm(run, session, command(2, List.of(decision("call_1", true))));

        assertThat(transition.resumed()).isTrue();
        verify(delegate).getAgentState("user-1", "session-1");
        verify(agent).streamEvents(any(Msg.class), any());
    }

    @Test
    void shouldRejectRestartConfirmationWithoutPersistedPendingTools() {
        ChatRunEntity run = awaitingRun(2, List.of());

        assertThatThrownBy(() -> coordinator.confirm(run, session(), command(2, List.of(decision("call_1", true)))))
                .isInstanceOf(AiBusinessException.class)
                .satisfies(error -> assertThat(((AiBusinessException) error).getCode())
                        .isEqualTo(AiErrorCode.CHAT_RUN_STATE_CONFLICT.getCode()));
        verify(delegate, never()).getAgentState(any(), any());
    }

    @Test
    void shouldThrowUnavailableWhenStateStoreThrows() {
        ChatRunEntity run = awaitingRun(2);
        ChatSessionEntity session = session();
        ConfirmToolCall command = command(2, List.of(decision("call_1", true)));

        when(delegate.getAgentState("user-1", "session-1")).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> execution(run, session).confirm(command))
                .isInstanceOf(AiBusinessException.class)
                .satisfies(e -> assertThat(((AiBusinessException) e).getCode())
                        .isEqualTo(AiErrorCode.CHAT_RUN_CONFIRM_CONTEXT_UNAVAILABLE.getCode()));
        verify(runService, never()).advanceConfirmation(any(), any(), anyInt(), any());
    }

    @Test
    void shouldThrowUnavailableWhenStateOrContextIsNull() {
        ChatRunEntity run = awaitingRun(2);
        ChatSessionEntity session = session();
        ConfirmToolCall command = command(2, List.of(decision("call_1", true)));

        when(delegate.getAgentState("user-1", "session-1")).thenReturn(null);

        assertThatThrownBy(() -> execution(run, session).confirm(command))
                .isInstanceOf(AiBusinessException.class)
                .satisfies(e -> assertThat(((AiBusinessException) e).getCode())
                        .isEqualTo(AiErrorCode.CHAT_RUN_CONFIRM_CONTEXT_UNAVAILABLE.getCode()));
        verify(runService, never()).advanceConfirmation(any(), any(), anyInt(), any());
    }

    @Test
    void shouldThrowUnavailableWhenNoAskingBlockExists() {
        ChatRunEntity run = awaitingRun(2);
        ChatSessionEntity session = session();
        ConfirmToolCall command = command(2, List.of(decision("call_1", true)));

        AgentState state = stateWithToolBlocks(List.of());
        when(delegate.getAgentState("user-1", "session-1")).thenReturn(state);

        assertThatThrownBy(() -> execution(run, session).confirm(command))
                .isInstanceOf(AiBusinessException.class)
                .satisfies(e -> assertThat(((AiBusinessException) e).getCode())
                        .isEqualTo(AiErrorCode.CHAT_RUN_CONFIRM_CONTEXT_UNAVAILABLE.getCode()));
        verify(runService, never()).advanceConfirmation(any(), any(), anyInt(), any());
    }

    @Test
    void shouldThrowMismatchWhenSnapshotAndAgentIdsDiffer() {
        ChatRunEntity run = awaitingRun(2, List.of("call_1"));
        ChatSessionEntity session = session();
        ConfirmToolCall command = command(2, List.of(decision("call_1", true)));

        AgentState state = stateWithAskingBlock("call_2");
        when(delegate.getAgentState("user-1", "session-1")).thenReturn(state);

        assertThatThrownBy(() -> execution(run, session).confirm(command))
                .isInstanceOf(AiBusinessException.class)
                .satisfies(e -> assertThat(((AiBusinessException) e).getCode())
                        .isEqualTo(AiErrorCode.CHAT_RUN_CONFIRM_CONTEXT_MISMATCH.getCode()));
        verify(runService, never()).advanceConfirmation(any(), any(), anyInt(), any());
    }

    @Test
    void shouldThrowInvalidParameterWhenDecisionsAreDuplicated() {
        ChatRunEntity run = awaitingRun(2, List.of("call_1"));
        ChatSessionEntity session = session();
        ConfirmToolCall command = command(2, List.of(decision("call_1", true), decision("call_1", false)));

        assertThatThrownBy(() -> execution(run, session).confirm(command))
                .isInstanceOf(AiBusinessException.class)
                .satisfies(e -> assertThat(((AiBusinessException) e).getCode())
                        .isEqualTo(AiErrorCode.INVALID_PARAMETER.getCode()));
        verify(runService, never()).advanceConfirmation(any(), any(), anyInt(), any());
    }

    @Test
    void shouldThrowMismatchWhenDecisionsAreIncomplete() {
        ChatRunEntity run = awaitingRun(2, List.of("call_1", "call_2"));
        ChatSessionEntity session = session();
        ConfirmToolCall command = command(2, List.of(decision("call_1", true)));

        AgentState state = stateWithAskingBlocks(List.of("call_1", "call_2"));
        when(delegate.getAgentState("user-1", "session-1")).thenReturn(state);

        assertThatThrownBy(() -> execution(run, session).confirm(command))
                .isInstanceOf(AiBusinessException.class)
                .satisfies(e -> assertThat(((AiBusinessException) e).getCode())
                        .isEqualTo(AiErrorCode.CHAT_RUN_CONFIRM_CONTEXT_MISMATCH.getCode()));
        verify(runService, never()).advanceConfirmation(any(), any(), anyInt(), any());
    }

    @Test
    void shouldStartNextPhaseWithResultsInDecisionOrder() {
        ChatRunEntity run = awaitingRun(2, List.of("call_1", "call_2"));
        ChatSessionEntity session = session();
        ConfirmToolCall command = command(2, List.of(decision("call_2", false), decision("call_1", true)));

        AgentState state = stateWithAskingBlocks(List.of("call_1", "call_2"));
        when(delegate.getAgentState("user-1", "session-1")).thenReturn(state);
        stubResumed(run, 2);
        when(agent.streamEvents(any(Msg.class), any())).thenReturn(Flux.never());

        ConfirmTransition transition = execution(run, session).confirm(command);

        assertThat(transition.resumed()).isTrue();
        ArgumentCaptor<Msg> captor = ArgumentCaptor.forClass(Msg.class);
        verify(agent).streamEvents(captor.capture(), any());
        @SuppressWarnings("unchecked")
        List<ConfirmResult> results =
                (List<ConfirmResult>) captor.getValue().getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).getToolCall().getId()).isEqualTo("call_2");
        assertThat(results.get(0).isConfirmed()).isFalse();
        assertThat(results.get(1).getToolCall().getId()).isEqualTo("call_1");
        assertThat(results.get(1).isConfirmed()).isTrue();
    }

    @Test
    void shouldNotStartPhaseWhenConfirmationIsReplayed() {
        ChatRunEntity run = awaitingRun(3, List.of("call_2"));
        ChatSessionEntity session = session();
        ConfirmToolCall command = command(2, List.of(decision("call_1", true)));

        ConfirmTransition transition = execution(run, session).confirm(command);

        assertThat(transition.resumed()).isFalse();
        verify(delegate, never()).getAgentState(any(), any());
        verify(runService, never()).advanceConfirmation(any(), any(), anyInt(), any());
        verify(agent, never()).streamEvents(any(Msg.class), any());
    }

    /** 相位交接时旧阶段句柄永不排空：等待必须有界，超时按状态冲突快速失败而不是挂住请求线程。 */
    @Test
    void shouldFailFastWhenOldPhaseNeverDrainsDuringHandover() {
        AiProperties properties = new AiProperties();
        properties.getChat().getRun().setStopGraceSeconds(1);
        ChatRunInstanceFactory mockFactory = mock(ChatRunInstanceFactory.class);
        ChatRunCoordinator fastCoordinator = new ChatRunCoordinator(
                runService,
                eventStore,
                mock(ChatMessageService.class),
                mock(ChatAttachmentService.class),
                mock(com.lambda.fusion.ai.chat.attachment.ChatAttachmentMessageBuilder.class),
                mock(AppService.class),
                mockFactory,
                properties);
        try {
            ChatSessionEntity session = session();
            ChatRunEntity oldRun = awaitingRun(1);
            ChatRunInstance oldExecution = mock(ChatRunInstance.class);
            when(oldExecution.run()).thenReturn(oldRun);
            when(oldExecution.localPhaseDrainedSignal()).thenReturn(new CompletableFuture<>());
            ChatRunInstance candidate = mock(ChatRunInstance.class);
            when(candidate.run()).thenReturn(awaitingRun(2));
            when(mockFactory.createAgentBacked(any(), any(), any())).thenReturn(oldExecution, candidate);
            // 旧阶段句柄注册完成后才放行 confirm，保证交接确定性。
            CountDownLatch oldPhaseRegistered = new CountDownLatch(1);
            when(runService.loadCurrentOrIdentity(oldRun)).thenAnswer(invocation -> {
                oldPhaseRegistered.countDown();
                return oldRun;
            });
            fastCoordinator.start(oldRun, session);
            assertThat(oldPhaseRegistered.await(3, TimeUnit.SECONDS)).isTrue();

            long startedNanos = System.nanoTime();
            assertThatThrownBy(() -> fastCoordinator.confirm(
                            awaitingRun(2), session, command(2, List.of(decision("call_1", true)))))
                    .isInstanceOf(AiBusinessException.class)
                    .satisfies(error -> assertThat(((AiBusinessException) error).getCode())
                            .isEqualTo(AiErrorCode.CHAT_RUN_STATE_CONFLICT.getCode()));
            assertThat(Duration.ofNanos(System.nanoTime() - startedNanos)).isLessThan(Duration.ofSeconds(10));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } finally {
            fastCoordinator.shutdown();
        }
    }

    /** 复配等待主导者确认结果的等待必须有界：主导者卡死时追随者按状态冲突快速失败。 */
    @Test
    void shouldFailFastWhenLeaderResumeNeverCompletes() {
        AiProperties properties = new AiProperties();
        properties.getChat().getRun().setStopGraceSeconds(1);
        ChatRunInstanceFactory mockFactory = mock(ChatRunInstanceFactory.class);
        ChatRunCoordinator fastCoordinator = new ChatRunCoordinator(
                runService,
                eventStore,
                mock(ChatMessageService.class),
                mock(ChatAttachmentService.class),
                mock(com.lambda.fusion.ai.chat.attachment.ChatAttachmentMessageBuilder.class),
                mock(AppService.class),
                mockFactory,
                properties);
        try {
            ChatSessionEntity session = session();
            CountDownLatch leaderConfirmStarted = new CountDownLatch(1);
            CountDownLatch releaseLeader = new CountDownLatch(1);
            ChatRunInstance leader = mock(ChatRunInstance.class);
            when(leader.run()).thenReturn(awaitingRun(2));
            when(leader.localPhaseDrainedSignal()).thenReturn(new CompletableFuture<>());
            when(leader.confirm(any())).thenAnswer(invocation -> {
                leaderConfirmStarted.countDown();
                // 主导者迁移永不返回（模拟卡死）。
                releaseLeader.await(10, TimeUnit.SECONDS);
                return new ConfirmTransition(awaitingRun(3), session, true);
            });
            ChatRunInstance followerCandidate = mock(ChatRunInstance.class);
            when(followerCandidate.run()).thenReturn(awaitingRun(2));
            when(mockFactory.createAgentBacked(any(), any(), any())).thenReturn(leader, followerCandidate);

            CompletableFuture<Void> leaderCall = CompletableFuture.runAsync(() ->
                    fastCoordinator.confirm(awaitingRun(2), session, command(2, List.of(decision("call_1", true)))));
            assertThat(leaderConfirmStarted.await(3, TimeUnit.SECONDS)).isTrue();

            long startedNanos = System.nanoTime();
            assertThatThrownBy(() -> fastCoordinator.confirm(
                            awaitingRun(2), session, command(2, List.of(decision("call_1", true)))))
                    .isInstanceOf(AiBusinessException.class)
                    .satisfies(error -> assertThat(((AiBusinessException) error).getCode())
                            .isEqualTo(AiErrorCode.CHAT_RUN_STATE_CONFLICT.getCode()));
            assertThat(Duration.ofNanos(System.nanoTime() - startedNanos)).isLessThan(Duration.ofSeconds(10));

            releaseLeader.countDown();
            leaderCall.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } finally {
            fastCoordinator.shutdown();
        }
    }

    /** 回归：旧消息残留的 ASKING 不计入当前确认批次，三方校验以最后一条助手消息为准。 */
    @Test
    void shouldConfirmWhenEarlierMessageHasStaleAskingBlock() {
        ChatRunEntity run = awaitingRun(2, List.of("call_2"));
        ChatSessionEntity session = session();
        ConfirmToolCall command = command(2, List.of(decision("call_2", true)));

        when(delegate.getAgentState("user-1", "session-1")).thenReturn(stateWithStaleThenCurrentAsking());
        stubResumed(run, 2);
        when(agent.streamEvents(any(Msg.class), any())).thenReturn(Flux.never());

        ConfirmTransition transition = execution(run, session).confirm(command);

        assertThat(transition.resumed()).isTrue();
        verify(agent).streamEvents(any(Msg.class), any());
    }

    /** CAS 推进成功：刷新内存实体为新阶段并返回 resumed=true。 */
    private void stubResumed(ChatRunEntity run, int sourcePhaseNo) {
        when(runService.advanceConfirmation(eq(run), any(ChatSessionEntity.class), eq(sourcePhaseNo), any()))
                .thenAnswer(invocation -> {
                    run.setStatus(ChatRunStatus.RUNNING.name());
                    run.setPhaseNo(sourcePhaseNo + 1);
                    run.setAguiRunId("agui-next");
                    return new ConfirmTransition(run, session(), true);
                });
    }

    private ChatRunInstance execution(ChatRunEntity run, ChatSessionEntity session) {
        return instanceFactory.createAgentBacked(run, session, scheduler);
    }

    private static ChatRunEntity awaitingRun(int phaseNo) {
        return awaitingRun(phaseNo, List.of("call_1"));
    }

    private static ChatRunEntity awaitingRun(int phaseNo, List<String> pendingIds) {
        ChatRunEntity run = new ChatRunEntity();
        run.setId("run-1");
        run.setSessionId("session-1");
        run.setStatus(ChatRunStatus.RUNNING.name());
        run.setPhaseNo(phaseNo);
        run.setAguiRunId("phase-1");
        List<ChatRunSnapshot.ToolCall> pendingTools = pendingIds.stream()
                .map(id -> new ChatRunSnapshot.ToolCall(id, "demo", "", "", "asking"))
                .toList();
        ChatRunSnapshot snapshot = new ChatRunSnapshot(
                "run-1",
                "phase-1",
                phaseNo,
                "",
                "",
                null,
                null,
                false,
                false,
                List.of(),
                pendingTools,
                List.of(),
                List.of());
        run.setSnapshotJson(io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(snapshot));
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

    private static ConfirmToolCall command(int phaseNo, List<ConfirmToolCall.Decision> decisions) {
        ConfirmToolCall command = new ConfirmToolCall();
        command.setPhaseNo(phaseNo);
        command.setDecisions(decisions);
        return command;
    }

    private static ConfirmToolCall.Decision decision(String toolCallId, boolean confirmed) {
        ConfirmToolCall.Decision decision = new ConfirmToolCall.Decision();
        decision.setToolCallId(toolCallId);
        decision.setConfirmed(confirmed);
        return decision;
    }

    private static AgentState stateWithAskingBlock(String toolCallId) {
        return stateWithAskingBlocks(List.of(toolCallId));
    }

    private static AgentState stateWithAskingBlocks(List<String> toolCallIds) {
        return stateWithToolBlocks(toolCallIds.stream()
                .map(ChatRunCoordinatorConfirmTest::askingBlock)
                .toList());
    }

    private static AgentState stateWithToolBlocks(List<ToolUseBlock> blocks) {
        Msg assistant = Msg.builderForRole(MsgRole.ASSISTANT)
                .content(new ArrayList<>(blocks))
                .build();
        return AgentState.builder().context(List.of(assistant)).build();
    }

    /** 上下文含多条助手消息：旧消息残留 call_1 的 ASKING，最后一条为当前批次 call_2。 */
    private static AgentState stateWithStaleThenCurrentAsking() {
        Msg stale = Msg.builderForRole(MsgRole.ASSISTANT)
                .content(new ArrayList<>(List.of(askingBlock("call_1"))))
                .build();
        Msg current = Msg.builderForRole(MsgRole.ASSISTANT)
                .content(new ArrayList<>(List.of(askingBlock("call_2"))))
                .build();
        return AgentState.builder().context(List.of(stale, current)).build();
    }

    private static ToolUseBlock askingBlock(String toolCallId) {
        return ToolUseBlock.builder()
                .id(toolCallId)
                .name("demo_tool")
                .state(ToolCallState.ASKING)
                .build();
    }
}
