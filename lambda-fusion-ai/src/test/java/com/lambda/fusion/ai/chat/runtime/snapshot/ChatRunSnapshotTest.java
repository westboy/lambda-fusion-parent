package com.lambda.fusion.ai.chat.runtime.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatRunSnapshotTest {

    @Test
    void shouldRedactCommonSecretFieldsFromPersistedToolState() {
        ChatRunSnapshot snapshot = new ChatRunSnapshot(
                "run-1",
                "phase-1",
                1,
                "",
                "",
                null,
                null,
                false,
                false,
                List.of(new ChatRunSnapshot.ToolCall(
                        "tool-1",
                        "request",
                        "{\"apiKey\":\"secret-value\",\"nested\":{\"token\":\"bearer\"},\"query\":\"ok\"}",
                        "{\"password\":\"hidden\",\"value\":1}",
                        "complete")),
                List.of(),
                List.of(),
                List.of());

        ChatRunSnapshot.ToolCall tool = snapshot.tools().getFirst();
        assertThat(tool.args())
                .contains("\"apiKey\":\"***\"")
                .contains("\"token\":\"***\"")
                .contains("ok");
        assertThat(tool.result()).contains("\"password\":\"***\"").contains("\"value\":1");
        assertThat(tool.args()).doesNotContain("secret-value", "bearer");
        assertThat(tool.result()).doesNotContain("hidden");
    }

    @Test
    void shouldPreserveSnapshotJsonShapeAcrossCodecRoundTrip() {
        ChatRunSnapshot original = new ChatRunSnapshot(
                "run-1",
                "phase-1",
                2,
                "answer",
                "reasoning",
                "message-1",
                "reasoning-1",
                true,
                false,
                List.of(new ChatRunSnapshot.ToolCall("tool-1", "search", "{}", "result", "complete")),
                List.of(new ChatRunSnapshot.ToolCall("tool-2", "confirm", "ignored", "ignored", "complete")),
                List.of(),
                List.of());

        String json = ChatRunSnapshotCodec.encode(original);
        ChatRunSnapshot restored = ChatRunSnapshotCodec.decode(json);

        assertThat(restored).isEqualTo(original);
        assertThat(json).contains("\"runId\":\"run-1\"", "\"pendingTools\"");
    }

    @Test
    void shouldRejectInvalidSnapshotInsteadOfSilentlyErasingIt() {
        assertThatThrownBy(() -> ChatRunSnapshotCodec.decode("{invalid"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("对话Run快照解析失败");
    }

    @Test
    void shouldIgnoreNullPendingTools() {
        ChatRunSnapshot snapshot = new ChatRunSnapshot(
                "run-1",
                "phase-1",
                1,
                "",
                "",
                null,
                null,
                false,
                false,
                List.of(),
                java.util.Arrays.asList((ChatRunSnapshot.ToolCall) null),
                List.of(),
                List.of());

        assertThat(snapshot.pendingTools()).isEmpty();
    }

    @Test
    void shouldKeepOnlyBoundedHeadAndTailPreviewForLargeToolContent() {
        String args = "args-head-" + "a".repeat(4000) + "-args-tail";
        String result = "result-head-" + "r".repeat(5000) + "-result-tail";

        ChatRunSnapshot snapshot = new ChatRunSnapshot(
                "run-1",
                "phase-1",
                1,
                "",
                "",
                null,
                null,
                false,
                false,
                List.of(new ChatRunSnapshot.ToolCall("tool-1", "read", args, result, "complete")),
                List.of(),
                List.of(),
                List.of());

        ChatRunSnapshot.ToolCall tool = snapshot.tools().getFirst();
        assertThat(tool.args())
                .hasSizeLessThanOrEqualTo(ChatRunSnapshotSanitizer.MAX_TOOL_CONTENT_CHARS)
                .startsWith("args-head-")
                .endsWith("-args-tail")
                .contains("快照内容已截断");
        assertThat(tool.result())
                .hasSizeLessThanOrEqualTo(ChatRunSnapshotSanitizer.MAX_TOOL_CONTENT_CHARS)
                .startsWith("result-head-")
                .endsWith("-result-tail")
                .contains("快照内容已截断");
    }
}
