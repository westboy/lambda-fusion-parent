package com.lambda.fusion.ai.chat.runtime;

import com.lambda.fusion.ai.AiConstants.ChatRunFailureCode;
import com.lambda.fusion.ai.AiConstants.ChatRunFinishReason;
import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.model.ConfirmToolCall;
import com.lambda.fusion.ai.chat.model.ConfirmTransition;
import com.lambda.fusion.ai.chat.model.SubmitToolInput;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.agui.AgentEventMapper;
import com.lambda.fusion.ai.chat.runtime.agui.AguiBootstrapEncoder;
import com.lambda.fusion.ai.chat.runtime.agui.AguiBootstrapModel;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEventStore;
import com.lambda.fusion.ai.chat.runtime.media.ToolResultMediaReference;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshotSanitizer;
import com.lambda.fusion.ai.chat.runtime.validator.ConfirmationValidator;
import com.lambda.fusion.ai.chat.runtime.validator.ToolInputValidator;
import com.lambda.fusion.ai.chat.service.ChatRunStateService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.runtime.workspace.WorkspaceAuditRecorder;
import com.lambda.fusion.core.utils.TenantUtils;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import reactor.core.Disposable;

/**
 * 单个对话运行的执行实例：持有 Agent 事件流、维护执行快照并提交运行终态。
 *
 * <p>业务终态通过持久化状态和终态事件对外发布；{@code drainedSignal} 仅表示最终阶段的源流与后处理
 * （沙箱释放、工作区审计）均已结束，供协调器摘除实例和关机等待。普通业务终态不会取消底层订阅，
 * 记忆尾部仍可继续执行；HITL 阶段由执行适配器在根
 * {@code AGENT_END} 后结束交互源流，避免记忆尾部阻塞待确认状态。状态由实例锁和原子变量共同协调。
 *
 * @author Jin
 */
@Slf4j
public final class ChatExecutionInstance {

    /**
     * 完整消息/附件投影的最大尝试次数；耗尽后切换到不含投影的最小失败终结。
     */
    private static final int MAX_PROJECTION_FINALIZE_ATTEMPTS = 3;

    private final ChatRunStateService runService;
    private final ChatRunEventStore eventStore;
    private final AiProperties properties;
    private final ScheduledExecutorService scheduler;
    private final WorkspaceAuditRecorder workspaceAuditRecorder;
    private final CompletableFuture<Void> drainedSignal = new CompletableFuture<>();
    private final CompletableFuture<Void> localPhaseDrainedSignal = new CompletableFuture<>();
    private final ChatExecutionFinalizer chatExecutionFinalizer;
    private final AgentStreamLifecycle agentStreamLifecycle;

    private final ChatRunEntity run;
    private final ChatSessionEntity session;
    private final AgentExecutionAdapter agentExecutionAdapter;
    private final ChatExecutionSnapshotBuilder accumulator;
    private boolean rootAgentEnded;
    private boolean terminal;
    /**
     * 终态 DB 提交进行中：提交已移出实例锁，此标志在锁内封住 confirm/submitInput 的推进窗口。
     */
    private boolean finalizeCommitInProgress;

    private boolean sourceActive;
    private int finalizeAttempts;
    private int fallbackFinalizeAttempts;
    private volatile long phaseStartedAtMillis = System.currentTimeMillis();
    private boolean checkpointDirty;
    /**
     * 最近一次成功写入数据库的快照版本号：检查点以它为 CAS 前置，写入成功后推进；
     * 待交互提交与阶段推进（confirm）落库后同步刷新。
     */
    private long persistedSnapshotVersion;
    /**
     * 快照内容代际：每次有增量事件应用进累加器时递增，用于识别检查点写库期间到达的新增量。
     */
    private long snapshotGeneration;

    private ScheduledFuture<?> checkpointTask;
    private AgentEventMapper eventMapper;
    private boolean awaitingInteraction;
    private List<AguiEvent> pendingInteractionEvents;

    /**
     * 创建执行实例。
     *
     * @param runService             运行状态服务
     * @param eventStore             运行事件存储
     * @param properties             AI 模块配置
     * @param scheduler              定时任务执行器
     * @param workspaceAuditRecorder 工作区审计记录器
     * @param run                    运行实体
     * @param session                会话实体
     * @param agentExecutionAdapter  Agent 执行适配器；仅执行终结流程时可为 {@code null}
     * @param accumulator            快照累加器
     */
    ChatExecutionInstance(
            ChatRunStateService runService,
            ChatRunEventStore eventStore,
            AiProperties properties,
            ScheduledExecutorService scheduler,
            WorkspaceAuditRecorder workspaceAuditRecorder,
            ChatRunEntity run,
            ChatSessionEntity session,
            AgentExecutionAdapter agentExecutionAdapter,
            ChatExecutionSnapshotBuilder accumulator) {
        this.runService = runService;
        this.eventStore = eventStore;
        this.properties = properties;
        this.scheduler = scheduler;
        this.workspaceAuditRecorder = workspaceAuditRecorder;
        this.run = run;
        this.session = session;
        this.agentExecutionAdapter = agentExecutionAdapter;
        this.accumulator = accumulator;
        this.persistedSnapshotVersion = run.getSnapshotVersion() == null ? 0L : run.getSnapshotVersion();
        this.chatExecutionFinalizer = new ChatExecutionFinalizer(runService, eventStore, properties);
        this.agentStreamLifecycle = new AgentStreamLifecycle(run.getId(), scheduler, properties);
        this.eventMapper = new AgentEventMapper(
                run.getSessionId(),
                run.getAguiRunId(),
                properties.getChat().getRun().isEnableReasoning());
    }

