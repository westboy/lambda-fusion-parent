package com.lambda.fusion.ai.runtime.media;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.state.AgentState;
import reactor.core.publisher.Mono;

/**
 * 将页面明确选择的工具调用接入 AgentScope 调用生命周期。
 *
 * <p>{@link #setRuntimeContext(RuntimeContext)} 由 AgentScope 在会话串行门放行并完成权威状态加载后调用，
 * 因此这里不需要额外锁或平台自建会话队列。工具执行完成后使用官方 {@link PostActingEvent#stopAgent()}
 * 终止 ReAct 循环，避免再发起一次无意义的模型调用。
 *
 * @author Jin
 */
@SuppressWarnings({"deprecation", "removal"})
public final class ExplicitToolCallHook implements Hook, RuntimeContextAware {

    @Override
    public void setRuntimeContext(RuntimeContext context) {
        if (context == null) {
            return;
        }
        ExplicitToolCall invocation = context.get(ExplicitToolCall.class);
        if (invocation == null) {
            return;
        }
        AgentState state = context.getAgentState();
        if (state == null) {
            throw new IllegalStateException("AgentScope call-scoped state is unavailable");
        }
        state.contextMutable().addAll(invocation.historyInput());
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostActingEvent acting && ExplicitToolCall.isExplicit(acting.getToolUse())) {
            acting.stopAgent();
        }
        return Mono.just(event);
    }
}
