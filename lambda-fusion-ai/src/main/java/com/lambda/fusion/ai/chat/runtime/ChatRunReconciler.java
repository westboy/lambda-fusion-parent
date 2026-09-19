package com.lambda.fusion.ai.chat.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.ai.AiConstants.ChatRunFailureCode;
import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.mapper.ChatRunMapper;
import com.lambda.fusion.ai.chat.mapper.ChatSessionMapper;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshotCodec;
import com.lambda.fusion.core.utils.TenantUtils;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Run 对账任务：周期收敛两类不会再推进的 {@code RUNNING} 记录——执行节点崩溃/重启留下的
 * 孤儿运行，以及 HITL 待交互（待确认/待输入）超时的运行。
 *
 * <p>收敛动作复用终态提交链路，数据库终态 CAS 是唯一裁决：多节点同时运行时重复收敛只会 CAS
 * 落败，不引入分布式锁、租约或节点发现。活跃运行由周期检查点持续刷新 {@code updated_at}，且配置
 * 约束 {@code orphanTimeoutSeconds > maxRunDurationSeconds} 保证活跃运行先于交互超时自我终结，
 * 对账不会误杀。新建但尚未写入过任何行的记录 {@code updated_at} 为空，以 {@code started_at} 起算。
 *
 * @author Jin
 */
@Slf4j
@RequiredArgsConstructor
public class ChatRunReconciler {

