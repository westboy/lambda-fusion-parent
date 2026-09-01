package com.lambda.fusion.ai.runtime.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.chat.runtime.media.ToolResultMediaMaterializer;
import com.lambda.fusion.ai.runtime.gateway.RuntimeProperty;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class MaterializingMediaToolTest {

    @Test
    void shouldEmitStoredMediaAndReturnOnlyTextToTheModel() {
        AgentTool delegate = delegate();
        ToolResultMediaMaterializer materializer = mock(ToolResultMediaMaterializer.class);
        ToolResultBlock providerResult = providerMediaResult();
        ToolResultBlock storedResult = storedMediaResult();
        when(delegate.callAsync(any())).thenReturn(Mono.just(providerResult));
        when(materializer.materialize(eq(providerResult), any(), eq("call-1"))).thenReturn(Mono.just(storedResult));
        AtomicReference<ToolResultBlock> emitted = new AtomicReference<>();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MaterializingMediaTool(delegate, materializer));
        toolkit.setChunkCallback((toolUse, chunk) -> emitted.set(chunk));
        List<ToolResultBlock> results = toolkit.callTools(List.of(toolUse()), null, null, runtimeContext())
                .block(Duration.ofSeconds(1));

        assertThat(emitted.get()).isSameAs(storedResult);
        assertThat(results).singleElement().satisfies(modelResult -> {
            assertThat(modelResult.getOutput())
                    .allMatch(TextBlock.class::isInstance)
                    .extracting(block -> ((TextBlock) block).getText())
                    .containsExactly("The media generation call completed successfully and produced 1 media item(s), "
                            + "stored as conversation attachment IDs: [attachment:123]. "
                            + "The client displays these attachments separately.")
                    .noneMatch(text -> text.contains("upstream.example.com"))
                    .noneMatch(text -> text.contains("/preview"))
                    .noneMatch(text -> text.contains("Do not call any media generation tool again"));
            assertThat(modelResult.getMetadata()).isEmpty();
        });
    }

    @Test
    void shouldReturnTextResultWithoutEmittingAChunk() {
        AgentTool delegate = delegate();
        ToolResultMediaMaterializer materializer = mock(ToolResultMediaMaterializer.class);
        ToolResultBlock textResult = ToolResultBlock.text("generation failed");
        when(delegate.callAsync(any())).thenReturn(Mono.just(textResult));
        when(materializer.materialize(eq(textResult), any(), eq("call-1"))).thenReturn(Mono.just(textResult));
        AtomicReference<ToolResultBlock> emitted = new AtomicReference<>();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MaterializingMediaTool(delegate, materializer));
        toolkit.setChunkCallback((toolUse, chunk) -> emitted.set(chunk));
        List<ToolResultBlock> results = toolkit.callTools(List.of(toolUse()), null, null, runtimeContext())
                .block(Duration.ofSeconds(1));

        assertThat(results).singleElement().satisfies(result -> assertThat(result.getOutput())
                .singleElement()
                .isInstanceOfSatisfying(
                        TextBlock.class, block -> assertThat(block.getText()).isEqualTo("generation failed")));
        assertThat(emitted).hasNullValue();
        verify(materializer).materialize(eq(textResult), any(), eq("call-1"));
        verify(delegate).callAsync(any());
    }

    @Test
    void shouldStreamStoredMediaButSendOnlyTextIntoTheSecondModelCall() {
        AgentTool delegate = delegate();
        ToolResultMediaMaterializer materializer = mock(ToolResultMediaMaterializer.class);
        ToolResultBlock providerResult = providerMediaResult();
        ToolResultBlock storedResult = storedMediaResult();
        when(delegate.callAsync(any())).thenReturn(Mono.just(providerResult));
        when(materializer.materialize(eq(providerResult), any(), eq("call-1"))).thenReturn(Mono.just(storedResult));

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MaterializingMediaTool(delegate, materializer));
        TwoTurnToolModel model = new TwoTurnToolModel();
        try (ReActAgent agent = ReActAgent.builder()
                .name("media-result-agent")
                .sysPrompt("Use the available tool.")
                .model(model)
                .toolkit(toolkit)
                .maxIters(2)
                .build()) {
            List<AgentEvent> events = agent.streamEvents(
                            Msg.builderForRole(MsgRole.USER)
                                    .textContent("generate an image")
                                    .build(),
                            runtimeContext())
                    .collectList()
                    .block(Duration.ofSeconds(5));

            assertThat(events)
                    .filteredOn(ToolResultDataDeltaEvent.class::isInstance)
                    .singleElement()
                    .isInstanceOfSatisfying(ToolResultDataDeltaEvent.class, event -> assertThat(event.getData())
                            .isInstanceOf(DataBlock.class));
        }

        assertThat(model.invocations()).isEqualTo(2);
        List<ToolResultBlock> secondCallResults = model.secondCallMessages().stream()
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .toList();
        assertThat(secondCallResults).singleElement().satisfies(result -> {
            assertThat(result.getOutput()).allMatch(TextBlock.class::isInstance).noneMatch(DataBlock.class::isInstance);
            assertThat(result.getOutput())
                    .extracting(block -> ((TextBlock) block).getText())
                    .anyMatch(text -> text.contains("attachment:123"))
                    .noneMatch(text -> text.contains("upstream.example.com"))
                    .noneMatch(text -> text.contains("/preview"))
                    .noneMatch(text -> text.contains("Do not call any media generation tool again"));
            assertThat(result.getMetadata()).isEmpty();
        });
    }

    private static AgentTool delegate() {
        AgentTool delegate = mock(AgentTool.class);
        when(delegate.getName()).thenReturn("test_media_tool");
        when(delegate.getDescription()).thenReturn("test media tool");
        when(delegate.getParameters()).thenReturn(Map.of("type", "object"));
        return delegate;
    }

    private static ToolResultBlock providerMediaResult() {
        return ToolResultBlock.of(List.of(ImageBlock.builder()
                .source(new URLSource("https://upstream.example.com/temporary.png", "image/png"))
                .build()));
    }

    private static ToolResultBlock storedMediaResult() {
        return new ToolResultBlock(
                null,
                null,
                List.of(
                        TextBlock.builder()
                                .text("generated from https://upstream.example.com/temporary.png")
                                .build(),
                        DataBlock.builder()
                                .id("attachment:123")
                                .name("generated.png")
                                .source(new URLSource("/v1/ai/chat/attachments/123/preview", "image/png"))
                                .build()),
                Map.of("upstreamUrl", "https://upstream.example.com/temporary.png"),
                null);
    }

    private static ToolUseBlock toolUse() {
        return ToolUseBlock.builder()
                .id("call-1")
                .name("test_media_tool")
                .input(Map.of())
                .content("{}")
                .build();
    }

    private static RuntimeContext runtimeContext() {
        return RuntimeContext.builder()
                .userId("user-1")
                .sessionId("session-1")
                .put(RuntimeProperty.KEY_RUN_ID, "run-1")
                .put(RuntimeProperty.KEY_TENANT_ID, "tenant-1")
                .put(RuntimeProperty.KEY_LF_SESSION_ID, "session-1")
                .build();
    }

    private static final class TwoTurnToolModel implements Model {

        private final AtomicInteger invocations = new AtomicInteger();
        private List<Msg> secondCallMessages = List.of();

        int invocations() {
            return invocations.get();
        }

        List<Msg> secondCallMessages() {
            return secondCallMessages;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (invocations.incrementAndGet() == 1) {
                ToolUseBlock toolUse = ToolUseBlock.builder()
                        .id("call-1")
                        .name("test_media_tool")
                        .input(Map.of())
                        .content("{}")
                        .build();
                return Flux.just(response(List.of(toolUse), "tool_calls"));
            }
            secondCallMessages = List.copyOf(messages);
            return Flux.just(response(List.of(TextBlock.builder().text("done").build()), "stop"));
        }

        @Override
        public String getModelName() {
            return "two-turn-tool-model";
        }

        private static ChatResponse response(List<ContentBlock> content, String finishReason) {
            return ChatResponse.builder()
                    .id("msg_" + UUID.randomUUID())
                    .content(content)
                    .usage(new ChatUsage(1, 1, 2))
                    .finishReason(finishReason)
                    .build();
        }
    }
}
