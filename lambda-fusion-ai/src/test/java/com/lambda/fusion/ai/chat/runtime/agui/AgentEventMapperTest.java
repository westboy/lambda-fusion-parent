package com.lambda.fusion.ai.chat.runtime.agui;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentEventMapperTest {

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
    void shouldMapToolResultDataToVersionedContentBlockEnvelope() {
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
        Map<String, Object> envelope = JsonUtils.getJsonCodec().fromJson(result.content(), Map.class);
        List<Map<String, Object>> content = (List<Map<String, Object>>) envelope.get("content");

        assertThat(envelope)
                .containsEntry("schema", "lambda-fusion/tool-result")
                .containsEntry("version", 1)
                .containsEntry("text", "generated");
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
}