    private final ChatRunMapper runMapper;
    private final ChatSessionMapper sessionMapper;
    private final ChatExecutionInstanceFactory instanceFactory;
    private final AiProperties properties;
    /** 正在收敛中的 Run（含终结失败的退避重试）：防止数据库故障期间每轮重复创建终结实例堆积。 */
    private final Set<String> converging = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "chat-run-reconciler");
        thread.setDaemon(true);
        return thread;
    });

    /** 启动周期对账；首轮延迟一个间隔，避开启动高峰。 */
    public void start() {
        AiProperties.Chat.Run.Reconcile cfg = properties.getChat().getRun().getReconcile();
        scheduler.scheduleWithFixedDelay(
                this::runSafely, cfg.getIntervalSeconds(), cfg.getIntervalSeconds(), TimeUnit.SECONDS);
        log.info(
                "Run对账任务已启动: intervalSeconds={}, orphanTimeoutSeconds={}, awaitInteractionTimeoutSeconds={}",
                cfg.getIntervalSeconds(),
                cfg.getOrphanTimeoutSeconds(),
                cfg.getAwaitInteractionTimeoutSeconds());
    }

    /** 单轮对账：整体异常只告警，等待下一轮。 */
    void runSafely() {
        try {
            reconcile();
        } catch (RuntimeException failure) {
            log.warn("Run对账本轮失败，等待下一轮: reason={}", failure.getMessage(), failure);
        }
    }

    /**
     * 取出全部可能被判定为停滞的 RUNNING 记录（超过两类阈值的较小者未刷新），逐条按其类别
     * 的阈值复核后收敛。
     */
    void reconcile() {
        AiProperties.Chat.Run.Reconcile cfg = properties.getChat().getRun().getReconcile();
        LocalDateTime staleBefore = LocalDateTime.now()
                .minusSeconds(Math.min(cfg.getOrphanTimeoutSeconds(), cfg.getAwaitInteractionTimeoutSeconds()));
        List<ChatRunEntity> stale = runMapper.selectList(new LambdaQueryWrapper<ChatRunEntity>()
                .eq(ChatRunEntity::getStatus, ChatRunStatus.RUNNING.name())
                .and(wrapper -> wrapper.lt(ChatRunEntity::getUpdatedAt, staleBefore)
                        .or()
                        .isNull(ChatRunEntity::getUpdatedAt))
                .orderByAsc(ChatRunEntity::getUpdatedAt)
                .last("LIMIT " + cfg.getBatchSize()));
        for (ChatRunEntity run : stale) {
            reconcileOne(run, cfg);
        }
    }

    /** 按类别阈值复核并收敛单条停滞运行；单条失败只告警，不影响本批其它记录。 */
    private void reconcileOne(ChatRunEntity run, AiProperties.Chat.Run.Reconcile cfg) {
        // 正在收敛中（含终结失败的退避重试）的 Run 跳过，防止数据库故障期间重复实例堆积。
        if (!converging.add(run.getId())) {
            return;
        }
        try {
            ChatSessionEntity session = sessionMapper.selectById(run.getSessionId());
            if (session == null) {
                log.warn("Run对账找不到会话，跳过本轮: runId={}, sessionId={}", run.getId(), run.getSessionId());
                converging.remove(run.getId());
                return;
            }
            boolean awaiting = hasPendingInteraction(run);
            long timeoutSeconds = awaiting ? cfg.getAwaitInteractionTimeoutSeconds() : cfg.getOrphanTimeoutSeconds();
            LocalDateTime lastProgress = run.getUpdatedAt() == null ? run.getStartedAt() : run.getUpdatedAt();
            if (lastProgress == null || lastProgress.isAfter(LocalDateTime.now().minusSeconds(timeoutSeconds))) {
                converging.remove(run.getId());
                return;
            }
            TenantUtils.withTenant(session.getTenantId(), () -> {
                converge(run, session, awaiting);
                return null;
            });
        } catch (RuntimeException failure) {
            converging.remove(run.getId());
            log.warn("Run对账收敛失败，等待下一轮: runId={}", run.getId(), failure);
        }
    }

    /**
     * 收敛一条停滞运行：待交互超时报 {@code AWAIT_TIMEOUT}，孤儿运行报 {@code INTERRUPTED}。
     * 待交互运行与 stop 路径同语义——优先加载 AgentScope 状态以在终结时补写拒绝结果，
     * 不可用时降级为纯业务终结。终结携带快照版本围栏：对账窗口内运行被并发推进
     * （如用户刚确认）时放弃，避免误杀刚恢复的运行。
     */
    private void converge(ChatRunEntity run, ChatSessionEntity session, boolean awaiting) {
        ChatRunFailureCode code = awaiting ? ChatRunFailureCode.AWAIT_TIMEOUT : ChatRunFailureCode.INTERRUPTED;
        String message = awaiting ? "等待用户确认/输入超时，已自动结束" : "执行节点故障或服务重启，运行已中断";
        ChatExecutionInstance instance = awaiting ? tryCreateAgentBacked(run, session) : null;
        if (instance == null) {
            instance = instanceFactory.createTerminalOnly(run, session, scheduler);
        }
        ChatExecutionInstance.FenceFinalizeOutcome outcome = instance.finalizeFailedIfUnchanged(code, message);
        switch (outcome) {
            case SUPERSEDED -> {
                converging.remove(run.getId());
                log.info("Run对账收敛放弃（运行已被并发推进）: runId={}", run.getId());
            }
            case SETTLED -> {
                converging.remove(run.getId());
                log.info("Run{}已收敛: runId={}", awaiting ? "待交互超时" : "孤儿运行", run.getId());
            }
            case RETRYING -> {
                // 提交失败退避重试中：保留去重标记，实例的终态提交信号完成时摘除。
                ChatExecutionInstance tracked = instance;
                tracked.terminalCommittedSignal().whenComplete((ignored, failure) -> converging.remove(run.getId()));
                log.info("Run{}收敛提交失败，退避重试中: runId={}", awaiting ? "待交互超时" : "孤儿运行", run.getId());
            }
            default -> {
                // 枚举全分支已覆盖。
            }
        }
    }

    /** 尝试为待交互运行构造带 Agent 的实例；AgentScope 状态不可用时返回 {@code null} 走降级。 */
    private ChatExecutionInstance tryCreateAgentBacked(ChatRunEntity run, ChatSessionEntity session) {
        try {
            return instanceFactory.createAgentBacked(run, session, scheduler);
        } catch (RuntimeException agentUnavailable) {
            log.warn("Run收敛无法清理 AgentScope 状态，降级为纯业务终结: runId={}", run.getId(), agentUnavailable);
            return null;
        }
    }

    /** 判定运行是否处于 HITL 待交互；快照损坏的运行无法正常确认或恢复，按孤儿路径处理。 */
    private boolean hasPendingInteraction(ChatRunEntity run) {
        try {
            return ChatRunSnapshotCodec.decode(run.getSnapshotJson()).hasPendingInteraction();
        } catch (RuntimeException decodeFailure) {
            log.warn("Run快照解析失败，按孤儿路径判定: runId={}", run.getId(), decodeFailure);
            return false;
        }
    }

    /** 停机时中断调度线程池；已提交的单轮任务允许自然结束。 */
    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }
}
