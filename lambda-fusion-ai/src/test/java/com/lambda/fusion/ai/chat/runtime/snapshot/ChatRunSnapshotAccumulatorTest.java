package com.lambda.fusion.ai.chat.runtime.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.lambda.fusion.ai.chat.model.ChatRunStep;
import com.lambda.fusion.ai.chat.runtime.ChatRunSnapshotAccumulator;
import io.agentscope.core.agui.event.AguiEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatRunSnapshotAccumulatorTest {

    @Test
    void shouldProjectCompletedToolFromOfficialAguiEvents() {
        ChatRunSnapshotAccumulator accumulator = accumulator();

        accumulator.apply(List.of(
                new AguiEvent.ToolCallStart("session-1", "phase-1", "tool-1", "search"),
                new AguiEvent.ToolCallArgs("session-1", "phase-1", "tool-1", "{\"q\":"),
                new AguiEvent.ToolCallArgs("session-1", "phase-1", "tool-1", "\"x\"}"),
                new AguiEvent.ToolCallEnd("session-1", "phase-1", "tool-1"),
                new AguiEvent.ToolCallResult("session-1", "phase-1", "tool-1", "result-text", "tool", "reply-1")));

        assertThat(accumulator.buildSnapshot().tools())
                .containsExactly(
                        new ChatRunSnapshot.ToolCall("tool-1", "search", "{\"q\":\"x\"}", "result-text", "complete"));
    }

    @Test
    void shouldKeepToolOrderWhenExistingToolReceivesMoreArguments() {
        ChatRunSnapshotAccumulator accumulator = accumulator();

        accumulator.apply(List.of(
                new AguiEvent.ToolCallStart("session-1", "phase-1", "tool-1", "first"),
                new AguiEvent.ToolCallStart("session-1", "phase-1", "tool-2", "second"),
                new AguiEvent.ToolCallArgs("session-1", "phase-1", "tool-1", "{}")));

        assertThat(accumulator.buildSnapshot().tools())
                .extracting(ChatRunSnapshot.ToolCall::toolCallId)
                .containsExactly("tool-1", "tool-2");
    }

    @Test
    void shouldProjectPendingConfirmationFromInterruptOutcome() {
        ChatRunSnapshotAccumulator accumulator = accumulator();
        AguiEvent.Interrupt interrupt = new AguiEvent.Interrupt(
                "tool-1",
                "human_confirmation_required",
                "confirm",
                "tool-1",
                null,
                null,
                Map.of("toolName", "dangerous"));

        accumulator.apply(List.of(new AguiEvent.RunFinished(
                "session-1", "phase-1", null, new AguiEvent.RunFinishedInterruptOutcome(List.of(interrupt)))));

        assertThat(accumulator.buildSnapshot().pendingTools())
                .containsExactly(new ChatRunSnapshot.ToolCall("tool-1", "dangerous", "", "", "asking"));
    }

    @Test
    void shouldSanitizeCompletedToolOnceAndKeepStableAcrossRebuilds() {
        ChatRunSnapshotAccumulator accumulator = accumulator();
        String bigResult = "{\"apiKey\":\"sk-live-secret\",\"data\":\"" + "x".repeat(3000) + "\"}";

        accumulator.apply(List.of(
                new AguiEvent.ToolCallStart("session-1", "phase-1", "tool-1", "search"),
                new AguiEvent.ToolCallArgs("session-1", "phase-1", "tool-1", "{}"),
                new AguiEvent.ToolCallResult("session-1", "phase-1", "tool-1", bigResult, "tool", "reply-1")));

        ChatRunSnapshot.ToolCall first = accumulator.buildSnapshot().tools().get(0);
        // COMPLETE 即脱敏：密钥字段被屏蔽，超长内容被截断。
        assertThat(first.result()).doesNotContain("sk-live-secret");
        assertThat(first.result()).contains("快照内容已截断");
        // 再次构建（含新增事件）结果保持一致，不会重复截断。
        accumulator.apply(List.of(new AguiEvent.TextMessageContent("session-1", "phase-1", "m-1", "later")));
        ChatRunSnapshot.ToolCall second = accumulator.buildSnapshot().tools().get(0);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void shouldEmitStepsForInterleavedReasoningToolsAndText() {
        ChatRunSnapshotAccumulator accumulator = accumulator();

        accumulator.apply(List.of(
                new AguiEvent.ReasoningMessageStart("session-1", "phase-1", "r-1", "reasoning"),
                new AguiEvent.ReasoningMessageContent("session-1", "phase-1", "r-1", "思考A"),
                new AguiEvent.ReasoningMessageEnd("session-1", "phase-1", "r-1"),
                new AguiEvent.ToolCallStart("session-1", "phase-1", "tool-1", "search"),
                new AguiEvent.ToolCallArgs("session-1", "phase-1", "tool-1", "{\"q\":\"x\"}"),
                new AguiEvent.ToolCallResult("session-1", "phase-1", "tool-1", "result-text", "tool", "reply-1"),
                new AguiEvent.TextMessageStart("session-1", "phase-1", "t-1", "assistant"),
                new AguiEvent.TextMessageContent("session-1", "phase-1", "t-1", "答案"),
                new AguiEvent.TextMessageEnd("session-1", "phase-1", "t-1")));

        assertThat(accumulator.pendingSteps())
                .extracting(
                        ChatRunStep::kind,
                        ChatRunStep::content,
                        ChatRunStep::toolCallId,
                        ChatRunStep::toolResult,
                        ChatRunStep::position,
                        ChatRunStep::phaseNo)
                .containsExactly(
                        tuple("reasoning", "思考A", null, null, 1, 1),
                        tuple("tool_call", "", "tool-1", "result-text", 2, 1),
                        tuple("text", "答案", null, null, 3, 1));
        assertThat(accumulator.pendingSteps()).allSatisfy(step -> {
            assertThat(step.startedAt()).isNotNull();
            assertThat(step.finishedAt()).isNotNull();
        });
        // 快照投影不受步骤采集影响。
        assertThat(accumulator.buildSnapshot().text()).isEqualTo("答案");
        assertThat(accumulator.buildSnapshot().reasoning()).isEqualTo("思考A");
    }

    @Test
    void shouldContinueStepNoFromWatermarkAndAttributePhase() {
        ChatRunSnapshotAccumulator accumulator =
                new ChatRunSnapshotAccumulator(ChatRunSnapshot.empty("run-1", "phase-1", 1), 3);

        accumulator.apply(List.of(new AguiEvent.TextMessageContent("session-1", "phase-1", "t-1", "阶段一")));
        accumulator.beginPhase("phase-2", 2);
        accumulator.apply(List.of(
                new AguiEvent.ReasoningMessageStart("session-1", "phase-2", "r-2", "reasoning"),
                new AguiEvent.ReasoningMessageContent("session-1", "phase-2", "r-2", "阶段二思考"),
                new AguiEvent.ReasoningMessageEnd("session-1", "phase-2", "r-2")));

        assertThat(accumulator.pendingSteps())
                .extracting(ChatRunStep::kind, ChatRunStep::content, ChatRunStep::position, ChatRunStep::phaseNo)
                .containsExactly(tuple("text", "阶段一", 4, 1), tuple("reasoning", "阶段二思考", 5, 2));
    }

    @Test
    void shouldNotEmitEmptySegmentsAsSteps() {
        ChatRunSnapshotAccumulator accumulator = accumulator();

        accumulator.apply(List.of(
                new AguiEvent.TextMessageStart("session-1", "phase-1", "t-1", "assistant"),
                new AguiEvent.TextMessageEnd("session-1", "phase-1", "t-1"),
                new AguiEvent.ReasoningMessageStart("session-1", "phase-1", "r-1", "reasoning"),
                new AguiEvent.ReasoningMessageEnd("session-1", "phase-1", "r-1")));

        assertThat(accumulator.pendingSteps()).isEmpty();
    }

    @Test
    void shouldEmitSuspendedToolStepOnlyAfterResultArrives() {
        ChatRunSnapshotAccumulator accumulator = accumulator();

        // 挂起语义：ToolCallEnd 不携带结果，不应产出步骤。
        accumulator.apply(List.of(
                new AguiEvent.ToolCallStart("session-1", "phase-1", "tool-1", "dangerous"),
                new AguiEvent.ToolCallArgs("session-1", "phase-1", "tool-1", "{}"),
                new AguiEvent.ToolCallEnd("session-1", "phase-1", "tool-1")));
        assertThat(accumulator.pendingSteps()).isEmpty();

        accumulator.apply(List.of(
                new AguiEvent.ToolCallResult("session-1", "phase-1", "tool-1", "done", "tool", "reply-1"),
                new AguiEvent.ToolCallResult("session-1", "phase-1", "tool-1", "done", "tool", "reply-1")));

        assertThat(accumulator.pendingSteps())
                .hasSize(1)
                .extracting(ChatRunStep::kind, ChatRunStep::toolCallId, ChatRunStep::toolResult, ChatRunStep::status)
                .containsExactly(tuple("tool_call", "tool-1", "done", "complete"));
    }

    @Test
    void shouldRecordUnfinishedToolsOnceWhenClosingRemaining() {
        ChatRunSnapshotAccumulator accumulator = accumulator();

        accumulator.apply(List.of(
                new AguiEvent.ToolCallStart("session-1", "phase-1", "tool-1", "search"),
                new AguiEvent.ToolCallArgs("session-1", "phase-1", "tool-1", "{\"q\":\"x\"}")));
        accumulator.closeRemainingToolSteps();
        accumulator.closeRemainingToolSteps();

        assertThat(accumulator.pendingSteps())
                .hasSize(1)
                .extracting(ChatRunStep::kind, ChatRunStep::toolCallId, ChatRunStep::toolArgs, ChatRunStep::status)
                .containsExactly(tuple("tool_call", "tool-1", "{\"q\":\"x\"}", "running"));
    }

    @Test
    void shouldRemoveFlushedPrefixFromPendingSteps() {
        ChatRunSnapshotAccumulator accumulator = accumulator();

        accumulator.apply(List.of(
                new AguiEvent.TextMessageContent("session-1", "phase-1", "t-1", "第一段"),
                new AguiEvent.TextMessageEnd("session-1", "phase-1", "t-1"),
                new AguiEvent.TextMessageStart("session-1", "phase-1", "t-2", "assistant"),
                new AguiEvent.TextMessageContent("session-1", "phase-1", "t-2", "第二段"),
                new AguiEvent.TextMessageEnd("session-1", "phase-1", "t-2")));
        accumulator.markStepsFlushed(1);

        assertThat(accumulator.pendingSteps()).extracting(ChatRunStep::content).containsExactly("第二段");
    }

    private static ChatRunSnapshotAccumulator accumulator() {
        return new ChatRunSnapshotAccumulator(ChatRunSnapshot.empty("run-1", "phase-1", 1));
    }
}
