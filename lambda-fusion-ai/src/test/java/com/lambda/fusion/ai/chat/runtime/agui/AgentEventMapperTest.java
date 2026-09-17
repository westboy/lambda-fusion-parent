package com.lambda.fusion.ai.chat.runtime.agui;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentEventMapperTest {

    @Test
    void shouldTreatOnlyRootConfirmAsInteractionBoundary() {
        AgentEventMapper mapper = new AgentEventMapper("thread-1", "run-1", true);
        AgentEvent childConfirm = new RequireUserConfirmEvent("child-reply", List.of(askingBlock("child-call")))
                .withSource("root/worker");
        AgentEvent rootConfirm = new RequireUserConfirmEvent("reply-1", List.of(askingBlock("call-1")));

        // 子 Agent 的确认冒泡进父流时不构成运行级待交互边界，也不映射 RunFinished 中断事件。
        assertThat(AgentEventMapper.isInteractionBoundary(childConfirm)).isFalse();
        assertThat(mapper.map(childConfirm)).isEmpty();

        assertThat(AgentEventMapper.isInteractionBoundary(rootConfirm)).isTrue();
        assertThat(mapper.map(rootConfirm))
                .singleElement()
                .isInstanceOfSatisfying(AguiEvent.RunFinished.class, finished -> assertThat(finished.outcome())
                        .isInstanceOfSatisfying(
                                AguiEvent.RunFinishedInterruptOutcome.class, outcome -> assertThat(outcome.interrupts())
                                        .singleElement()
                                        .satisfies(interrupt -> assertThat(interrupt.toolCallId())
                                                .isEqualTo("call-1"))));
    }

    @Test
    void shouldKeepPlainTextToolResultBackwardCompatible() {
        AgentEventMapper mapper = new AgentEventMapper("thread-1", "run-1", true);
        mapper.map(new ToolResultStartEvent("reply-1", "tool-1", "search"));
        mapper.map(new ToolResultTextDeltaEvent("reply-1", "tool-1", "search", "first "));
        mapper.map(new ToolResultTextDeltaEvent("reply-1", "tool-1", "search", "second"));

        List<AguiEvent> events =
                mapper.map(new ToolResultEndEvent("reply-1", "tool-1", "search", ToolResultState.SUCCESS));

        assertThat(events).hasSize(2);
        assertThat(events.getLast())
                .isInstanceOfSatisfying(AguiEvent.ToolCallResult.class, result -> assertThat(result.content())
                        .isEqualTo("first second"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldMapToolResultDataToOfficialNewlineDelimitedContentBlocks() {
        AgentEventMapper mapper = new AgentEventMapper("thread-1", "run-1", true);
        mapper.map(new ToolResultStartEvent("reply-1", "tool-1", "generate_media"));
        mapper.map(new ToolResultTextDeltaEvent("reply-1", "tool-1", "generate_media", "generated"));
        mapper.map(new ToolResultDataDeltaEvent(
                "reply-1",
                "tool-1",
                "generate_media",
                ImageBlock.builder()
                        .source(URLSource.builder()
                                .url("https://example.com/image.png")
                                .mimeType("image/png")
                                .build())
                        .build()));
        mapper.map(new ToolResultDataDeltaEvent(
                "reply-1",
                "tool-1",
                "generate_media",
                AudioBlock.builder()
                        .source(Base64Source.builder()
                                .mediaType("audio/mpeg")
                                .data("YXVkaW8=")
                                .build())
                        .build()));
        mapper.map(new ToolResultDataDeltaEvent(
                "reply-1",
                "tool-1",
                "generate_media",
                VideoBlock.builder()
                        .source(URLSource.builder()
                                .url("https://example.com/video.mp4")
                                .mimeType("video/mp4")
                                .build())
                        .build()));
        mapper.map(new ToolResultDataDeltaEvent(
                "reply-1",
                "tool-1",
                "generate_media",
                DataBlock.builder()
                        .id("data-1")
                        .name("artifact.bin")
                        .source(URLSource.builder()
                                .url("https://example.com/artifact.bin")
                                .mimeType("application/octet-stream")
                                .build())
                        .build()));

        List<AguiEvent> events =
                mapper.map(new ToolResultEndEvent("reply-1", "tool-1", "generate_media", ToolResultState.SUCCESS));
        AguiEvent.ToolCallResult result = (AguiEvent.ToolCallResult) events.getLast();
        List<String> lines = result.content().lines().toList();
        List<Map<String, Object>> content = lines.subList(1, lines.size()).stream()
                .map(line -> (Map<String, Object>) JsonUtils.getJsonCodec().fromJson(line, Map.class))
                .toList();

        assertThat(lines.getFirst()).isEqualTo("generated");
        assertThat(content).extracting(block -> block.get("type")).containsExactly("image", "audio", "video", "data");
        assertThat((Map<String, Object>) content.get(0).get("source"))
                .containsEntry("type", "url")
                .containsEntry("url", "https://example.com/image.png")
                .containsEntry("mime_type", "image/png");
        assertThat((Map<String, Object>) content.get(1).get("source"))
                .containsEntry("type", "base64")
                .containsEntry("media_type", "audio/mpeg")
                .containsEntry("data", "YXVkaW8=");
        assertThat(content.get(3)).containsEntry("id", "data-1").containsEntry("name", "artifact.bin");
    }

    private static ToolUseBlock askingBlock(String toolCallId) {
        return ToolUseBlock.builder()
                .id(toolCallId)
                .name("demo_tool")
                .state(ToolCallState.ASKING)
                .build();
    }
}
