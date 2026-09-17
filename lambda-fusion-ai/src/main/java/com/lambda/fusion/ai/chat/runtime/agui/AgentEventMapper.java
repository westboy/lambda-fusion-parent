package com.lambda.fusion.ai.chat.runtime.agui;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/**
 * AgentScope 细粒度事件到官方 AG-UI 事件的薄映射器。
 *
 * <p>本类只处理协议事件配对与工具结果缓冲，不解释业务 Run 状态，也不生成另一套快照增量。运行中快照直接投影本类输出的
 * {@link AguiEvent}。工具数据块沿用 AgentScope 官方 AG-UI 适配器语义：序列化为紧凑 JSON，多个块以换行分隔；
 * 不再另包一层平台私有结果信封。
 *
 * @author Jin
 */
public final class AgentEventMapper {

    private final String threadId;
    private final String runId;
    private final boolean enableReasoning;
    private final ToolCallTracker toolCallTracker = new ToolCallTracker();

    private String textMessageId;
    private String reasoningMessageId;
    private boolean reasoningStarted;
    private String reasoningGroupId;

    public AgentEventMapper(String threadId, String runId, boolean enableReasoning) {
        this.threadId = threadId;
        this.runId = runId;
        this.enableReasoning = enableReasoning;
    }

    /** 将一个 AgentScope 事件映射为零到多个标准 AG-UI 事件。 */
    public List<AguiEvent> map(AgentEvent event) {
        List<AguiEvent> events = new ArrayList<>();
        AgentEventType type = event.getType();
        switch (type) {
            case AGENT_START -> mapAgentStart(event, events);
            case TEXT_BLOCK_DELTA -> mapTextDelta(event, events);
            case THINKING_BLOCK_DELTA -> mapThinkingDelta(event, events);
            case TOOL_CALL_START -> mapToolCallStart(event, events);
            case TOOL_CALL_DELTA -> mapToolCallDelta(event, events);
            case TOOL_RESULT_START -> mapToolResultStart(event, events);
            case TOOL_RESULT_TEXT_DELTA -> mapToolResultTextDelta(event);
            case TOOL_RESULT_DATA_DELTA -> mapToolResultDataDelta(event);
            case TOOL_RESULT_END -> mapToolResultEnd(event, events);
            case REQUIRE_USER_CONFIRM -> mapRequireUserConfirm(event, events);
            case REQUIRE_EXTERNAL_EXECUTION -> mapRequireExternalExecution(event, events);
            case AGENT_RESULT -> mapAgentResult(event, events);
            default -> {
                // AGENT_END 由运行实例决定业务完成语义；其余事件当前没有对应的 UI 投影。
            }
        }
        return List.copyOf(events);
    }

    /**
     * 判断事件是否为运行级人机交互边界信号。
     *
     * <p>只有根 Agent（{@code source == null}）的确认/挂起才把运行推进待交互：子 Agent 的
     * {@code REQUIRE_USER_CONFIRM} 等事件会原样冒泡进父流，但其 ASKING 状态保存在子 Agent
     * 自己的状态会话中，运行级的「快照、用户决策、根 Agent 状态」三方校验永远读不到它们——
     * 若据此进入待交互，运行将停在永远无法确认的状态，只能等待超时或显式停止。
     *
     * <p>当前依赖的 AgentScope 版本不发出 {@code REQUIRE_EXTERNAL_EXECUTION} 事件：外部工具挂起以
     * 根 {@code AgentResultEvent}（{@code GenerateReason.TOOL_SUSPENDED}）的形式出现在事件流中，
     * 随后紧跟根 {@code AGENT_END}。此处统一判定两类信号，供运行实例与执行适配器复用。
     *
     * @param event 待判定的事件
     * @return 是否为运行级待交互边界信号
     */
    public static boolean isInteractionBoundary(AgentEvent event) {
        if (event.getSource() != null) {
            // 子 Agent 的确认/挂起不代表整个运行进入待交互。
            return false;
        }
        if (event.getType() == AgentEventType.REQUIRE_USER_CONFIRM
                || event.getType() == AgentEventType.REQUIRE_EXTERNAL_EXECUTION) {
            return true;
        }
        return event instanceof AgentResultEvent result
                && result.getResult() != null
                && result.getResult().getGenerateReason() == GenerateReason.TOOL_SUSPENDED;
    }

    /** 关闭本阶段已打开的文本/推理事件。 */
    public List<AguiEvent> closeOpenMessages() {
        List<AguiEvent> events = new ArrayList<>();
        closeActiveMessage(events);
        return List.copyOf(events);
    }

    private void mapAgentStart(AgentEvent event, List<AguiEvent> out) {
        if (event.getSource() == null) {
            out.add(new AguiEvent.RunStarted(threadId, runId, null, null));
        }
    }

