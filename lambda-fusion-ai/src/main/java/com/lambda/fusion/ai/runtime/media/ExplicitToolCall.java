package com.lambda.fusion.ai.runtime.media;

import io.agentscope.core.message.ToolUseBlock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 页面明确选择的工具调用。
 *
 * <p>AgentScope 2.0.1 没有公开的“强制执行一个已注册工具”入口。本对象只负责标记已由服务端校验的
 * 工具调用并将其带入 AgentScope 生命周期；执行、超时、权限、事件和状态保存仍由 {@code ReActAgent}
 * 负责。标记随 {@link ToolUseBlock} 持久化，用于权限确认恢复后保持终止工具语义。
 *
 * @author Jin
 */
public record ExplicitToolCall(ToolUseBlock toolCall) {

    private static final String METADATA_KEY = "lambdaFusionExplicitToolCall";

    public ExplicitToolCall {
        Objects.requireNonNull(toolCall, "toolCall");
        Map<String, Object> metadata = new HashMap<>(toolCall.getMetadata());
        metadata.put(METADATA_KEY, Boolean.TRUE);
        toolCall = new ToolUseBlock(
                toolCall.getId(),
                toolCall.getName(),
                toolCall.getInput(),
                toolCall.getContent(),
                metadata,
                toolCall.getState());
    }

    /** 判断工具调用是否由页面显式发起。 */
    public static boolean isExplicit(ToolUseBlock toolCall) {
        return toolCall != null && Boolean.TRUE.equals(toolCall.getMetadata().get(METADATA_KEY));
    }
}
