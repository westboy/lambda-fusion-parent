package com.lambda.fusion.ai.runtime.media;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 结构化媒体请求的 AgentScope 路由中间件。首次推理仅暴露并强制调用服务端选定的工具；工具完成后的
 * 推理禁用工具调用，使一个请求只产生一批媒体，数量由工具的 {@code n/count} 参数控制。
 *
 * @author Jin
 */
public final class MediaGenerationRoutingMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        MediaGenerationInvocation invocation = context == null ? null : context.get(MediaGenerationInvocation.class);
        if (invocation == null) {
            return next.apply(input);
        }

        if (!invocation.claimInitialModelCall()) {
            return next.apply(new ModelCallInput(
                    input.messages(),
                    List.of(),
                    withToolChoice(input.options(), new ToolChoice.None()),
                    input.model()));
        }

        List<ToolSchema> selectedTools = input.tools() == null
                ? List.of()
                : input.tools().stream()
                        .filter(tool -> invocation.getToolName().equals(tool.getName()))
                        .toList();
        if (selectedTools.isEmpty()) {
            return Flux.error(new IllegalStateException("应用未挂载媒体生成工具: " + invocation.getToolName()));
        }
        GenerateOptions options = GenerateOptions.mergeOptions(
                GenerateOptions.builder()
                        .toolChoice(new ToolChoice.Specific(invocation.getToolName()))
                        .parallelToolCalls(false)
                        .build(),
                input.options());
        return next.apply(new ModelCallInput(input.messages(), selectedTools, options, input.model()));
    }

    private static GenerateOptions withToolChoice(GenerateOptions current, ToolChoice choice) {
        return GenerateOptions.mergeOptions(
                GenerateOptions.builder().toolChoice(choice).build(), current);
    }
}