    private void mapTextDelta(AgentEvent event, List<AguiEvent> out) {
        if (!(event instanceof TextBlockDeltaEvent textDelta)) {
            return;
        }
        String messageId = resolveId(textDelta.getReplyId());
        if (!messageId.equals(textMessageId)) {
            closeReasoningMessage(out);
            closeTextMessage(out);
            textMessageId = messageId;
            out.add(new AguiEvent.TextMessageStart(threadId, runId, messageId, "assistant"));
        }
        out.add(new AguiEvent.TextMessageContent(threadId, runId, messageId, textDelta.getDelta()));
    }

    private void mapThinkingDelta(AgentEvent event, List<AguiEvent> out) {
        if (!enableReasoning || !(event instanceof ThinkingBlockDeltaEvent thinkingDelta)) {
            return;
        }
        String messageId = resolveId(thinkingDelta.getReplyId());
        if (!messageId.equals(reasoningMessageId)) {
            closeTextMessage(out);
            if (reasoningMessageId != null) {
                out.add(new AguiEvent.ReasoningMessageEnd(threadId, runId, reasoningMessageId));
            }
            if (!reasoningStarted) {
                out.add(new AguiEvent.ReasoningStart(threadId, runId, messageId, null));
                reasoningStarted = true;
                reasoningGroupId = messageId;
            }
            reasoningMessageId = messageId;
            out.add(new AguiEvent.ReasoningMessageStart(threadId, runId, messageId, "reasoning"));
        }
        out.add(new AguiEvent.ReasoningMessageContent(threadId, runId, messageId, thinkingDelta.getDelta()));
    }

    private void mapToolCallStart(AgentEvent event, List<AguiEvent> out) {
        if (!(event instanceof ToolCallStartEvent tool)) {
            return;
        }
        closeActiveMessage(out);
        if (toolCallTracker.markStarted(tool.getToolCallId())) {
            out.add(new AguiEvent.ToolCallStart(threadId, runId, tool.getToolCallId(), tool.getToolCallName()));
        }
    }

    private void mapToolCallDelta(AgentEvent event, List<AguiEvent> out) {
        if (event instanceof ToolCallDeltaEvent tool) {
            out.add(new AguiEvent.ToolCallArgs(threadId, runId, tool.getToolCallId(), tool.getDelta()));
        }
    }

    private void mapToolResultStart(AgentEvent event, List<AguiEvent> out) {
        if (!(event instanceof ToolResultStartEvent tool)) {
            return;
        }
        closeActiveMessage(out);
        if (toolCallTracker.markStarted(tool.getToolCallId())) {
            out.add(new AguiEvent.ToolCallStart(threadId, runId, tool.getToolCallId(), tool.getToolCallName()));
        }
    }

    private void mapToolResultTextDelta(AgentEvent event) {
        if (event instanceof ToolResultTextDeltaEvent tool) {
            toolCallTracker.appendResult(tool.getToolCallId(), tool.getDelta());
        }
    }

    private void mapToolResultDataDelta(AgentEvent event) {
        if (event instanceof ToolResultDataDeltaEvent tool) {
            toolCallTracker.appendResult(tool.getToolCallId(), tool.getData());
        }
    }

    private void mapToolResultEnd(AgentEvent event, List<AguiEvent> out) {
        if (!(event instanceof ToolResultEndEvent tool)) {
            return;
        }
        out.add(new AguiEvent.ToolCallEnd(threadId, runId, tool.getToolCallId()));
        if (tool.getState() == ToolResultState.RUNNING) {
            // 外部工具挂起调用：结果语义由后续 interrupt 事件承载，不发占位结果。
            return;
        }
        out.add(new AguiEvent.ToolCallResult(
                threadId,
                runId,
                tool.getToolCallId(),
                toolCallTracker.result(tool.getToolCallId()),
                "tool",
                tool.getReplyId()));
    }

    private void mapRequireUserConfirm(AgentEvent event, List<AguiEvent> out) {
        // 子 Agent 的确认不下发运行级 RunFinished，判定见 isInteractionBoundary。
        if (event.getSource() != null || !(event instanceof RequireUserConfirmEvent confirm)) {
            return;
        }
        closeActiveMessage(out);
        out.add(new AguiEvent.RunFinished(threadId, runId, null, buildInterruptOutcome(confirm.getToolCalls())));
    }

    private void mapRequireExternalExecution(AgentEvent event, List<AguiEvent> out) {
        if (event.getSource() != null || !(event instanceof RequireExternalExecutionEvent external)) {
            return;
        }
        closeActiveMessage(out);
        List<AguiEvent.Interrupt> interrupts = external.getToolCalls().stream()
                .map(InterruptFactory::inputInterrupt)
                .toList();
        out.add(new AguiEvent.RunFinished(
                threadId, runId, null, new AguiEvent.RunFinishedInterruptOutcome(interrupts)));
    }