    public ChatRunEntity run() {
        return run;
    }

    public ChatSessionEntity session() {
        return session;
    }

    public void runInTenant(Runnable task) {
        TenantUtils.withTenant(session.getTenantId(), task);
    }

    /**
     * 在实例锁内处理用户确认。只有旧阶段源流已经完整排空、快照存在待确认投影时才校验并推进；
     * AgentScope ASKING 是底层确认状态，ChatRun 始终保持 RUNNING。已处理的旧阶段按幂等重放返回。
     *
     * @return 迁移结果；{@code resumed=false} 仅表示来源阶段已经被处理
     * @throws AiBusinessException 状态/阶段冲突、确认上下文不可用、决策非法或三方工具调用不一致
     */
    public synchronized ConfirmTransition confirm(ConfirmToolCall command) {
        Integer sourcePhaseNo = interactionSourcePhase(command == null ? null : command.getPhaseNo(), "确认");
        if (sourcePhaseNo == null) {
            return new ConfirmTransition(run, session, false);
        }
        ChatRunSnapshot snapshot = accumulator.buildSnapshot();
        requireInteractionWindow(sourcePhaseNo, snapshot.pendingTools().isEmpty());

        Msg confirmMessage = validateAndBuildMessage(command, snapshot.pendingTools());
        return getConfirmTransition(sourcePhaseNo, snapshot, confirmMessage);
    }

    @NonNull
    private ConfirmTransition getConfirmTransition(
            Integer sourcePhaseNo, ChatRunSnapshot snapshot, Msg confirmMessage) {
        ConfirmTransition transition = runService.advanceConfirmation(run, session, sourcePhaseNo, snapshot);
        syncRun(transition.run());
        if (!transition.resumed()) {
            return transition;
        }
        try {
            beginConfirmedPhase();
            startPhase(confirmMessage);
        } catch (RuntimeException startFailure) {
            finalizeFailed(ChatRunFailureCode.START_FAILED, ChatRunSnapshotSanitizer.safeMessage(startFailure));
        }
        return transition;
    }

    /**
     * 在实例锁内处理用户输入提交。只有旧阶段源流已经完整排空、快照存在待输入投影时才校验并推进；
     * AgentScope 挂起（TOOL_SUSPENDED）是底层状态，ChatRun 始终保持 RUNNING。已处理的旧阶段按幂等重放返回。
     *
     * @return 迁移结果；{@code resumed=false} 仅表示来源阶段已经被处理
     * @throws AiBusinessException 状态/阶段冲突、挂起上下文不可用、输入非法或三方工具调用不一致
     */
    public synchronized ConfirmTransition submitInput(SubmitToolInput command) {
        Integer sourcePhaseNo = interactionSourcePhase(command == null ? null : command.getPhaseNo(), "输入");
        if (sourcePhaseNo == null) {
            return new ConfirmTransition(run, session, false);
        }
        ChatRunSnapshot snapshot = accumulator.buildSnapshot();
        requireInteractionWindow(sourcePhaseNo, snapshot.pendingInputs().isEmpty());

        Msg inputMessage = validateAndBuildInputMessage(command, snapshot.pendingInputs());
        return getConfirmTransition(sourcePhaseNo, snapshot, inputMessage);
    }

    /**
     * 校验交互命令的来源阶段：阶段号缺失直接拒绝；来源阶段已被处理时记录幂等重放并返回 {@code null}。
     */
    private Integer interactionSourcePhase(Integer sourcePhaseNo, String action) {
        if (sourcePhaseNo == null) {
            throw new AiBusinessException(AiErrorCode.INVALID_PARAMETER, "phaseNo不能为空");
        }
        if (run.getPhaseNo() > sourcePhaseNo) {
            log.info(
                    "Run{}幂等重放，来源阶段已处理: runId={}, sourcePhaseNo={}, currentPhaseNo={}",
                    action,
                    run.getId(),
                    sourcePhaseNo,
                    run.getPhaseNo());
            return null;
        }
        return sourcePhaseNo;
    }

