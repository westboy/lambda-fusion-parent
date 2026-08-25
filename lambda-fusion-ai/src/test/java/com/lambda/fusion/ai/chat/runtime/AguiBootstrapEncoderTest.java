package com.lambda.fusion.ai.chat.runtime.agui;

import static org.assertj.core.api.Assertions.assertThat;

import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AguiBootstrapEncoderTest {

    @Test
    void shouldBuildCompleteBootstrapWithoutCommittedSequenceNumbers() {
        ChatRunEntity run = run(ChatRunStatus.RUNNING);
        ChatRunSnapshot snapshot = new ChatRunSnapshot(
                run.getId(),
                run.getAguiRunId(),
                2,
                "answer",
                "thought",
                "text-1",
                "reasoning-1",
                true,
                false,
                List.of(new ChatRunSnapshot.ToolCall("tool-1", "search", "{q:1}", "result", "complete")),
                List.of(),
                List.of(),
                List.of());

        List<String> events = AguiBootstrapEncoder.encode(run, snapshot);

        assertThat(events.getFirst())
                .contains("\"type\":\"RUN_STARTED\"")
                .contains("\"phaseNo\":2")
                .doesNotContain("\"bootstrap\":", "\"bootstrapSeq\":", "\"seq\":");
        assertThat(events)
                .anyMatch(event -> event.contains("\"type\":\"TEXT_MESSAGE_CONTENT\"") && event.contains("answer"));
        assertThat(events)
                .anyMatch(event -> event.contains("\"type\":\"REASONING_START\"")
                        && event.contains("\"messageId\":\"reasoning-1\""));
        assertThat(events)
                .anyMatch(event -> event.contains("\"type\":\"REASONING_END\"")
                        && event.contains("\"messageId\":\"reasoning-1\""));
        assertThat(events)
                .anyMatch(event -> event.contains("\"type\":\"TOOL_CALL_RESULT\"") && event.contains("result"));
        assertThat(events)
                .anyMatch(event -> event.contains("\"type\":\"TEXT_MESSAGE_START\"")
                        && event.contains("\"messageId\":\"text-1\""));
        assertThat(events).noneMatch(event -> event.contains("\"type\":\"RAW\""));
        assertThat(events.stream().map(AguiBootstrapEncoderTest::readEventType))
                .containsExactly(
                        "RUN_STARTED",
                        "REASONING_START",
                        "REASONING_MESSAGE_START",
                        "REASONING_MESSAGE_CONTENT",
                        "REASONING_MESSAGE_END",
                        "REASONING_END",
                        "TOOL_CALL_START",
                        "TOOL_CALL_ARGS",
                        "TOOL_CALL_END",
                        "TOOL_CALL_RESULT",
                        "TEXT_MESSAGE_START",
                        "TEXT_MESSAGE_CONTENT");
    }

    @Test
    void shouldCloseRestoredReasoningAroundToolsAndReopenItForLiveDeltas() {
        ChatRunEntity run = run(ChatRunStatus.RUNNING);
        ChatRunSnapshot snapshot = new ChatRunSnapshot(
                run.getId(),
                run.getAguiRunId(),
                2,
                "",
                "restored thought",
                null,
                "reasoning-1",
                false,
                true,
                List.of(new ChatRunSnapshot.ToolCall("tool-1", "search", "{q:1}", "result", "complete")),
                List.of(),
                List.of(),
                List.of());

        List<String> events = AguiBootstrapEncoder.encode(run, snapshot);

        assertThat(events.stream().map(AguiBootstrapEncoderTest::readEventType))
                .containsExactly(
                        "RUN_STARTED",
                        "REASONING_START",
                        "REASONING_MESSAGE_START",
                        "REASONING_MESSAGE_CONTENT",
                        "REASONING_MESSAGE_END",
                        "REASONING_END",
                        "TOOL_CALL_START",
                        "TOOL_CALL_ARGS",
                        "TOOL_CALL_END",
                        "TOOL_CALL_RESULT",
                        "REASONING_START",
                        "REASONING_MESSAGE_START");
        assertThat(events)
                .filteredOn(event -> event.contains("restored thought"))
                .hasSize(1);
    }

    @Test
    void shouldRestoreAwaitingConfirmationAsInterrupt() {
        ChatRunEntity run = run(ChatRunStatus.RUNNING);
        ChatRunSnapshot snapshot = new ChatRunSnapshot(
                run.getId(),
                run.getAguiRunId(),
                2,
                "partial",
                "",
                "text-1",
                null,
                false,
                false,
                List.of(),
                List.of(new ChatRunSnapshot.ToolCall("tool-1", "dangerous", "", "", "asking")),
                List.of(),
                List.of());

        assertThat(AguiBootstrapEncoder.encode(run, snapshot))
                .anyMatch(event -> event.contains("\"type\":\"RUN_FINISHED\"")
                        && event.contains("\"type\":\"interrupt\"")
                        && event.contains("tool-1"));
    }

    @Test
    void shouldLeaveInProgressToolOpenForFollowingDeltas() {
        ChatRunEntity run = run(ChatRunStatus.RUNNING);
        ChatRunSnapshot snapshot = new ChatRunSnapshot(
                run.getId(),
                run.getAguiRunId(),
                2,
                "",
                "",
                null,
                null,
                false,
                false,
                List.of(new ChatRunSnapshot.ToolCall("tool-1", "search", "{\"q\":", "", "running")),
                List.of(),
                List.of(),
                List.of());

        List<String> events = AguiBootstrapEncoder.encode(run, snapshot);

        assertThat(events).anyMatch(event -> event.contains("\"type\":\"TOOL_CALL_ARGS\""));
        assertThat(events).noneMatch(event -> event.contains("\"type\":\"TOOL_CALL_END\""));
    }

    @Test
    void shouldEncodeFailedRunAsRunError() {
        ChatRunEntity run = run(ChatRunStatus.FAILED);
        run.setErrorCode("MODEL_ERROR");
        run.setErrorMessage("model unavailable");

        List<String> events =
                AguiBootstrapEncoder.encode(run, ChatRunSnapshot.empty(run.getId(), run.getAguiRunId(), 2));

        assertThat(events).hasSize(2);
        assertThat(events.getLast())
                .contains("\"type\":\"RUN_ERROR\"")
                .contains("\"message\":\"model unavailable\"")
                .contains("\"code\":\"MODEL_ERROR\"");
    }

    @Test
    void shouldEncodeStoppedRunAsSuccessfulTerminalEvent() {
        ChatRunEntity run = run(ChatRunStatus.STOPPED);
        run.setFinishReason("USER_STOP");

        List<String> events =
                AguiBootstrapEncoder.encode(run, ChatRunSnapshot.empty(run.getId(), run.getAguiRunId(), 2));

        assertThat(events).hasSize(2);
        assertThat(events.getLast())
                .contains("\"type\":\"RUN_FINISHED\"")
                .contains("\"chatRunStatus\":\"STOPPED\"")
                .contains("\"finishReason\":\"USER_STOP\"");
    }

    private static ChatRunEntity run(ChatRunStatus status) {
        ChatRunEntity run = new ChatRunEntity();
        run.setId("run-1");
        run.setSessionId("session-1");
        run.setAguiRunId("phase-2");
        run.setPhaseNo(2);
        run.setStatus(status.name());
        return run;
    }

    /** 从已编码事件 JSON 中读取 {@code type} 字段，仅供测试断言使用。 */
    @SuppressWarnings("unchecked")
    private static String readEventType(String json) {
        Map<String, Object> event = JsonUtils.getJsonCodec().fromJson(json.trim(), Map.class);
        Object type = event == null ? null : event.get("type");
        return type == null ? null : String.valueOf(type);
    }
}