    /**
     * 映射根阶段结果事件：AgentScope 2.0.1 中外部工具挂起的结果消息携带
     * {@code GenerateReason.TOOL_SUSPENDED}，内容为成对的 ToolUseBlock 与挂起 ToolResultBlock。
     * 与官方 {@code AgentLifecycleEventConverter} 一致，仅对挂起结果生成输入型 Interrupt。
     *
     * @param event 阶段结果事件
     * @param out 输出事件列表
     */
    private void mapAgentResult(AgentEvent event, List<AguiEvent> out) {
        if (!(event instanceof AgentResultEvent resultEvent) || event.getSource() != null) {
            return;
        }
        Msg result = resultEvent.getResult();
        if (result == null || result.getGenerateReason() != GenerateReason.TOOL_SUSPENDED) {
            return;
        }
        Map<String, ToolUseBlock> toolUses = new LinkedHashMap<>();
        for (ContentBlock block : result.getContent()) {
            if (block instanceof ToolUseBlock toolUse && StringUtils.isNotBlank(toolUse.getId())) {
                toolUses.put(toolUse.getId(), toolUse);
            }
        }
        List<AguiEvent.Interrupt> interrupts = new ArrayList<>();
        for (ContentBlock block : result.getContent()) {
            if (block instanceof ToolResultBlock toolResult
                    && toolResult.isSuspended()
                    && StringUtils.isNotBlank(toolResult.getId())
                    && toolUses.containsKey(toolResult.getId())) {
                interrupts.add(InterruptFactory.inputInterrupt(toolUses.get(toolResult.getId())));
            }
        }
        if (interrupts.isEmpty()) {
            return;
        }
        closeActiveMessage(out);
        out.add(new AguiEvent.RunFinished(
                threadId, runId, null, new AguiEvent.RunFinishedInterruptOutcome(List.copyOf(interrupts))));
    }

    private static AguiEvent.RunFinishedInterruptOutcome buildInterruptOutcome(List<ToolUseBlock> blocks) {
        List<AguiEvent.Interrupt> interrupts = blocks.stream()
                .map(tool -> AguiBootstrapEncoder.confirmationInterrupt(tool.getId(), tool.getName()))
                .toList();
        return new AguiEvent.RunFinishedInterruptOutcome(interrupts);
    }

    private void closeActiveMessage(List<AguiEvent> out) {
        closeTextMessage(out);
        closeReasoningMessage(out);
    }

    private void closeTextMessage(List<AguiEvent> out) {
        if (textMessageId != null) {
            out.add(new AguiEvent.TextMessageEnd(threadId, runId, textMessageId));
            textMessageId = null;
        }
    }

    private void closeReasoningMessage(List<AguiEvent> out) {
        if (reasoningMessageId != null) {
            out.add(new AguiEvent.ReasoningMessageEnd(threadId, runId, reasoningMessageId));
            reasoningMessageId = null;
        }
        if (reasoningStarted) {
            out.add(new AguiEvent.ReasoningEnd(threadId, runId, reasoningGroupId));
            reasoningStarted = false;
            reasoningGroupId = null;
        }
    }

    private String resolveId(String replyId) {
        return StringUtils.defaultIfBlank(replyId, runId);
    }

    private static final class ToolCallTracker {

        private final Set<String> startedToolCalls = new HashSet<>();
        private final Map<String, StringBuilder> resultBuffers = new HashMap<>();

        boolean markStarted(String toolCallId) {
            resultBuffers.computeIfAbsent(toolCallId, ignored -> new StringBuilder());
            return startedToolCalls.add(toolCallId);
        }

        void appendResult(String toolCallId, String delta) {
            resultBuffers
                    .computeIfAbsent(toolCallId, ignored -> new StringBuilder())
                    .append(delta);
        }

        void appendResult(String toolCallId, ContentBlock content) {
            if (content == null) {
                return;
            }
            StringBuilder result = resultBuffers.computeIfAbsent(toolCallId, ignored -> new StringBuilder());
            if (!result.isEmpty()) {
                result.append('\n');
            }
            result.append(serialize(content));
        }

        String result(String toolCallId) {
            StringBuilder result = resultBuffers.get(toolCallId);
            return result == null ? "" : result.toString();
        }

        private static String serialize(ContentBlock content) {
            if (content instanceof TextBlock text) {
                return text.getText();
            }
            try {
                return JsonUtils.getJsonCodec().toJson(content);
            } catch (RuntimeException serializationFailure) {
                return content.toString();
            }
        }
    }
}