    /**
     * 校验当前允许进入交互推进：阶段一致、运行中、不在挂起交互或终结提交窗口，快照存在待处理项且
     * 旧阶段源流已完整排空。confirm 与 submitInput 共用同一守护，必须在实例锁内调用以保证与事件回调
     * 的状态判定同帧。
     */
    private void requireInteractionWindow(int sourcePhaseNo, boolean pendingInteractionEmpty) {
        if (!Objects.equals(run.getPhaseNo(), sourcePhaseNo)
                || !ChatRunStatus.RUNNING.name().equals(run.getStatus())
                || awaitingInteraction
                || finalizeCommitInProgress
                || pendingInteractionEmpty
                || sourceActive) {
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_STATE_CONFLICT, run.getStatus());
        }
    }

    /**
     * 校验输入命令并构造恢复消息：委托 {@link ToolInputValidator} 读取 Agent 当前挂起工具调用，
     * 要求快照、输入、Agent 三方 ID 一致，且输入值满足 responseSchema。
     *
     * @param command       用户输入命令
     * @param pendingInputs 当前快照的待输入投影
     * @return 携带用户输入结果的恢复消息
     * @throws AiBusinessException 挂起上下文不可用、输入非法或三方不一致
     */
    private Msg validateAndBuildInputMessage(
            SubmitToolInput command, List<ChatRunSnapshot.PendingInput> pendingInputs) {
        if (agentExecutionAdapter == null) {
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_CONFIRM_CONTEXT_UNAVAILABLE, run.getId());
        }
        return ToolInputValidator.validateAndBuildMessage(
                run,
                agentExecutionAdapter.agentName(),
                pendingInputs,
                command.getInputs(),
                agentExecutionAdapter::readSuspendedToolBlocks);
    }

    /**
     * 校验确认命令并构造确认消息：委托 {@link ConfirmationValidator} 读取 Agent 当前 ASKING 工具调用，
     * 要求快照、决策、Agent 三方 ID 一致。
     *
     * @param command      用户确认命令
     * @param pendingTools 当前快照的待确认工具投影
     * @return 携带确认结果的下一阶段输入消息
     * @throws AiBusinessException 确认上下文不可用、决策非法或三方不一致
     */
    private Msg validateAndBuildMessage(ConfirmToolCall command, List<ChatRunSnapshot.ToolCall> pendingTools) {
        if (agentExecutionAdapter == null) {
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_CONFIRM_CONTEXT_UNAVAILABLE, run.getId());
        }
        return ConfirmationValidator.validateAndBuildMessage(
                run, pendingTools, command.getDecisions(), agentExecutionAdapter::readAskingToolBlocks);
    }

    /**
     * 将数据库迁移后的运行状态同步到实例内存对象（含快照版本围栏）。
     */
    private void syncRun(ChatRunEntity updated) {
        run.setStatus(updated.getStatus());
        run.setPhaseNo(updated.getPhaseNo());
        run.setAguiRunId(updated.getAguiRunId());
        run.setSnapshotVersion(updated.getSnapshotVersion());
        persistedSnapshotVersion = updated.getSnapshotVersion() == null ? 0L : updated.getSnapshotVersion();
    }

    /**
     * CAS 成功后切换累加器与事件解释器到新阶段。
     */
    private void beginConfirmedPhase() {
        cancelCheckpointTask();
        accumulator.beginPhase(run.getAguiRunId(), run.getPhaseNo());
        checkpointDirty = false;
        eventMapper = new AgentEventMapper(
                run.getSessionId(),
                run.getAguiRunId(),
                properties.getChat().getRun().isEnableReasoning());
    }

    /**
     * 启动一个 Agent 执行阶段。交互超时仅覆盖从订阅开始到根 {@code AGENT_END} 到达的区间：
     * 订阅时注册 {@code max-run-duration} 截止任务，根事件到达后立即取消。普通阶段的底层订阅在业务终态后仍保留，
     * 直到不受交互超时约束的记忆尾部自然结束；HITL 阶段的适配流在根事件处结束。
     *
     * @param message 阶段输入消息
     */
    public synchronized void startPhase(Msg message) {
        if (terminal || isRunning()) {
            return;
        }
        if (agentExecutionAdapter == null) {
            finalizeFailed(ChatRunFailureCode.START_FAILED, "Agent未能初始化");
            return;
        }
        rootAgentEnded = false;
        sourceActive = true;
        agentStreamLifecycle.clear();
        phaseStartedAtMillis = System.currentTimeMillis();
        agentStreamLifecycle.scheduleInteractionTimeout(() -> runInTenant(this::onInteractionTimeout));
        Disposable next;
        try {
            next = agentExecutionAdapter.stream(message)
                    .doFinally(signal -> runInTenant(this::onSourceTerminated))
                    .subscribe(
                            event -> runInTenant(() -> onEvent(event)),
                            error -> runInTenant(() -> onError(error)),
                            () -> runInTenant(this::onComplete));
        } catch (RuntimeException subscribeFailure) {
            // 装配或订阅同步失败时源流尚未建立，doFinally 不会触发；立即复位 sourceActive，
            // 使终结流程直接完成 drainedSignal。
            sourceActive = false;
            agentStreamLifecycle.cancelInteractionTimeout();
            throw subscribeFailure;
        }
        if (terminal && !rootAgentEnded) {
            // 停止、超时或同步失败发生在回答边界前时不保留源流；若根 AGENT_END 在 subscribe
            // 返回前同步到达，仍须接管订阅，让随后记忆/维护尾部自然排空。
            next.dispose();
        } else {
            agentStreamLifecycle.adopt(next);
        }
    }

    /**
     * 仅在根 {@code AGENT_END} 尚未到达时处理中断，避免误伤后续记忆尾部。
     */
    private void onInteractionTimeout() {
        synchronized (this) {
            if (terminal || rootAgentEnded) {
                return;
            }
            log.warn("Run交互超时，中断当前phase: runId={}", run.getId());
        }
        // 终态 DB 提交在实例锁外执行。
        finalizeFailed(
                ChatRunFailureCode.ERROR,
                "对话运行超过最大时长 " + properties.getChat().getRun().getMaxRunDurationSeconds() + " 秒");
        // AgentScope 中断可能触发流回调，必须在实例锁外调用，避免回流等待同一 monitor。
        try {
            interruptAgent();
        } catch (RuntimeException interruptFailure) {
            log.warn("Run交互超时后协作式中断失败，将等待宽限期后强制取消: runId={}", run.getId(), interruptFailure);
        }
        // 超时中断宽限期结束后仍有活动源流时强制取消，只释放资源，不覆盖已经提交的失败终态。
        agentStreamLifecycle.scheduleForceDispose(() -> runInTenant(agentStreamLifecycle::forceDispose));
    }

    private void onEvent(AgentEvent event) {
        boolean finalizeNow = false;
        synchronized (this) {
            if (terminal) {
                return;
            }
            if (event.getType() == AgentEventType.AGENT_END) {
                if (event.getSource() != null) {
                    // 子 Agent 结束不代表整个对话运行结束。
                    return;
                }
                // 根 AGENT_END 是业务回答边界；普通阶段允许记忆尾部继续，HITL 阶段由适配器在此结束源流。
                rootAgentEnded = true;
                agentStreamLifecycle.cancelInteractionTimeout();
                if (awaitingInteraction || accumulator.hasPendingInteraction()) {
                    return;
                }
                finalizeNow = true;
            } else {
                applyAndAppend(event);
            }
        }
        if (finalizeNow) {
            // 终态 DB 提交在实例锁外执行。
            finalizeCompleted();
        }
    }

    /**
     * 锁内的事件处理体（非 AGENT_END 分支）：映射、应用快照、广播并按需安排检查点。
     */
    private void applyAndAppend(AgentEvent event) {
        ToolResultMediaReference.attachmentId(event).ifPresent(accumulator::addGeneratedAttachmentId);
        List<AguiEvent> events = eventMapper.map(event);
        if (AgentEventMapper.isInteractionBoundary(event)) {
            if (awaitingInteraction) {
                return;
            }
            awaitingInteraction = true;
            pendingInteractionEvents = events;
            accumulator.apply(events);
            // AgentScope 在事件流结束时持久化 ASKING / TOOL_SUSPENDED 挂起状态。
            return;
        }
        accumulator.apply(events);
        if (!events.isEmpty()) {
            checkpointDirty = true;
            snapshotGeneration++;
        }
        appendAll(events);
        maybeCheckpoint();
    }

    private void onError(Throwable error) {
        synchronized (this) {
            if (terminal) {
                // 业务终态后的记忆或维护任务失败不再改变运行结果，仅记录后处理错误。
                log.warn("Run业务终态后，记忆/维护尾部失败: runId={}", run.getId(), error);
                return;
            }
        }
        // 终态 DB 提交在实例锁外执行。
        finalizeFailed(ChatRunFailureCode.ERROR, ChatRunSnapshotSanitizer.safeMessage(error));
    }

    private void onComplete() {
        synchronized (this) {
            agentStreamLifecycle.clear();
            if (terminal) {
                return;
            }
            if (awaitingInteraction) {
                // 待交互状态必须等 doFinally 确认适配后的当前阶段源流已经结束后再提交。
                return;
            }
        }
        // 终态 DB 提交在实例锁外执行。
        finalizeCompleted();
    }

    /**
     * AgentScope 源流完成、失败或取消后的统一收尾。此时沙箱已释放，方法先把正常完成的 HITL 阶段提交为
     * 待交互（确认/输入），随后在实例锁外执行当前阶段的工作区审计（含应用查询与文件扫描复制 I/O）。
     * 当前执行阶段收尾后完成 {@code localPhaseDrainedSignal}，供协调器释放本节点句柄与容量；
     * 只有最终业务终态才完成实例 {@code drainedSignal}。后续 HITL 请求从持久化状态构造新的阶段实例。
     */
    private void onSourceTerminated() {
        boolean commitAwait = false;
        synchronized (this) {
            sourceActive = false;
            agentStreamLifecycle.cancelInteractionTimeout();
            if (!terminal && awaitingInteraction) {
                commitAwait = true;
            }
        }
        if (commitAwait) {
            // 待交互提交必须先于用户 confirm 可见（confirm 以 awaitingInteraction 为冲突守卫），
            // 因此提交仍占用实例锁；HITL 边界每阶段仅一次，锁内短事务可接受。
            try {
                completeAwaitInteraction();
            } catch (RuntimeException awaitInteractionFailure) {
                // 源流已经排空，待交互事实仍无法提交时转为失败终态，避免运行永久停留在 RUNNING。
                log.error("Run进入待交互失败，收敛为失败终态: runId={}", run.getId(), awaitInteractionFailure);
                finalizeFailed(
                        ChatRunFailureCode.AWAIT_CONFIRM_FAILED,
                        ChatRunSnapshotSanitizer.safeMessage(awaitInteractionFailure));
            }
        }
        runWorkspaceAudit();
        if (terminal) {
            // 最终阶段已提交业务终态，源流排空后即可完成实例级排空信号。
            drainedSignal.complete(null);
        }
        localPhaseDrainedSignal.complete(null);
    }

    /**
     * 源流排空后审计当前阶段的工作区变更；审计失败不回滚业务终态。
     */
    private void runWorkspaceAudit() {
        try {
            workspaceAuditRecorder.recordChanges(session, phaseStartedAtMillis);
        } catch (RuntimeException auditFailure) {
            log.warn("源流排空后工作区审计记录失败: runId={}", run.getId(), auditFailure);
        }
    }

    /**
     * 判断 AgentScope 源流是否已终止（无已订阅但尚未触发 {@code doFinally} 的源流）。
     */
    private boolean isSourceTerminated() {
        return !sourceActive;
    }

    private synchronized void completeAwaitInteraction() {
        ChatRunSnapshot snapshot = accumulator.buildSnapshot();
        // 先提交待交互快照，再向本地订阅者发布 interrupt，保证事实先于信号。
        List<AguiEvent> events = pendingInteractionEvents;
        if (!persistAwaitSnapshot(snapshot)) {
            // 终态会拒绝迟到的待交互快照；不存在第二套待交互状态迁移。
            awaitingInteraction = false;
            pendingInteractionEvents = null;
            return;
        }
        try {
            eventStore.appendAll(run.getId(), run.getAguiRunId(), events);
        } catch (RuntimeException publishFailure) {
            // 待交互快照已经是事实；本地信号失败不能反向把 Run 终结为 FAILED。
            // 浏览器重新连接后仍可从持久化快照恢复交互卡片。
            log.warn("Run待交互快照已写入，但本地 interrupt 发布失败: runId={}", run.getId(), publishFailure);
        }
        cancelCheckpointTask();
        checkpointDirty = false;
        awaitingInteraction = false;
        pendingInteractionEvents = null;
    }

    /**
     * 提交待交互快照：版本碰撞（在途检查点与本次提交交错）时按数据库最新版本有限重试；
     * 运行已被并发提交终态时回读终态并返回 {@code false}。
     */
    private boolean persistAwaitSnapshot(ChatRunSnapshot snapshot) {
        for (int attempt = 1; attempt <= MAX_PROJECTION_FINALIZE_ATTEMPTS; attempt++) {
            boolean written;
            try {
                written = runService.checkpoint(run, snapshot, persistedSnapshotVersion);
            } catch (RuntimeException awaitFailure) {
                // 独立事务回滚时 interrupt 尚未发布，运行保持 RUNNING，可由调用方重试。
                awaitingInteraction = false;
                pendingInteractionEvents = null;
                log.error("Run进入待交互失败，保持运行态可重试: runId={}", run.getId(), awaitFailure);
                throw new AiBusinessException(AiErrorCode.CHAT_RUN_AWAIT_CONFIRM_FAILED, run.getId());
            }
            if (written) {
                persistedSnapshotVersion++;
                return true;
            }
            if (adoptIfExternallyTerminated()) {
                return false;
            }
        }
        // 版本持续碰撞只在并发写长期存在时可能：保持运行态，由调用方收敛为失败终态。
        awaitingInteraction = false;
        pendingInteractionEvents = null;
        throw new AiBusinessException(AiErrorCode.CHAT_RUN_AWAIT_CONFIRM_FAILED, run.getId());
    }

    private void appendAll(List<AguiEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        eventStore.appendAll(run.getId(), run.getAguiRunId(), events);
    }

    /**
     * 事件批次写入后判断是否需要安排检查点：无增量、已终态或处于待交互态时跳过。
     * 待交互态的快照由 {@code completeAwaitInteraction} 统一提交，不走延迟检查点。
     */
    private void maybeCheckpoint() {
        if (!checkpointDirty || terminal || accumulator.hasPendingInteraction()) {
            return;
        }
        scheduleCheckpoint();
    }

    /**
     * 由实例自身安排一次延迟检查点，不依赖全局扫描。
     */
    private void scheduleCheckpoint() {
        if (checkpointTask != null || scheduler.isShutdown()) {
            return;
        }
        checkpointTask = scheduler.schedule(
                () -> runInTenant(this::checkpointIfDirty),
                properties.getChat().getRun().getSnapshotIntervalSeconds(),
                TimeUnit.SECONDS);
    }

    /**
     * 延迟检查点到期后的写入回调：仍无增量、已终态或待交互时放弃；快照 CAS 写入在实例锁外执行，
     * 写入失败或版本碰撞被拒（脏标记保持）时重新调度，依赖实例自驱重试而非全局扫描。
     */
    private void checkpointIfDirty() {
        synchronized (this) {
            checkpointTask = null;
            if (!checkpointDirty || terminal || accumulator.hasPendingInteraction()) {
                return;
            }
        }
        try {
            checkpointNow();
        } catch (RuntimeException checkpointFailure) {
            log.warn("Run延迟检查点写入失败，将由实例重试: runId={}", run.getId(), checkpointFailure);
        }
        synchronized (this) {
            if (checkpointDirty && !terminal) {
                scheduleCheckpoint();
            }
        }
    }

    /**
     * 将运行终结为完成状态。
     */
    void finalizeCompleted() {
        finalizeTerminal(ChatRunStatus.COMPLETED, ChatRunFinishReason.SUCCESS, null, null);
    }

    /**
     * 将运行终结为停止状态。
     */
    void finalizeStopped() {
        finalizeTerminal(ChatRunStatus.STOPPED, ChatRunFinishReason.USER_STOP, null, null);
    }

    /**
     * 将运行终结为失败状态。
     */
    public void finalizeFailed(ChatRunFailureCode errorCode, String errorMessage) {
        finalizeTerminal(ChatRunStatus.FAILED, ChatRunFinishReason.ERROR, errorCode, errorMessage);
    }

    /**
     * 终态提交的唯一入口：实例锁内完成状态翻转、检查点取消与快照闭合，随后把数据库终态提交
     * （含消息/附件投影与会话行锁等待）移到实例锁外执行，提交结束后回锁同步排空信号。
     *
     * <p>锁外提交窗口内，{@code confirm}/{@code submitInput} 由 {@code finalizeCommitInProgress}
     * 拦截为状态冲突；数据库行锁保证终态与并发状态迁移的最终串行。非 COMPLETED 终态会通过适配器
     * 拒绝未决的工具调用（denyPendingToolCalls）。数据库提交失败交给 {@code handleFinalizeFailure}
     * 退避重试；成功且无活动源流时立即完成排空信号，否则由 {@code onSourceTerminated} 在源流排空后完成。
     *
     * <p>注意：从持有实例锁的调用方（如 confirm 的 startPhase 失败路径）进入时，monitor 可重入导致
     * 提交实际仍在锁内执行——该路径低频（仅启动失败），可接受。
     *
     * @param status       终态状态
     * @param reason       结束原因
     * @param errorCode    失败码；非 FAILED 终态为 {@code null}
     * @param errorMessage 失败消息；非 FAILED 终态为 {@code null}
     */
    private void finalizeTerminal(
            ChatRunStatus status, ChatRunFinishReason reason, ChatRunFailureCode errorCode, String errorMessage) {
        ChatRunSnapshot snapshot;
        synchronized (this) {
            if (terminal || finalizeCommitInProgress) {
                return;
            }
            terminal = true;
            finalizeCommitInProgress = true;
            cancelCheckpointTask();
            awaitingInteraction = false;
            pendingInteractionEvents = null;
            if (status != ChatRunStatus.COMPLETED) {
                closePendingToolCalls();
            }
            List<AguiEvent> closeEvents = eventMapper.closeOpenMessages();
            // 先应用快照增量、后写关闭事件：appendAll 可能触发 checkpointNow，
            // 必须保证检查点读到的是已关闭快照，而非「事件已关闭、快照仍打开」。
            accumulator.apply(closeEvents);
            accumulator.closeOpenMessages();
            try {
                appendAll(closeEvents);
            } catch (RuntimeException closeEventFailure) {
                log.warn("Run终结前内容关闭事件写入失败，仍继续提交业务终态: runId={}", run.getId(), closeEventFailure);
            }
            snapshot = accumulator.buildSnapshot();
        }
        try {
            chatExecutionFinalizer.commitTerminal(run, snapshot, status, reason, errorCode, errorMessage);
        } catch (RuntimeException finalizeFailure) {
            handleFinalizeFailure(status, reason, errorCode, errorMessage, finalizeFailure);
            return;
        }
        completeTerminalAfterCommit();
    }

    /**
     * 终态 DB 提交成功后的回锁收尾：清提交标志，并在无活动源流时完成排空信号。
     */
    private synchronized void completeTerminalAfterCommit() {
        finalizeCommitInProgress = false;
        // 业务终态后若无活动源流（如纯终结恢复、源流已先终止、启动同步失败），立即完成排空信号；
        // 否则等待 onSourceTerminated 在源流排空后完成它。
        if (isSourceTerminated()) {
            drainedSignal.complete(null);
            localPhaseDrainedSignal.complete(null);
        }
    }

    /**
     * 完整终态提交失败后的重试决策：确定性的投影错误直接走最小失败终结；瞬时错误只有限重试，
     * 耗尽后同样降级，避免附件冲突等错误反复占用调度线程并让数据库长期停留在 RUNNING。
     * 在实例锁外调用（DB 提交已移出锁），仅在重置终态标志与调度重试时短暂回锁。
     */
    private void handleFinalizeFailure(
            ChatRunStatus status,
            ChatRunFinishReason reason,
            ChatRunFailureCode errorCode,
            String errorMessage,
            RuntimeException finalizeFailure) {
        int attempt;
        boolean permanent;
        synchronized (this) {
            terminal = false;
            finalizeCommitInProgress = false;
            attempt = ++finalizeAttempts;
            permanent = isPermanentFinalizeFailure(finalizeFailure);
        }
        if (permanent || attempt >= MAX_PROJECTION_FINALIZE_ATTEMPTS) {
            log.error(
                    "对话Run完整终结失败，切换最小失败终结: runId={}, attempt={}, permanent={}",
                    run.getId(),
                    attempt,
                    permanent,
                    finalizeFailure);
            finalizeFailureWithoutProjection(finalizeFailure);
            return;
        }
        if (scheduler.isShutdown()) {
            finalizeFailureWithoutProjection(finalizeFailure);
            return;
        }
        log.warn("对话Run完整终结失败，将有限重试: runId={}, attempt={}", run.getId(), attempt, finalizeFailure);
        scheduler.schedule(
                () -> runInTenant(() -> finalizeTerminal(status, reason, errorCode, errorMessage)),
                attempt,
                TimeUnit.SECONDS);
    }

    /**
     * 不再执行助手消息和附件投影，仅把仍处于活动态的 Run 提交为 FAILED。锁内完成状态准备后，
     * 最小失败的 DB 提交在实例锁外执行；提交遇数据库瞬时故障时以最多 30 秒的退避持续重试，
     * 由当前执行实例在数据库恢复后收敛。
     */
    private void finalizeFailureWithoutProjection(RuntimeException projectionFailure) {
        String fallbackMessage;
        synchronized (this) {
            if (terminal) {
                return;
            }
            terminal = true;
            finalizeCommitInProgress = true;
            cancelCheckpointTask();
            awaitingInteraction = false;
            pendingInteractionEvents = null;
            closePendingToolCalls();
            fallbackMessage = "对话结果终结失败: " + ChatRunSnapshotSanitizer.safeMessage(projectionFailure);
        }
        try {
            chatExecutionFinalizer.commitFailureWithoutProjection(run, ChatRunFailureCode.ERROR, fallbackMessage);
        } catch (RuntimeException fallbackFailure) {
            handleFallbackCommitFailure(projectionFailure, fallbackFailure);
            return;
        }
        completeTerminalAfterCommit();
    }

    /**
     * 最小失败终结的 DB 提交失败后的重试决策：在实例锁外调用，仅在标志重置与调度重试时短暂回锁。
     */
    private void handleFallbackCommitFailure(RuntimeException projectionFailure, RuntimeException fallbackFailure) {
        synchronized (this) {
            terminal = false;
            finalizeCommitInProgress = false;
            int attempt = ++fallbackFinalizeAttempts;
            boolean persistedTerminal = ChatRunStatus.isTerminal(run.getStatus());
            boolean permanent = !persistedTerminal && isPermanentFinalizeFailure(fallbackFailure);
            if (permanent || scheduler.isShutdown()) {
                log.error(
                        "对话Run最小失败终结无法继续: runId={}, attempt={}, persistedTerminal={}, permanent={}",
                        run.getId(),
                        attempt,
                        persistedTerminal,
                        permanent,
                        fallbackFailure);
                terminal = true;
                if (isSourceTerminated()) {
                    drainedSignal.complete(null);
                    localPhaseDrainedSignal.complete(null);
                }
                return;
            }
            if (attempt == 1 || attempt == 5 || attempt % 10 == 0) {
                log.error("对话Run最小失败终结失败，将继续重试: runId={}, attempt={}", run.getId(), attempt, fallbackFailure);
            } else {
                log.warn("对话Run最小失败终结失败，将重试: runId={}, attempt={}", run.getId(), attempt, fallbackFailure);
            }
            scheduler.schedule(
                    () -> runInTenant(() -> finalizeFailureWithoutProjection(projectionFailure)),
                    Math.min(attempt, 30),
                    TimeUnit.SECONDS);
        }
    }

    /**
     * 判断完整终结失败是否为确定性错误；此类错误继续执行同一投影没有意义。
     */
    private static boolean isPermanentFinalizeFailure(RuntimeException failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof IllegalStateException || cause instanceof AiBusinessException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 排空信号：最终业务阶段的源流与后处理全部结束时完成。
     */
    public CompletionStage<Void> drainedSignal() {
        return drainedSignal;
    }

    /**
     * 本地阶段排空信号：本实例承载的源流及其后处理结束时完成；无源流的终结实例在终态提交后完成。
     * 该信号只用于释放当前 JVM 的执行句柄和容量，不表达业务 Run 终态。
     */
    CompletionStage<Void> localPhaseDrainedSignal() {
        return localPhaseDrainedSignal;
    }

    /**
     * 生成当前运行的 AG-UI 引导事件。
     */
    public synchronized AguiBootstrapModel bootstrap() {
        long cursor = eventStore.latestCursor(run.getId());
        return new AguiBootstrapModel(
                cursor,
                AguiBootstrapEncoder.encode(run, accumulator.buildSnapshot()),
                ChatRunStatus.isTerminal(run.getStatus()) || accumulator.hasPendingInteraction());
    }

    /**
     * 立即写入执行检查点。实例锁内构建快照并捕获版本号与代际，快照 CAS 写入在实例锁外执行；
     * 写库期间新到事件会保留脏标记并安排后续检查点。写入被拒时不抛出：已终态则同步外部终态，
     * 否则视为快照版本碰撞，刷新版本号后由调用方或后续检查点重写。
     */
    void checkpointNow() {
        ChatRunSnapshot snapshot;
        long expectedVersion;
        long generationAtBuild;
        synchronized (this) {
            cancelCheckpointTask();
            snapshot = accumulator.buildSnapshot();
            expectedVersion = persistedSnapshotVersion;
            generationAtBuild = snapshotGeneration;
        }
        if (runService.checkpoint(run, snapshot, expectedVersion)) {
            synchronized (this) {
                persistedSnapshotVersion = expectedVersion + 1;
                // 写库期间到达的新事件不在本次快照内，保留脏标记交由后续检查点覆盖。
                checkpointDirty = checkpointDirty && snapshotGeneration != generationAtBuild;
            }
            return;
        }
        synchronized (this) {
            if (!adoptIfExternallyTerminated()) {
                log.warn("Run检查点因快照版本碰撞被拒，已刷新版本等待重写: runId={}", run.getId());
            }
        }
    }

    /**
     * 快照写入被数据库拒绝后的收敛：运行已被并发提交终态时同步本地终态，并在无活动源流时完成排空信号，
     * 返回 {@code true}；非终态拒绝视为快照版本碰撞，从数据库刷新版本号、保留脏标记等待重写，返回
     * {@code false}。
     */
    private boolean adoptIfExternallyTerminated() {
        ChatRunEntity current = runService.loadCurrentOrIdentity(run);
        run.setStatus(current.getStatus());
        if (!ChatRunStatus.isTerminal(current.getStatus())) {
            persistedSnapshotVersion = current.getSnapshotVersion() == null ? 0L : current.getSnapshotVersion();
            return false;
        }
        terminal = true;
        checkpointDirty = false;
        if (isSourceTerminated()) {
            drainedSignal.complete(null);
            localPhaseDrainedSignal.complete(null);
        }
        return true;
    }

    /**
     * 取消未执行的延迟检查点任务并清空引用；允许重复调用。
     */
    private void cancelCheckpointTask() {
        if (checkpointTask != null) {
            checkpointTask.cancel(false);
            checkpointTask = null;
        }
    }

    /**
     * 判断 Agent 事件流是否正在运行。
     */
    boolean isRunning() {
        return agentStreamLifecycle.isRunning();
    }

    /**
     * 请求中断 Agent 状态会话。
     */
    public void interruptAgent() {
        if (agentExecutionAdapter != null) {
            agentExecutionAdapter.interrupt();
        }
    }

    /**
     * 取消活动事件流并终结运行。dispose 出锁以避免取消回调回流死锁，DB 终态迁移在锁外完成。
     */
    public void forceStopIfRunning() {
        agentStreamLifecycle.forceDispose();
        finalizeStopped();
    }

    /**
     * 提交 STOPPED 终态；数据库终态阻止迟到完成结果覆盖。若本地源流仍活动，返回 true 供协调器
     * 在锁外做协作式中断。停止前快照与终态的 DB 写入均在实例锁外执行。
     *
     * @return 是否需要协调器在锁外中断活动源流
     */
    public boolean requestStop() {
        boolean interruptRequired;
        synchronized (this) {
            if (terminal || finalizeCommitInProgress) {
                return false;
            }
            interruptRequired = isRunning();
        }
        if (interruptRequired) {
            try {
                checkpointNow();
            } catch (RuntimeException checkpointFailure) {
                log.warn("停止Run前快照写入失败，仍提交停止终态: runId={}", run.getId(), checkpointFailure);
            }
        }
        synchronized (this) {
            if (terminal) {
                return false;
            }
        }
        finalizeStopped();
        synchronized (this) {
            // 终态提交后复检源流：覆盖两阶段检查之间 confirm 新起的阶段源流，避免其无中断空跑。
            return interruptRequired || isRunning();
        }
    }

    /**
     * 保存当前检查点并中断 Agent。检查点在实例锁内写入，中断请求保持在锁外以避免回流死锁。
     */
    public void interruptForShutdown() {
        try {
            checkpointNow();
        } catch (RuntimeException checkpointFailure) {
            log.warn("停机前Run检查点写入失败，仍继续中断: runId={}", run.getId(), checkpointFailure);
        }
        try {
            interruptAgent();
        } catch (RuntimeException interruptFailure) {
            log.warn("停机中断Run失败: runId={}", run.getId(), interruptFailure);
        }
    }

    /**
     * 闭合 Agent 状态中的未决工具调用：补写拒绝结果，避免遗留调用阻塞该会话的后续对话。
     */
    private void closePendingToolCalls() {
        if (agentExecutionAdapter == null) {
            return;
        }
        try {
            agentExecutionAdapter.denyPendingToolCalls();
        } catch (RuntimeException error) {
            log.warn("Run终结时补写未决工具调用失败: runId={}", run.getId(), error);
        }
    }
}
