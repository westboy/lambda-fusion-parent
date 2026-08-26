package com.lambda.fusion.ai.runtime.media;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import reactor.core.publisher.Mono;

/**
 * AgentScope 2.0.1 的终止工具兼容边界。
 *
 * <p>2.0.1 的 {@code onActing} 中间件若直接请求停止，会跳过成功工具结果的 PostActing
 * 归一化与记忆写入；官方修复尚未发布。因此这里只在 AgentScope 已进入 PostActing 后识别平台标记并请求停止，
 * 不读取运行上下文、不修改 AgentState，也不承担工具执行职责。上游修复发布后可整体删除本 Hook。
 *
 * @author Jin
 * @see <a href="https://github.com/agentscope-ai/agentscope-java/issues/2362">AgentScope issue
 *     #2362</a>
 * @see <a href="https://github.com/agentscope-ai/agentscope-java/pull/2380">AgentScope PR
 *     #2380</a>
 */
@SuppressWarnings({"deprecation", "removal"})
public final class ExplicitToolCallHook implements Hook {

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostActingEvent acting && ExplicitToolCall.isExplicit(acting.getToolUse())) {
            acting.stopAgent();
        }
        return Mono.just(event);
    }
}
