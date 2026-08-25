package com.lambda.fusion.ai.chat.runtime;

import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.agui.AgentEventMapper;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.runtime.gateway.FusionSubagentGateway;
import com.lambda.fusion.ai.runtime.gateway.RuntimeProperty;
import com.lambda.fusion.ai.runtime.media.ExplicitToolCall;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import reactor.core.publisher.Flux;

/**
 * 对话运行与 {@link HarnessAgent} 之间的执行适配器。内部执行直接调用已选定的 Agent，不再经过 Harness 网关路由；
 * 状态会话统一使用 {@code (userId, ChatSession.id)} 标识，并在此封装事件流、中断和待确认工具调用处理。
 *
 * @author Jin
 */
@Slf4j
public final class AgentExecutionAdapter {

    private final HarnessAgent agent;
    private final String runId;
    private final String sessionId;
    private final String userId;
    private final String appId;
    private final String tenantId;
    private final FusionSubagentGateway subagentGateway;

    /**
     * 创建 Agent 执行适配器。
     *
     * @param agent Agent 实例
     * @param run 运行实体
     * @param session 会话实体
     * @param tenantId 已归一化的租户标识
     */
    public AgentExecutionAdapter(
            HarnessAgent agent,
            ChatRunEntity run,
            ChatSessionEntity session,
            String tenantId,
            FusionSubagentGateway subagentGateway) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.runId = Objects.requireNonNull(run.getId(), "run.id");
        this.sessionId = Objects.requireNonNull(run.getSessionId(), "run.sessionId");
        this.userId = session.getUserId();
        this.appId = Objects.requireNonNull(session.getAppId(), "session.appId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.subagentGateway = subagentGateway;
    }

    /** 启动事件流；显式媒体请求直接执行 AgentScope 工具，普通消息仍交给 Agent 自主编排。 */
    public Flux<AgentEvent> stream(Msg message, ToolUseBlock mediaToolCall) {
        ExplicitToolCall explicitCall =
                mediaToolCall == null ? null : new ExplicitToolCall(message, mediaToolCall, agent.getName()).marked();
        RuntimeContext.Builder contextBuilder = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .put(RuntimeProperty.KEY_RUN_ID, runId)
                .put(RuntimeProperty.KEY_TENANT_ID, tenantId)
                .put(RuntimeProperty.KEY_APP_ID, appId)
                .put(RuntimeProperty.KEY_LF_SESSION_ID, sessionId);
        if (explicitCall != null) {
            contextBuilder.put(ExplicitToolCall.class, explicitCall);
        }
        RuntimeContext context = contextBuilder.build();
        Flux<AgentEvent> source = explicitCall == null
                ? agent.streamEvents(message, context)
                : streamExplicitMediaTool(explicitCall, context);
        if (subagentGateway != null) {
            source = source.doOnNext(this::recordSubagentExposure);
        }
        return endAtHitlPhaseBoundary(source);
    }

    /**
     * 执行页面明确选择的媒体工具。工具查找、参数 Schema 校验、预置参数合并、调用追踪和异常结果转换
     * 全部委托 AgentScope {@link io.agentscope.core.tool.Toolkit}；这里只编排官方事件并维护同一会话记忆。
     */
    private Flux<AgentEvent> streamExplicitMediaTool(ExplicitToolCall explicitCall, RuntimeContext context) {
        ToolUseBlock toolCall = explicitCall.toolCall();
        Flux<AgentEvent> source = agent.streamEvents(List.of(), context);
        return Flux.defer(() -> {
            AtomicBoolean callAnnounced = new AtomicBoolean();
            AtomicBoolean toolFailed = new AtomicBoolean();
            return source.concatMap(
                            event -> {
                                if (event instanceof ToolResultEndEvent result
                                        && toolCall.getId().equals(result.getToolCallId())
                                        && result.getState() == ToolResultState.ERROR) {
                                    toolFailed.set(true);
                                }
                                if (event instanceof AgentEndEvent && event.getSource() == null && toolFailed.get()) {
                                    return Flux.error(new IllegalStateException("媒体工具执行失败: " + toolCall.getName()));
                                }
                                if (event instanceof AgentStartEvent start
                                        && event.getSource() == null
                                        && callAnnounced.compareAndSet(false, true)) {
                                    return Flux.just(
                                            event,
                                            new ToolCallStartEvent(
                                                    start.getReplyId(), toolCall.getId(), toolCall.getName()),
                                            new ToolCallDeltaEvent(
                                                    start.getReplyId(),
                                                    toolCall.getId(),
                                                    toolCall.getName(),
                                                    toolCall.getContent()),
                                            new ToolCallEndEvent(
                                                    start.getReplyId(), toolCall.getId(), toolCall.getName()));
                                }
                                return Flux.just(event);
                            },
                            1)
                    .cast(AgentEvent.class);
        });
    }

