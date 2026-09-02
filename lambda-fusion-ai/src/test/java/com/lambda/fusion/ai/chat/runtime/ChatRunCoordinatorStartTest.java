package com.lambda.fusion.ai.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.apps.service.AppService;
import com.lambda.fusion.ai.chat.attachment.ChatAttachmentMessageBuilder;
import com.lambda.fusion.ai.chat.model.ChatRunFinalizationCommand;
import com.lambda.fusion.ai.chat.model.ChatRunFinalizationResult;
import com.lambda.fusion.ai.chat.model.entity.ChatMessageEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEvent;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEventStore;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshotCodec;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.chat.service.ChatMessageService;
import com.lambda.fusion.ai.chat.service.ChatRunStateService;
import com.lambda.fusion.ai.runtime.AgentFactory;
import com.lambda.fusion.ai.runtime.workspace.WorkspaceAuditRecorder;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Sinks;

/** Run 启动调度测试。 */
class ChatRunCoordinatorStartTest {

    private ChatExecutionService coordinator;

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.shutdown();
        }
    }

    /** 回归：上一 Run 已业务完成但记忆尾部未排空时，下一 Run 仍应立即订阅 AgentScope。 */
    @Test
    void shouldStartNextRunBeforePreviousSourceDrains() {
        ChatRunStateService runService = mock(ChatRunStateService.class);
        ChatRunEventStore eventStore = mock(ChatRunEventStore.class);
        ChatMessageService messageService = mock(ChatMessageService.class);
        ChatAttachmentService attachmentService = mock(ChatAttachmentService.class);
        ChatAttachmentMessageBuilder attachmentMessageBuilder = mock(ChatAttachmentMessageBuilder.class);
        AppService appService = mock(AppService.class);
        AgentFactory agentFactory = mock(AgentFactory.class);
        HarnessAgent agent = mock(HarnessAgent.class);
        AiProperties properties = new AiProperties();
        ChatExecutionInstanceFactory instanceFactory = new ChatExecutionInstanceFactory(
                runService,
                eventStore,
                agentFactory,
                mock(WorkspaceAuditRecorder.class),
                mock(ObjectProvider.class),
                properties);
        coordinator = new ChatExecutionService(
                runService,
                eventStore,
                messageService,
                attachmentService,
                attachmentMessageBuilder,
                appService,
                instanceFactory,
                properties);

        ChatSessionEntity session = session();
        ChatRunEntity first = run("run-1", 1L);
        ChatRunEntity second = run("run-2", 2L);
        ChatMessageEntity userMessage = new ChatMessageEntity();
        userMessage.setId(1L);
        userMessage.setContent("日期");

        when(messageService.findByIdAndSession(anyLong(), eq(session.getId()))).thenReturn(Optional.of(userMessage));
        when(attachmentService.listByMessageIds(anyList())).thenReturn(List.of());
        when(appService.loadById(session.getAppId())).thenReturn(new AppEntity());
        when(attachmentMessageBuilder.buildUserMsg(any(), any(), anyString(), anyList()))
                .thenReturn(new UserMessage("日期"));
        when(agentFactory.getOrBuildForChatRun(session.getAppId(), session.getTenantId()))
                .thenReturn(agent);
        when(agent.getDelegate()).thenReturn(mock(ReActAgent.class));
        when(runService.loadCurrentOrIdentity(any(ChatRunEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Sinks.Many<AgentEvent> firstSource = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<AgentEvent> secondSource = Sinks.many().unicast().onBackpressureBuffer();
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(firstSource.asFlux(), secondSource.asFlux());
        when(runService.finalizeExecution(any(ChatRunEntity.class), any(ChatRunFinalizationCommand.class)))
                .thenAnswer(invocation -> {
                    ChatRunFinalizationCommand command = invocation.getArgument(1);
                    return new ChatRunFinalizationResult(
                            true,
                            command.targetStatus().name(),
                            command.finishReason().name(),
                            null,
                            null);
                });
        when(eventStore.appendTerminalIfAbsent(
                        anyString(), anyString(), any(AguiEvent.class), anyString(), anyString()))
                .thenReturn(new ChatRunEvent(1L, "RUN_FINISHED", "{}"));

        coordinator.start(first, session);
        verify(agent, timeout(1000)).streamEvents(any(Msg.class), any(RuntimeContext.class));

        assertThat(firstSource.tryEmitNext(new AgentEndEvent("reply-1")).isSuccess())
                .isTrue();
        verify(runService, timeout(1000)).finalizeExecution(eq(first), any(ChatRunFinalizationCommand.class));
        assertThat(first.getStatus()).isEqualTo(ChatRunStatus.COMPLETED.name());

        // firstSource 未 complete，模拟 MemoryFlush / MemoryMaintenance 仍在运行。
        coordinator.start(second, session);

        verify(agent, timeout(1000).times(2)).streamEvents(any(Msg.class), any(RuntimeContext.class));
        assertThat(second.getStatus()).isEqualTo(ChatRunStatus.RUNNING.name());

        assertThat(secondSource.tryEmitNext(new AgentEndEvent("reply-2")).isSuccess())
                .isTrue();
        assertThat(firstSource.tryEmitComplete()).isEqualTo(Sinks.EmitResult.OK);
        assertThat(secondSource.tryEmitComplete()).isEqualTo(Sinks.EmitResult.OK);
        verify(runService, timeout(1000)).finalizeExecution(eq(second), any(ChatRunFinalizationCommand.class));
    }

    @Test
    void shouldConstructAgentOutsideCallingThread() throws Exception {
        ChatRunStateService runService = mock(ChatRunStateService.class);
        ChatRunEventStore eventStore = mock(ChatRunEventStore.class);
        ChatMessageService messageService = mock(ChatMessageService.class);
        ChatExecutionInstanceFactory instanceFactory = mock(ChatExecutionInstanceFactory.class);
        ChatExecutionInstance execution = mock(ChatExecutionInstance.class);
        ChatRunEntity run = run("run-1", 1L);
        ChatSessionEntity session = session();
        CountDownLatch constructionStarted = new CountDownLatch(1);
        CountDownLatch releaseConstruction = new CountDownLatch(1);
        when(execution.run()).thenReturn(run);
        when(execution.localPhaseDrainedSignal()).thenReturn(new CompletableFuture<>());
        when(runService.loadCurrentOrIdentity(run)).thenReturn(run);
        when(messageService.findByIdAndSession(run.getUserMessageId(), run.getSessionId()))
                .thenReturn(Optional.empty());
        when(instanceFactory.createAgentBacked(eq(run), eq(session), any())).thenAnswer(invocation -> {
            constructionStarted.countDown();
            releaseConstruction.await();
            return execution;
        });
        coordinator = new ChatExecutionService(
                runService,
                eventStore,
                messageService,
                mock(ChatAttachmentService.class),
                mock(ChatAttachmentMessageBuilder.class),
                mock(AppService.class),
                instanceFactory,
                new AiProperties());

        CompletableFuture<Void> caller = CompletableFuture.runAsync(() -> coordinator.start(run, session));
        try {
            assertThat(constructionStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(caller).succeedsWithin(Duration.ofSeconds(1));
        } finally {
            releaseConstruction.countDown();
        }
        verify(execution, timeout(3000)).finalizeFailed(any(), anyString());
    }

    private static ChatRunEntity run(String id, long userMessageId) {
        ChatRunEntity run = new ChatRunEntity();
        run.setId(id);
        run.setSessionId("session-1");
        run.setUserMessageId(userMessageId);
        run.setStatus(ChatRunStatus.RUNNING.name());
        run.setPhaseNo(1);
        run.setAguiRunId("agui-" + id);
        run.setSnapshotJson(ChatRunSnapshotCodec.encode(ChatRunSnapshot.empty(id, run.getAguiRunId(), 1)));
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
