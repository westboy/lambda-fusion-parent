package com.lambda.fusion.ai.chat.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.model.ConfirmToolCall;
import com.lambda.fusion.ai.chat.model.ConfirmTransition;
import com.lambda.fusion.ai.chat.model.RunContext;
import com.lambda.fusion.ai.chat.model.SendMessage;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.ChatExecutionService;
import com.lambda.fusion.ai.chat.runtime.agui.AguiBootstrapModel;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEventStore;
import com.lambda.fusion.ai.chat.service.ChatRunService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ChatServiceImplConfirmTest {

    private final ChatRunService runService = mock(ChatRunService.class);
    private final ChatExecutionService chatExecutionService = mock(ChatExecutionService.class);
    private final ChatRunEventStore eventStore = mock(ChatRunEventStore.class);
    private final AiProperties properties = new AiProperties();
    private final ChatServiceImpl chatService =
            new ChatServiceImpl(runService, chatExecutionService, eventStore, properties);

    @Test
    void shouldStartOnlyNewlyCreatedRun() {
        ChatRunEntity run = run(ChatRunStatus.RUNNING, 1);
        ChatSessionEntity session = session();
        SendMessage message = message();
        when(runService.createOrLoad("session-1", message)).thenReturn(new RunContext(run, session, true));
        when(chatExecutionService.bootstrap(run, session)).thenReturn(new AguiBootstrapModel(0L, List.of(), true));

        assertThat(chatService.streamChat("session-1", message)).isNotNull();

        verify(chatExecutionService).start(run, session);
    }

    @Test
    void shouldNotRestartIdempotentlyLoadedRun() {
        ChatRunEntity run = run(ChatRunStatus.RUNNING, 1);
        ChatSessionEntity session = session();
        SendMessage message = message();
        when(runService.createOrLoad("session-1", message)).thenReturn(new RunContext(run, session, false));
        when(chatExecutionService.bootstrap(run, session)).thenReturn(new AguiBootstrapModel(0L, List.of(), true));

        assertThat(chatService.streamChat("session-1", message)).isNotNull();

        verify(chatExecutionService, never()).start(any(), any());
    }

    @Test
    void shouldPropagateWhenConfirmThrows() {
        ChatRunEntity run = run(ChatRunStatus.RUNNING, 2);
        ChatSessionEntity session = session();
        ConfirmToolCall command = command(2, List.of(decision("call_1", true)));

        when(runService.loadOwned("session-1", "run-1")).thenReturn(new RunContext(run, session));
        when(chatExecutionService.confirm(run, session, command))
                .thenThrow(new IllegalStateException("context unavailable"));

        assertThatThrownBy(() -> chatService.confirm("session-1", "run-1", command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("context unavailable");
    }

    @Test
    void shouldOpenRunEventStreamWithAfterSeqZeroAndBootstrap() {
        ChatRunEntity run = run(ChatRunStatus.RUNNING, 2);
        ChatSessionEntity session = session();
        ConfirmToolCall command = command(2, List.of(decision("call_1", true)));
        ChatRunEntity transitioned = run(ChatRunStatus.RUNNING, 3);

        when(runService.loadOwned("session-1", "run-1")).thenReturn(new RunContext(run, session));
        when(chatExecutionService.confirm(run, session, command))
                .thenReturn(new ConfirmTransition(transitioned, session, true));
        when(chatExecutionService.bootstrap(any(), any())).thenReturn(new AguiBootstrapModel(0L, List.of(), true));

        SseEmitter emitter = chatService.confirm("session-1", "run-1", command);

        assertThat(emitter).isNotNull();
        verify(chatExecutionService).bootstrap(transitioned, session);
    }

    @Test
    void shouldOpenStreamFromTransitionedRunEvenWhenIdempotent() {
        ChatRunEntity run = run(ChatRunStatus.RUNNING, 2);
        ChatSessionEntity session = session();
        ConfirmToolCall command = command(2, List.of(decision("call_1", true)));
        ChatRunEntity transitioned = run(ChatRunStatus.RUNNING, 3);

        when(runService.loadOwned("session-1", "run-1")).thenReturn(new RunContext(run, session));
        when(chatExecutionService.confirm(run, session, command))
                .thenReturn(new ConfirmTransition(transitioned, session, false));
        when(chatExecutionService.bootstrap(any(), any())).thenReturn(new AguiBootstrapModel(0L, List.of(), false));
        when(eventStore.subscribe(any(), anyLong(), any(), any())).thenReturn(mock());

        SseEmitter emitter = chatService.confirm("session-1", "run-1", command);

        assertThat(emitter).isNotNull();
        verify(chatExecutionService).bootstrap(transitioned, session);
    }

    @Test
    void shouldFallBackToPersistedBootstrapWhenRealtimeEventsAreNotLocal() {
        ChatRunEntity run = run(ChatRunStatus.RUNNING, 2);
        ChatSessionEntity session = session();
        when(runService.loadOwned("session-1", "run-1")).thenReturn(new RunContext(run, session));
        when(eventStore.subscribe(any(), anyLong(), any(), any()))
                .thenThrow(new AiBusinessException(AiErrorCode.CHAT_RUN_EVENTS_EXPIRED, "run-1"));
        when(chatExecutionService.bootstrap(run, session)).thenReturn(new AguiBootstrapModel(7L, List.of(), true));

        SseEmitter emitter = chatService.resume("session-1", "run-1");

        assertThat(emitter).isNotNull();
        verify(chatExecutionService).bootstrap(run, session);
    }

    private static ChatRunEntity run(ChatRunStatus status, int phaseNo) {
        ChatRunEntity run = new ChatRunEntity();
        run.setId("run-1");
        run.setSessionId("session-1");
        run.setStatus(status.name());
        run.setPhaseNo(phaseNo);
        return run;
    }

    private static ChatSessionEntity session() {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        session.setAppId("app-1");
        return session;
    }

    private static ConfirmToolCall command(int phaseNo, List<ConfirmToolCall.Decision> decisions) {
        ConfirmToolCall command = new ConfirmToolCall();
        command.setPhaseNo(phaseNo);
        command.setDecisions(decisions);
        return command;
    }

    private static SendMessage message() {
        SendMessage message = new SendMessage();
        message.setClientRequestId("request-1");
        message.setContent("hello");
        return message;
    }

    private static ConfirmToolCall.Decision decision(String toolCallId, boolean confirmed) {
        ConfirmToolCall.Decision decision = new ConfirmToolCall.Decision();
        decision.setToolCallId(toolCallId);
        decision.setConfirmed(confirmed);
        return decision;
    }
}