    /**
     * 在人机交互阶段（权限确认或外部工具挂起）的根 {@code AGENT_END} 处结束当前源流。
     *
     * <p>AgentScope 会先持久化挂起状态（{@code ASKING} 或 {@code TOOL_SUSPENDED}，挂起结果对不写入
     * 记忆上下文），再发送根 {@code AGENT_END}，随后才通过 {@code concatWith} 订阅记忆冲刷和整理尾部。
     * 此处等待根结束事件后再取消上游，既保留可恢复的挂起状态，又避免记忆模型阻塞待交互快照落库。
     * 普通最终回答和子 Agent 事件不受影响。
     *
     * <p>注意 AgentScope 2.0.1 不发出 {@code REQUIRE_EXTERNAL_EXECUTION} 事件，外部工具挂起以
     * 根 {@code AgentResultEvent}（{@code GenerateReason.TOOL_SUSPENDED}）的形式出现，判定逻辑见
     * {@link AgentEventMapper#isInteractionBoundary}。
     */
    private Flux<AgentEvent> endAtHitlPhaseBoundary(Flux<AgentEvent> source) {
        return Flux.defer(() -> {
            AtomicBoolean awaitingInteraction = new AtomicBoolean();
            return source.doOnNext(event -> {
                        if (AgentEventMapper.isRootInteractionBoundary(event)) {
                            awaitingInteraction.set(true);
                        }
                    })
                    .takeUntil(event -> awaitingInteraction.get() && isRootEvent(event, AgentEventType.AGENT_END));
        });
    }

    private static boolean isRootEvent(AgentEvent event, AgentEventType type) {
        return event.getType() == type && event.getSource() == null;
    }

    private void recordSubagentExposure(AgentEvent event) {
        if (event instanceof SubagentExposedEvent exposedEvent) {
            subagentGateway.recordExposure(exposedEvent, appId, tenantId, userId, sessionId);
        }
    }

