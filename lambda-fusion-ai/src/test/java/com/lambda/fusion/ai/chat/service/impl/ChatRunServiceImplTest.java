package com.lambda.fusion.ai.chat.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lambda.fusion.ai.AiConstants.ChatRunFailureCode;
import com.lambda.fusion.ai.AiConstants.ChatRunFinishReason;
import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.chat.mapper.ChatRunMapper;
import com.lambda.fusion.ai.chat.mapper.ChatSessionMapper;
import com.lambda.fusion.ai.chat.model.ChatRunFinalizationCommand;
import com.lambda.fusion.ai.chat.model.ConfirmTransition;
import com.lambda.fusion.ai.chat.model.RunContext;
import com.lambda.fusion.ai.chat.model.SendMessage;
import com.lambda.fusion.ai.chat.model.entity.ChatMessageEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.chat.service.ChatMessageService;
import com.lambda.fusion.ai.chat.service.ChatSessionService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("unchecked")
class ChatRunServiceImplTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        initializeTable(ChatRunMapper.class.getName(), ChatRunEntity.class);
        initializeTable(ChatSessionMapper.class.getName(), ChatSessionEntity.class);
    }

    private static void initializeTable(String namespace, Class<?> entityType) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        assistant.setCurrentNamespace(namespace);
        TableInfoHelper.initTableInfo(assistant, entityType);
    }

    private final ChatRunMapper runMapper = mock(ChatRunMapper.class);
    private final ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
    private final ChatSessionService sessionService = mock(ChatSessionService.class);
    private final ChatMessageService messageService = mock(ChatMessageService.class);
    private final ChatAttachmentService attachmentService = mock(ChatAttachmentService.class);
    private final ChatRunServiceImpl service =
            new ChatRunServiceImpl(runMapper, sessionMapper, sessionService, messageService, attachmentService);

    @Test
    void shouldCreateRunningRunOnceAndMarkIdempotentReplayAsLoaded() {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        session.setAppId("app-1");
        SendMessage message = new SendMessage();
        message.setClientRequestId("request-1");
        message.setContent("hello");
        ChatMessageEntity userMessage = new ChatMessageEntity();
        userMessage.setId(9L);
        AtomicReference<ChatRunEntity> inserted = new AtomicReference<>();

        when(sessionService.loadOwnedForUpdate("session-1")).thenReturn(session);
        when(runMapper.selectOne(any())).thenAnswer(ignored -> inserted.get());
        when(messageService.saveUserMessage(session, "hello")).thenReturn(userMessage);
        when(runMapper.insert(any(ChatRunEntity.class))).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        when(runMapper.selectById(any())).thenAnswer(ignored -> inserted.get());

        RunContext created = service.createOrLoad("session-1", message);
        RunContext replay = service.createOrLoad("session-1", message);

        assertThat(created.created()).isTrue();
        assertThat(created.run().getStatus()).isEqualTo(ChatRunStatus.RUNNING.name());
        assertThat(created.run().getStartedAt()).isNotNull();
        assertThat(replay.created()).isFalse();
        assertThat(replay.run().getId()).isEqualTo(created.run().getId());
        verify(messageService, times(1)).saveUserMessage(session, "hello");
    }

    @Test
    void shouldKeepExistingStoppedTerminalAgainstLateCompletion() {
        ChatRunEntity identity = run(ChatRunStatus.RUNNING);
        ChatRunEntity persisted = run(ChatRunStatus.STOPPED);
        persisted.setFinishReason(ChatRunFinishReason.USER_STOP.name());
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");

        when(sessionMapper.selectForUpdate("session-1")).thenReturn(session);
        when(runMapper.selectOne(any())).thenReturn(persisted);

        var result = service.finalizeExecution(
                identity,
                new ChatRunFinalizationCommand(
                        ChatRunStatus.COMPLETED, ChatRunFinishReason.SUCCESS, snapshot("partial"), null, null, null));

        assertThat(result.committed()).isFalse();
        assertThat(result.status()).isEqualTo(ChatRunStatus.STOPPED.name());
        assertThat(result.finishReason()).isEqualTo(ChatRunFinishReason.USER_STOP.name());
        assertThat(result.errorCode()).isNull();
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    void shouldClearRunningSnapshotWhenTerminalResultIsCommitted() {
        ChatRunEntity identity = run(ChatRunStatus.RUNNING);
        ChatRunEntity persisted = run(ChatRunStatus.RUNNING);
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        ChatMessageEntity assistant = new ChatMessageEntity();
        assistant.setId(10L);

        when(sessionMapper.selectForUpdate("session-1")).thenReturn(session);
        when(runMapper.selectOne(any())).thenReturn(persisted);
        when(messageService.saveAssistantMessage(session, "answer", null)).thenReturn(assistant);
        when(runMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        var result = service.finalizeExecution(
                identity,
                new ChatRunFinalizationCommand(
                        ChatRunStatus.COMPLETED,
                        ChatRunFinishReason.SUCCESS,
                        snapshot("answer", List.of("123456")),
                        null,
                        null,
                        null));

        assertThat(result.committed()).isTrue();
        verify(attachmentService).bindToMessage(session, List.of("123456"), 10L);
        ArgumentCaptor<LambdaUpdateWrapper<ChatRunEntity>> updateCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(runMapper).update(isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSet()).contains("snapshot_json");
        assertThat(updateCaptor.getValue().getParamNameValuePairs()).containsValue(null);
    }

    @Test
    void shouldFinalizeEmptyFailureWithoutProjectingMessageOrAttachments() {
        ChatRunEntity identity = run(ChatRunStatus.RUNNING);
        ChatRunEntity persisted = run(ChatRunStatus.RUNNING);
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");

        when(sessionMapper.selectForUpdate("session-1")).thenReturn(session);
        when(runMapper.selectOne(any())).thenReturn(persisted);
        when(runMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        var result = service.finalizeExecution(
                identity,
                new ChatRunFinalizationCommand(
                        ChatRunStatus.FAILED,
                        ChatRunFinishReason.ERROR,
                        ChatRunSnapshot.empty("run-1", "phase-1", 1),
                        null,
                        ChatRunFailureCode.ERROR,
                        "attachment projection failed"));

        assertThat(result.committed()).isTrue();
        assertThat(result.status()).isEqualTo(ChatRunStatus.FAILED.name());
        assertThat(result.errorCode()).isEqualTo(ChatRunFailureCode.ERROR.name());
        verifyNoInteractions(messageService, attachmentService);
    }

    @Test
    void shouldCheckpointOnlyRunningRun() {
        ChatRunEntity run = run(ChatRunStatus.RUNNING);
        when(runMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        assertThat(service.checkpoint(run, snapshot("partial"))).isTrue();

        ArgumentCaptor<LambdaUpdateWrapper<ChatRunEntity>> updateCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(runMapper).update(isNull(), updateCaptor.capture());
        LambdaUpdateWrapper<ChatRunEntity> update = updateCaptor.getValue();
        assertThat(update.getSqlSegment()).contains("status");
        assertThat(update.getParamNameValuePairs().values()).contains(ChatRunStatus.RUNNING.name());
    }

    @Test
    void shouldAdvanceWhenPhaseMatches() {
        ChatRunSnapshot snapshot = snapshotWithPendingTool(2, "call_1");
        ChatRunEntity run = runningRunWithPending(2, snapshot);
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(runMapper.selectOne(any())).thenReturn(run);
        when(runMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        ConfirmTransition transition = service.advanceConfirmation(run, session, 2, snapshot);

        assertThat(transition.resumed()).isTrue();
        assertThat(transition.run().getStatus()).isEqualTo(ChatRunStatus.RUNNING.name());
        assertThat(transition.run().getPhaseNo()).isEqualTo(3);
        assertThat(com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshotCodec.decode(
                                transition.run().getSnapshotJson())
                        .pendingTools())
                .isEmpty();
    }

    @Test
    void shouldReturnIdempotentWhenPhaseAlreadyAdvanced() {
        ChatRunSnapshot snapshot = snapshotWithPendingTool(2, "call_1");
        ChatRunEntity run = runningRunWithPending(3, snapshot);
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(runMapper.selectOne(any())).thenReturn(run);

        ConfirmTransition transition = service.advanceConfirmation(run, session, 2, snapshot);

        assertThat(transition.resumed()).isFalse();
        assertThat(transition.run().getPhaseNo()).isEqualTo(3);
    }

    @Test
    void shouldRejectConfirmWhenPhaseBehind() {
        ChatRunSnapshot snapshot = snapshotWithPendingTool(2, "call_1");
        ChatRunEntity run = runningRunWithPending(1, snapshot);
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(runMapper.selectOne(any())).thenReturn(run);

        assertThatThrownBy(() -> service.advanceConfirmation(run, session, 2, snapshot))
                .isInstanceOf(AiBusinessException.class)
                .satisfies(e -> assertThat(((AiBusinessException) e).getCode())
                        .isEqualTo(AiErrorCode.CHAT_RUN_STATE_CONFLICT.getCode()));
    }

    @Test
    void shouldRejectConfirmWithoutPendingProjection() {
        ChatRunEntity run = run(ChatRunStatus.RUNNING);
        run.setPhaseNo(2);
        run.setAguiRunId("phase-2");
        ChatRunSnapshot snapshot = ChatRunSnapshot.empty("run-1", "phase-2", 2);
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(runMapper.selectOne(any())).thenReturn(run);

        assertThatThrownBy(() -> service.advanceConfirmation(run, session, 2, snapshot))
                .isInstanceOf(AiBusinessException.class)
                .satisfies(e -> assertThat(((AiBusinessException) e).getCode())
                        .isEqualTo(AiErrorCode.CHAT_RUN_STATE_CONFLICT.getCode()));
    }

    @Test
    void shouldRejectConfirmWhenSessionNotOwned() {
        ChatRunSnapshot snapshot = snapshotWithPendingTool(2, "call_1");
        ChatRunEntity run = runningRunWithPending(2, snapshot);
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        // 所有权复核：按 session.id + userId 查不到本人会话。
        when(sessionMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.advanceConfirmation(run, session, 2, snapshot))
                .isInstanceOf(AiBusinessException.class)
                .satisfies(e -> assertThat(((AiBusinessException) e).getCode())
                        .isEqualTo(AiErrorCode.CHAT_RUN_NOT_FOUND.getCode()));
    }

    @Test
    void shouldRejectConfirmWhenCasLosesRace() {
        ChatRunSnapshot snapshot = snapshotWithPendingTool(2, "call_1");
        ChatRunEntity run = runningRunWithPending(2, snapshot);
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(runMapper.selectOne(any())).thenReturn(run);
        when(runMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.advanceConfirmation(run, session, 2, snapshot))
                .isInstanceOf(AiBusinessException.class)
                .satisfies(e -> assertThat(((AiBusinessException) e).getCode())
                        .isEqualTo(AiErrorCode.CHAT_RUN_STATE_CONFLICT.getCode()));
    }

    private static ChatRunEntity run(ChatRunStatus status) {
        ChatRunEntity run = new ChatRunEntity();
        run.setId("run-1");
        run.setSessionId("session-1");
        run.setStatus(status.name());
        return run;
    }

    private static ChatRunEntity runningRunWithPending(int phaseNo, ChatRunSnapshot snapshot) {
        ChatRunEntity run = run(ChatRunStatus.RUNNING);
        run.setPhaseNo(phaseNo);
        run.setAguiRunId(snapshot.aguiRunId());
        run.setSnapshotJson(io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(snapshot));
        return run;
    }

    private static ChatRunSnapshot snapshotWithPendingTool(int phaseNo, String toolCallId) {
        return new ChatRunSnapshot(
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
                List.of(new ChatRunSnapshot.ToolCall(toolCallId, "demo_tool", "", "", "asking")),
                List.of(),
                List.of());
    }

    private static ChatRunSnapshot snapshot(String text) {
        return snapshot(text, null);
    }

    private static ChatRunSnapshot snapshot(String text, List<String> generatedAttachmentIds) {
        return new ChatRunSnapshot(
                "run-1",
                "phase-1",
                1,
                text,
                "",
                "message-1",
                null,
                false,
                false,
                null,
                null,
                null,
                generatedAttachmentIds);
    }
}
