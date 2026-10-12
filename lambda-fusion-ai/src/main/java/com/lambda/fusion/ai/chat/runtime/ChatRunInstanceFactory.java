package com.lambda.fusion.ai.chat.runtime;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEventStore;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshotCodec;
import com.lambda.fusion.ai.chat.service.ChatRunStateService;
import com.lambda.fusion.ai.chat.service.ChatRunStepService;
import com.lambda.fusion.ai.runtime.AgentFactory;
import com.lambda.fusion.ai.runtime.gateway.FusionSubagentGateway;
import com.lambda.fusion.ai.runtime.workspace.WorkspaceAuditRecorder;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 执行实例工厂：统一构造 {@link ChatRunInstance}。带 Agent 的实例只用于新 Run 或持久化 HITL 的显式继续，
 * 无 Agent 的纯落库实例仅用于终结。工厂只组装对象，不登记本地执行状态或事件缓冲；本地阶段句柄与
 * 容量生命周期由协调器管理。
 *
 * @author Jin
 */
@Component
@RequiredArgsConstructor
public class ChatRunInstanceFactory {

    private final ChatRunStateService runService;
    private final ChatRunEventStore eventStore;
    private final AgentFactory agentFactory;
    private final WorkspaceAuditRecorder workspaceAuditRecorder;
    private final ChatRunStepService stepService;
    private final ObjectProvider<FusionSubagentGateway> subagentGatewayProvider;
    private final AiProperties properties;
    /**
     * 共享阻塞工作执行器（虚拟线程）：承载执行实例的 DB 提交、工作区审计等阻塞 I/O，
     * 使定时调度器回归纯计时职责。
     */
    private final ExecutorService blockingExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 构造带 Agent 的完整执行实例。
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
    public ChatRunInstance createAgentBacked(
            ChatRunEntity run, ChatSessionEntity session, ScheduledExecutorService scheduler) {
        return newInstance(run, session, scheduler, createAgentExecution(run, session));
    }

    /** 构建执行适配器：按需获取或构建 Agent，装配子智能体网关（若可用）并绑定租户上下文。 */
    private AgentExecutionAdapter createAgentExecution(ChatRunEntity run, ChatSessionEntity session) {
        String tenantId = tenantId(session);
        HarnessAgent agent = agentFactory.getOrBuildForChatRun(session.getAppId(), tenantId);
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
    public ChatRunInstance createTerminalOnly(
            ChatRunEntity run, ChatSessionEntity session, ScheduledExecutorService scheduler) {
        return newInstance(run, session, scheduler, null);
    }

    /** 组装实例的公共部分：解码持久化快照，从步骤水位续接序号，聚合所有构造依赖。 */
    private ChatRunInstance newInstance(
            ChatRunEntity run,
            ChatSessionEntity session,
            ScheduledExecutorService scheduler,
            AgentExecutionAdapter agentExecution) {
        ChatRunSnapshot snapshot = ChatRunSnapshotCodec.decode(run.getSnapshotJson());
        ChatRunSnapshotAccumulator chatExecutionSnapshotBuilder =
                new ChatRunSnapshotAccumulator(snapshot, stepService.maxStepNo(run.getId()));
        return new ChatRunInstance(
                runService,
                eventStore,
                properties,
                scheduler,
                blockingExecutor,
                workspaceAuditRecorder,
                stepService,
                run,
                session,
                agentExecution,
                chatExecutionSnapshotBuilder);
    }

    /** 关机时关闭共享阻塞执行器。 */
    @PreDestroy
    public void shutdown() {
        blockingExecutor.shutdownNow();
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
