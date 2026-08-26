package com.lambda.fusion.ai.runtime.media;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 将页面明确选择的工具调用接入 AgentScope 官方中间件生命周期。
 *
 * <p>首次模型调用由一个仅返回目标 {@link ToolUseBlock} 的内存模型替换，因此不会请求对话模型；工具仍由
 * AgentScope 完成 Schema 校验、权限判断、执行和事件发布。工具执行后的终止由
 * {@link ExplicitToolCallHook} 在官方 PostActing 阶段处理，避免在 onActing 中提前停止并产生孤儿结果。
 *
 * @author Jin
 */
public final class ExplicitToolCallMiddleware implements HarnessRuntimeMiddleware {

    private static final String MODEL_NAME = "lambda-fusion-explicit-tool-call";
    private static final String DENIED_REASON = "explicit tool call denied";

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        ExplicitToolCall invocation = explicitCall(context);
        if (invocation == null) {
            return next.apply(input);
        }
        ModelCallInput explicitInput = new ModelCallInput(
                input.messages(), input.tools(), input.options(), new ExplicitToolModel(invocation.toolCall()));
        return next.apply(explicitInput);
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext context, ActingInput input, Function<ActingInput, Flux<AgentEvent>> next) {
        if (!containsExplicitCall(input)) {
            return next.apply(input);
        }
        return next.apply(input)
                .concatMap(event -> event instanceof AllToolsDeniedEvent
                        ? Flux.just(event, new RequestStopEvent(DENIED_REASON, GenerateReason.ALL_TOOLS_DENIED))
                        : Flux.just(event));
    }

    private static boolean containsExplicitCall(ActingInput input) {
        return input.toolCalls() != null && input.toolCalls().stream().anyMatch(ExplicitToolCall::isExplicit);
    }

    private static ExplicitToolCall explicitCall(RuntimeContext context) {
        return context == null ? null : context.get(ExplicitToolCall.class);
    }

    private record ExplicitToolModel(ToolUseBlock toolCall) implements Model {

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(ChatResponse.builder()
                    .content(List.of(toolCall))
                    .finishReason("tool_calls")
                    .build());
        }

        @Override
        public String getModelName() {
            return MODEL_NAME;
        }
    }
}
