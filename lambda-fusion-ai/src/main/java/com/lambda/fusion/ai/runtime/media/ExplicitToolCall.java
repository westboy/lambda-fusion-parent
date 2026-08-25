package com.lambda.fusion.ai.runtime.media;

import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 页面明确选择的工具调用。
 *
 * <p>AgentScope 2.0.1 没有公开的“强制执行一个已注册工具”入口。本对象只负责把已由服务端校验的
 * 用户消息和工具调用带入 AgentScope 的调用生命周期；执行、超时、权限、事件和状态保存仍由
 * {@code ReActAgent} 负责。
 *
 * @author Jin
 */
public record ExplicitToolCall(Msg userMessage, ToolUseBlock toolCall, String agentName) {

    public static final String METADATA_KEY = "lambdaFusionExplicitToolCall";

    public ExplicitToolCall {
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(toolCall, "toolCall");
        Objects.requireNonNull(agentName, "agentName");
    }

    /** 为工具调用增加平台内部标记；该标记不参与工具参数校验。 */
    public ExplicitToolCall marked() {
        Map<String, Object> metadata = Map.of(METADATA_KEY, Boolean.TRUE);
        ToolUseBlock markedCall = new ToolUseBlock(
                toolCall.getId(),
                toolCall.getName(),
                toolCall.getInput(),
                toolCall.getContent(),
                metadata,
                ToolCallState.ALLOWED);
        return new ExplicitToolCall(userMessage, markedCall, agentName);
    }

    /** 按 AgentScope 的标准记忆结构生成本轮输入。 */
    public List<Msg> historyInput() {
        Msg assistantCall = Msg.builderForRole(MsgRole.ASSISTANT)
                .name(agentName)
                .content(toolCall)
                .generateReason(GenerateReason.TOOL_CALLS)
                .build();
        return List.of(userMessage, assistantCall);
    }

    public static boolean isExplicit(ToolUseBlock toolCall) {
        return toolCall != null && Boolean.TRUE.equals(toolCall.getMetadata().get(METADATA_KEY));
    }
}
