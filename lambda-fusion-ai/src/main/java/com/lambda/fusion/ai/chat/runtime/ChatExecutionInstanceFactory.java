package com.lambda.fusion.ai.chat.runtime;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEventStore;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshotCodec;
import com.lambda.fusion.ai.chat.service.ChatRunStateService;
import com.lambda.fusion.ai.runtime.AgentFactory;
import com.lambda.fusion.ai.runtime.gateway.FusionSubagentGateway;
import com.lambda.fusion.ai.runtime.workspace.WorkspaceAuditRecorder;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.concurrent.ScheduledExecutorService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 执行实例工厂：统一构造 {@link ChatExecutionInstance}。带 Agent 的实例只用于新 Run 或持久化 HITL 的显式继续，
 * 无 Agent 的纯落库实例仅用于终结；构造依赖集中在工厂，调度器由协调器按次传入，
 * 活动实例注册表由协调器的注册表组件独占，工厂不持有、查询或修改。
 *
 * @author Jin
 */
@Component
@RequiredArgsConstructor
public class ChatExecutionInstanceFactory {

    private final ChatRunStateService runService;
    private final ChatRunEventStore eventStore;
    private final AgentFactory agentFactory;
    private final WorkspaceAuditRecorder workspaceAuditRecorder;
    private final ObjectProvider<FusionSubagentGateway> subagentGatewayProvider;
    private final AiProperties properties;

    /**
     * 构造带 Agent 的完整执行实例，并把该 Run 注册为本地活跃运行。
     *
     * <p>适用场景：新 Run 启动，或为已持久化在 HITL 边界的 Run 重建本地确认上下文
     * （仅在用户显式确认或停止时调用）。构造时获取或构建 Agent，并完成子智能体网关装配；
     * 不重启旧阶段、不接管正在执行的工具。
     *
     * @param run 运行实体
     * @param session 会话实体
     * @param scheduler 调度器，由协调器按次传入，实例不自建
     * @return 带 Agent 的执行实例
     */
    public ChatExecutionInstance createAgentBacked(
            ChatRunEntity run, ChatSessionEntity session, ScheduledExecutorService scheduler) {
        eventStore.registerLocalRun(run.getId());
        return newInstance(run, session, scheduler, createAgentExecution(run, session));
    }

    /** 构建执行适配器：按需获取或构建 Agent，装配子智能体网关（若可用）并绑定租户上下文。 */
    private AgentExecutionAdapter createAgentExecution(ChatRunEntity run, ChatSessionEntity session) {
        String tenantId = tenantId(session);
        HarnessAgent agent = agentFactory.getOrBuild(session.getAppId(), tenantId);
        FusionSubagentGateway subagentGateway = subagentGatewayProvider.getIfAvailable();
        if (subagentGateway != null) {
            subagentGateway.configureAgent(agent);
        }
        return new AgentExecutionAdapter(agent, run, session, tenantId, subagentGateway);
    }

    /**
     * 构造无 Agent 的纯终结实例：仅用于落终态，不闭合或恢复 Agent 状态，
     * 也不会把 Run 注册为本地活跃运行。
     *
     * @param run 运行实体
     * @param session 会话实体
     * @param scheduler 调度器，由协调器按次传入
     * @return 无 Agent 的终结实例
     */
    public ChatExecutionInstance createTerminalOnly(
            ChatRunEntity run, ChatSessionEntity session, ScheduledExecutorService scheduler) {
        return newInstance(run, session, scheduler, null);
    }

    /** 组装实例的公共部分：解码持久化快照，聚合所有构造依赖。 */
    private ChatExecutionInstance newInstance(
            ChatRunEntity run,
            ChatSessionEntity session,
            ScheduledExecutorService scheduler,
            AgentExecutionAdapter agentExecution) {
        ChatExecutionSnapshotBuilder chatExecutionSnapshotBuilder =
                new ChatExecutionSnapshotBuilder(ChatRunSnapshotCodec.decode(run.getSnapshotJson()));
        return new ChatExecutionInstance(
                runService,
                eventStore,
                properties,
                scheduler,
                workspaceAuditRecorder,
                run,
                session,
                agentExecution,
                chatExecutionSnapshotBuilder);
    }

    /**
     * 获取运行时租户标识。
     *
     * @param session 会话实体
     * @return 租户标识；会话未指定时返回 {@code default}
     */
    static String tenantId(ChatSessionEntity session) {
        return StringUtils.defaultIfBlank(session.getTenantId(), "default");
    }
}
