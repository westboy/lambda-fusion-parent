package com.lambda.fusion.ai.runtime.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class MediaGenerationRoutingMiddlewareTest {

    private final MediaGenerationRoutingMiddleware middleware = new MediaGenerationRoutingMiddleware();

    @Test
    void shouldForceOnlySelectedToolOnFirstCallAndDisableToolsAfterward() {
        MediaGenerationInvocation invocation = MediaGenerationInvocation.builder()
                .toolName("text_to_image")
                .prompt("海报")
                .build();
        RuntimeContext context = RuntimeContext.builder()
                .put(MediaGenerationInvocation.class, invocation)
                .build();
        GenerateOptions originalOptions = GenerateOptions.builder()
                .temperature(0.7)
                .toolChoice(new ToolChoice.Auto())
                .parallelToolCalls(true)
                .build();
        ModelCallInput original = new ModelCallInput(
                List.of(), List.of(tool("other_tool"), tool("text_to_image")), originalOptions, mock(Model.class));
        List<ModelCallInput> routed = new ArrayList<>();

        middleware
                .onModelCall(null, context, original, input -> {
                    routed.add(input);
                    return Flux.empty();
                })
                .blockLast();
        middleware
                .onModelCall(null, context, original, input -> {
                    routed.add(input);
                    return Flux.empty();
                })
                .blockLast();

        assertThat(routed).hasSize(2);
        assertThat(routed.get(0).tools()).extracting(ToolSchema::getName).containsExactly("text_to_image");
        assertThat(routed.get(0).options().getToolChoice()).isEqualTo(new ToolChoice.Specific("text_to_image"));
        assertThat(routed.get(0).options().getParallelToolCalls()).isFalse();
        assertThat(routed.get(0).options().getTemperature()).isEqualTo(0.7);
        assertThat(routed.get(1).tools()).isEmpty();
        assertThat(routed.get(1).options().getToolChoice()).isEqualTo(new ToolChoice.None());
    }

    @Test
    void shouldFailWhenApplicationToolkitDoesNotContainSelectedTool() {
        MediaGenerationInvocation invocation = MediaGenerationInvocation.builder()
                .toolName("text_to_audio")
                .prompt("播报")
                .build();
        RuntimeContext context = RuntimeContext.builder()
                .put(MediaGenerationInvocation.class, invocation)
                .build();
        ModelCallInput input = new ModelCallInput(List.of(), List.of(tool("other_tool")), null, mock(Model.class));

        assertThatThrownBy(() -> middleware
                        .onModelCall(null, context, input, ignored -> Flux.empty())
                        .blockLast())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("text_to_audio");
    }

    @Test
    void shouldLeaveOrdinaryChatCallsUntouched() {
        ModelCallInput input = new ModelCallInput(List.of(), List.of(tool("search")), null, mock(Model.class));
        List<ModelCallInput> routed = new ArrayList<>();

        middleware
                .onModelCall(null, RuntimeContext.empty(), input, current -> {
                    routed.add(current);
                    return Flux.empty();
                })
                .blockLast();

        assertThat(routed).singleElement().isSameAs(input);
    }

    private static ToolSchema tool(String name) {
        return ToolSchema.builder()
                .name(name)
                .description(name)
                .parameters(java.util.Map.of("type", "object"))
                .build();
    }
}
