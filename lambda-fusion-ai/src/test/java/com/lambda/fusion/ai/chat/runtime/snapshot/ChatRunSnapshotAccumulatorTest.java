package com.lambda.fusion.ai.chat.runtime.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.lambda.fusion.ai.chat.runtime.ChatExecutionSnapshotBuilder;
import io.agentscope.core.agui.event.AguiEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatRunSnapshotAccumulatorTest {

    @Test
    void shouldProjectCompletedToolFromOfficialAguiEvents() {
        ChatExecutionSnapshotBuilder accumulator = accumulator();

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
        ChatExecutionSnapshotBuilder accumulator = accumulator();

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
        ChatExecutionSnapshotBuilder accumulator = accumulator();
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
        ChatExecutionSnapshotBuilder accumulator = accumulator();
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

    private static ChatExecutionSnapshotBuilder accumulator() {
        return new ChatExecutionSnapshotBuilder(ChatRunSnapshot.empty("run-1", "phase-1", 1));
    }
}