    /** 从上下文末尾倒序查找最后一条助手消息；不存在时返回 {@code null}。 */
    private static Msg lastAssistantMessage(List<Msg> context) {
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg message = context.get(i);
            if (message.getRole() == MsgRole.ASSISTANT) {
                return message;
            }
        }
        return null;
    }

    /**
     * 读取 Agent 状态中当前待确认的工具调用。
     *
     * <p>与 AgentScope 的 {@code getPendingToolUseIds} 保持一致，只检查最后一条助手消息中的 ASKING 块。
     * 该范围是快照、用户决策和 Agent 状态三方校验的共同基准；若扫描更早消息，遗留块可能造成误判。
     *
     * @return 当前待确认工具调用
     * @throws AiBusinessException Agent 状态不可用或不存在待确认工具调用
     */
    public List<ToolUseBlock> readAskingToolBlocks() {
        try {
            Msg lastAssistant = getLastAssistant();
            List<ToolUseBlock> asking = lastAssistant.getContentBlocks(ToolUseBlock.class).stream()
                    .filter(tool -> tool.getState() == ToolCallState.ASKING)
                    .toList();
            if (asking.isEmpty()) {
                // 最后一条助手消息没有 ASKING 块，表示当前不存在待确认批次，不再回查历史消息。
                throw new AiBusinessException(AiErrorCode.CHAT_RUN_CONFIRM_CONTEXT_UNAVAILABLE, runId);
            }
            return asking;
        } catch (AiBusinessException exception) {
            throw exception;
        } catch (RuntimeException error) {
            log.warn("读取 HITL Agent状态失败: runId={}", runId, error);
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_CONFIRM_CONTEXT_UNAVAILABLE, runId);
        }
    }

    private @NonNull Msg getLastAssistant() {
        var state = getAgentState();
        Msg lastAssistant = lastAssistantMessage(state.getContext());
        if (lastAssistant == null) {
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_CONFIRM_CONTEXT_UNAVAILABLE, runId);
        }
        return lastAssistant;
    }

    /** 中断当前 Agent 状态会话。 */
    public void interrupt() {
        agent.getDelegate().interrupt(userId, sessionId);
    }

    /** 当前 Agent 名称（构造 TOOL 结果消息使用）。 */
    public String agentName() {
        return agent.getName();
    }

    /**
     * 读取 Agent 状态中当前挂起等待用户输入的工具调用。
     *
     * <p>与 AgentScope 的 {@code getPendingToolUseIds} 语义一致：挂起结果对不写入记忆上下文，
     * 因此最后一条助手消息中缺少对应结果且非 ASKING 的工具调用视为挂起。该范围与快照
     * {@code pendingInputs}、用户提交输入三方校验的基准一致。
     *
     * @return 当前挂起的工具调用
     * @throws AiBusinessException Agent 状态不可用或不存在挂起调用
     */
    public List<ToolUseBlock> readSuspendedToolBlocks() {
        try {
            var state = getAgentState();
            Msg lastAssistant = lastAssistantMessage(state.getContext());
            if (lastAssistant == null) {
                throw new AiBusinessException(AiErrorCode.CHAT_RUN_CONFIRM_CONTEXT_UNAVAILABLE, runId);
            }
            Set<String> resultIds = state.getContext().stream()
                    .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                    .map(ToolResultBlock::getId)
                    .collect(Collectors.toSet());
            List<ToolUseBlock> suspended = lastAssistant.getContentBlocks(ToolUseBlock.class).stream()
                    .filter(tool -> tool.getState() != ToolCallState.ASKING)
                    .filter(tool -> !resultIds.contains(tool.getId()))
                    .toList();
            if (suspended.isEmpty()) {
                throw new AiBusinessException(AiErrorCode.CHAT_RUN_CONFIRM_CONTEXT_UNAVAILABLE, runId);
            }
            return suspended;
        } catch (AiBusinessException exception) {
            throw exception;
        } catch (RuntimeException error) {
            log.warn("读取挂起工具调用失败: runId={}", runId, error);
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_CONFIRM_CONTEXT_UNAVAILABLE, runId);
        }
    }

    private @NonNull AgentState getAgentState() {
        var state = agent.getDelegate().getAgentState(userId, sessionId);
        if (state == null || state.getContext() == null) {
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_CONFIRM_CONTEXT_UNAVAILABLE, runId);
        }
        return state;
    }

    /**
     * 将状态会话中遗留的工具调用补写为用户拒绝结果并保存。
     *
     * <p>ASKING 或缺少结果的工具调用会阻塞同一状态会话的后续请求。运行停止、确认超时或异常终结时调用本方法，
     * 可闭合未决调用并恢复会话可用性；不存在未决调用时不产生修改。
     */
    public void denyPendingToolCalls() {
        ReActAgent delegate = agent.getDelegate();
        AgentState state = delegate.getAgentState(userId, sessionId);
        if (state == null || state.getContext() == null) {
            return;
        }
        // 与 AgentScope 保持同一判定范围：最后一条助手消息中缺少对应结果的工具调用视为未决。
        Set<String> resultIds = state.getContext().stream()
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .map(ToolResultBlock::getId)
                .collect(Collectors.toSet());
        Msg lastAssistant = lastAssistantMessage(state.getContext());
        if (lastAssistant == null) {
            return;
        }
        List<ToolUseBlock> pending = lastAssistant.getContentBlocks(ToolUseBlock.class).stream()
                .filter(tool -> !resultIds.contains(tool.getId()))
                .toList();
        if (pending.isEmpty()) {
            return;
        }
        for (ToolUseBlock tool : pending) {
            ToolResultBlock denied = ToolResultBlock.text("Permission denied by user")
                    .withIdAndName(tool.getId(), tool.getName())
                    .withState(ToolResultState.DENIED);
            state.contextMutable().add(ToolResultMessageBuilder.buildToolResultMsg(denied, tool, agent.getName()));
        }
        delegate.saveAgentState(userId, sessionId);
    }
}
