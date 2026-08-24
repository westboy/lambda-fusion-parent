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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
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

    /** 终态提交的最大重试次数；达到上限后释放实例，避免局部故障永久占用运行容量。 */
    private static final int MAX_FINALIZE_ATTEMPTS = 100;

    private final ChatRunStateService runService;
    private final ChatRunEventStore eventStore;
    private final AiProperties properties;
    private final ScheduledExecutorService scheduler;
    private final WorkspaceAuditRecorder workspaceAuditRecorder;
    private final CompletableFuture<Void> drainedSignal = new CompletableFuture<>();
    private final ChatExecutionFinalizer chatExecutionFinalizer;
    private final AgentStreamLifecycle agentStreamLifecycle;

    private final ChatRunEntity run;
    private final ChatSessionEntity session;
    private final AgentExecutionAdapter agentExecutionAdapter;
    private final ChatExecutionSnapshotBuilder accumulator;
    private boolean rootAgentEnded;
    private boolean terminal;
    private boolean sourceActive;
    private int finalizeAttempts;
    private volatile long phaseStartedAtMillis = System.currentTimeMillis();
    private boolean checkpointDirty;
    private ScheduledFuture<?> checkpointTask;
    private AgentEventMapper eventMapper;
    private boolean awaitingInteraction;
    private List<AguiEvent> pendingInteractionEvents;
    private final Set<String> generatedAttachmentIds = new LinkedHashSet<>();

    /**
     * 创建执行实例。
     *
     * @param runService 运行状态服务
     * @param eventStore 运行事件存储
     * @param properties AI 模块配置
     * @param scheduler 定时任务执行器
     * @param workspaceAuditRecorder 工作区审计记录器
     * @param run 运行实体
     * @param session 会话实体
     * @param agentExecutionAdapter Agent 执行适配器；仅执行终结流程时可为 {@code null}
     * @param accumulator 快照累加器
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
        this.generatedAttachmentIds.addAll(ToolResultMediaReference.attachmentIds(accumulator.buildSnapshot()));
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
        Integer sourcePhaseNo = command == null ? null : command.getPhaseNo();
        if (sourcePhaseNo == null) {
            throw new AiBusinessException(AiErrorCode.INVALID_PARAMETER, "phaseNo不能为空");
        }
        if (run.getPhaseNo() > sourcePhaseNo) {
            log.info(
                    "Run确认幂等重放，来源阶段已处理: runId={}, sourcePhaseNo={}, currentPhaseNo={}",
                    run.getId(),
                    sourcePhaseNo,
                    run.getPhaseNo());
            return new ConfirmTransition(run, session, false);
        }
        ChatRunSnapshot snapshot = accumulator.buildSnapshot();
        if (!Objects.equals(run.getPhaseNo(), sourcePhaseNo)
                || !ChatRunStatus.RUNNING.name().equals(run.getStatus())
                || snapshot.pendingTools().isEmpty()
                || sourceActive) {
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_STATE_CONFLICT, run.getStatus());
        }

        Msg confirmMessage = validateAndBuildMessage(command, snapshot.pendingTools());
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
        Integer sourcePhaseNo = command == null ? null : command.getPhaseNo();
        if (sourcePhaseNo == null) {
            throw new AiBusinessException(AiErrorCode.INVALID_PARAMETER, "phaseNo不能为空");
        }
        if (run.getPhaseNo() > sourcePhaseNo) {
            log.info(
                    "Run输入幂等重放，来源阶段已处理: runId={}, sourcePhaseNo={}, currentPhaseNo={}",
                    run.getId(),
                    sourcePhaseNo,
                    run.getPhaseNo());
            return new ConfirmTransition(run, session, false);
        }
        ChatRunSnapshot snapshot = accumulator.buildSnapshot();
        if (!Objects.equals(run.getPhaseNo(), sourcePhaseNo)
                || !ChatRunStatus.RUNNING.name().equals(run.getStatus())
                || snapshot.pendingInputs().isEmpty()
                || sourceActive) {
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_STATE_CONFLICT, run.getStatus());
        }

        Msg inputMessage = validateAndBuildInputMessage(command, snapshot.pendingInputs());
        ConfirmTransition transition = runService.advanceConfirmation(run, session, sourcePhaseNo, snapshot);
        syncRun(transition.run());
        if (!transition.resumed()) {
            return transition;
        }
        try {
            beginConfirmedPhase();
            startPhase(inputMessage);
        } catch (RuntimeException startFailure) {
            finalizeFailed(ChatRunFailureCode.START_FAILED, ChatRunSnapshotSanitizer.safeMessage(startFailure));
        }
        return transition;
    }

    /**
     * 校验输入命令并构造恢复消息：委托 {@link ToolInputValidator} 读取 Agent 当前挂起工具调用，
     * 要求快照、输入、Agent 三方 ID 一致，且输入值满足 responseSchema。
     *
     * @param command 用户输入命令
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
     * @param command 用户确认命令
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

    /** 将数据库迁移后的运行状态同步到实例内存对象。 */
    private void syncRun(ChatRunEntity updated) {
        run.setStatus(updated.getStatus());
        run.setPhaseNo(updated.getPhaseNo());
        run.setAguiRunId(updated.getAguiRunId());
    }

    /** CAS 成功后切换累加器与事件解释器到新阶段。 */
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
        if (terminal) {
            next.dispose();
        } else {
            agentStreamLifecycle.adopt(next);
        }
    }

    /** 仅在根 {@code AGENT_END} 尚未到达时处理中断，避免误伤后续记忆尾部。 */
    private void onInteractionTimeout() {
        synchronized (this) {
            if (terminal || rootAgentEnded) {
                return;
            }
            log.warn("Run交互超时，中断当前phase: runId={}", run.getId());
            finalizeFailed(
                    ChatRunFailureCode.ERROR,
                    "对话运行超过最大时长 " + properties.getChat().getRun().getMaxRunDurationSeconds() + " 秒");
        }
        // AgentScope 中断可能触发流回调，必须在实例锁外调用，避免回流等待同一 monitor。
        try {
            interruptAgent();
        } catch (RuntimeException interruptFailure) {
            log.warn("Run交互超时后协作式中断失败，将等待宽限期后强制取消: runId={}", run.getId(), interruptFailure);
        }
        // 超时中断宽限期结束后仍有活动源流时强制取消，只释放资源，不覆盖已经提交的失败终态。
        agentStreamLifecycle.scheduleForceDispose(() -> runInTenant(agentStreamLifecycle::forceDispose));
    }

    private synchronized void onEvent(AgentEvent event) {
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
            finalizeCompleted();
            return;
        }
        ToolResultMediaReference.attachmentId(event).ifPresent(generatedAttachmentIds::add);
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
        checkpointDirty = checkpointDirty || !events.isEmpty();
        appendAll(events);
        maybeCheckpoint();
    }

    private synchronized void onError(Throwable error) {
        if (terminal) {
            // 业务终态后的记忆或维护任务失败不再改变运行结果，仅记录后处理错误。
            log.warn("Run业务终态后，记忆/维护尾部失败: runId={}", run.getId(), error);
            return;
        }
        finalizeFailed(ChatRunFailureCode.ERROR, ChatRunSnapshotSanitizer.safeMessage(error));
    }

    private synchronized void onComplete() {
        agentStreamLifecycle.clear();
        if (terminal) {
            return;
        }
        if (awaitingInteraction) {
            // 待交互状态必须等 doFinally 确认适配后的当前阶段源流已经结束后再提交。
            return;
        }
        finalizeCompleted();
    }

    /**
     * AgentScope 源流完成、失败或取消后的统一收尾。此时沙箱已释放，方法先执行当前阶段的工作区审计，
     * 再把正常完成的 HITL 阶段提交为待交互（确认/输入）。只有最终业务终态才完成实例 {@code drainedSignal}；
     * 待交互阶段保留实例，交互请求随后可立即启动下一阶段。
     */
    private synchronized void onSourceTerminated() {
        sourceActive = false;
        agentStreamLifecycle.cancelInteractionTimeout();
        runWorkspaceAudit();
        if (!terminal && awaitingInteraction) {
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
        if (terminal) {
            // 最终阶段已提交业务终态，源流排空后即可完成实例级排空信号。
            drainedSignal.complete(null);
        }
    }

    /** 源流排空后审计当前阶段的工作区变更；审计失败不回滚业务终态。 */
    private void runWorkspaceAudit() {
        try {
            workspaceAuditRecorder.recordChanges(session, phaseStartedAtMillis);
        } catch (RuntimeException auditFailure) {
            log.warn("源流排空后工作区审计记录失败: runId={}", run.getId(), auditFailure);
        }
    }

    /** 判断 AgentScope 源流是否已终止（无已订阅但尚未触发 {@code doFinally} 的源流）。 */
    private boolean isSourceTerminated() {
        return !sourceActive;
    }

    private void completeAwaitInteraction() {
        ChatRunSnapshot snapshot = accumulator.buildSnapshot();
        // 先提交待交互快照，再向本地订阅者发布 interrupt，保证事实先于信号。
        List<AguiEvent> events = pendingInteractionEvents;
        boolean awaiting;
        try {
            awaiting = runService.checkpoint(run, snapshot);
        } catch (RuntimeException awaitFailure) {
            // 独立事务回滚时 interrupt 尚未发布，运行保持 RUNNING，可由调用方重试。
            awaitingInteraction = false;
            pendingInteractionEvents = null;
            log.error("Run进入待交互失败，保持运行态可重试: runId={}", run.getId(), awaitFailure);
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_AWAIT_CONFIRM_FAILED, run.getId());
        }
        if (!awaiting) {
            // 终态会拒绝迟到的待交互快照；不存在第二套待交互状态迁移。
            awaitingInteraction = false;
            pendingInteractionEvents = null;
            adoptExternalTerminalIfRejected("Run待交互快照未写入: ");
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

    /** 由实例自身安排一次延迟检查点，不依赖全局扫描。 */
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
     * 延迟检查点到期后的写入回调（实例锁内）：仍无增量、已终态或待交互时放弃，
     * 写入失败时重新调度，依赖实例自驱重试而非全局扫描。
     */
    private synchronized void checkpointIfDirty() {
        checkpointTask = null;
        if (!checkpointDirty || terminal || accumulator.hasPendingInteraction()) {
            return;
        }
        try {
            checkpointNow();
        } catch (RuntimeException checkpointFailure) {
            log.warn("Run延迟检查点写入失败，将由实例重试: runId={}", run.getId(), checkpointFailure);
            scheduleCheckpoint();
        }
    }

    /** 将运行终结为完成状态。 */
    synchronized void finalizeCompleted() {
        finalizeTerminal(ChatRunStatus.COMPLETED, ChatRunFinishReason.SUCCESS, null, null);
    }

    /** 将运行终结为停止状态。 */
    synchronized void finalizeStopped() {
        finalizeTerminal(ChatRunStatus.STOPPED, ChatRunFinishReason.USER_STOP, null, null);
    }

    /** 将运行终结为失败状态。 */
    public synchronized void finalizeFailed(ChatRunFailureCode errorCode, String errorMessage) {
        finalizeTerminal(ChatRunStatus.FAILED, ChatRunFinishReason.ERROR, errorCode, errorMessage);
    }

    /**
     * 终态提交的唯一入口：置终态标志、取消检查点任务并清理待确认中间态，
     * 依次关闭打开的消息流、写入关闭事件，最后提交业务终态与最终快照。
     *
     * <p>非 COMPLETED 终态会通过适配器拒绝未决的工具调用（denyPendingToolCalls）。数据库提交失败交给
     * {@code handleFinalizeFailure} 退避重试；成功且无活动源流时立即完成排空信号，
     * 否则由 {@code onSourceTerminated} 在源流排空后完成。
     *
     * @param status 终态状态
     * @param reason 结束原因
     * @param errorCode 失败码；非 FAILED 终态为 {@code null}
     * @param errorMessage 失败消息；非 FAILED 终态为 {@code null}
     */
    private synchronized void finalizeTerminal(
            ChatRunStatus status, ChatRunFinishReason reason, ChatRunFailureCode errorCode, String errorMessage) {
        if (terminal) {
            return;
        }
        terminal = true;
        cancelCheckpointTask();
        awaitingInteraction = false;
        pendingInteractionEvents = null;
        if (status != ChatRunStatus.COMPLETED) {
            closePendingToolCalls();
        }
        try {
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
            chatExecutionFinalizer.commitTerminal(
                    run,
                    accumulator.buildSnapshot(),
                    List.copyOf(generatedAttachmentIds),
                    status,
                    reason,
                    errorCode,
                    errorMessage);
            // 业务终态后若无活动源流（如纯终结恢复、源流已先终止、启动同步失败），立即完成排空信号；
            // 否则等待 onSourceTerminated 在源流排空后完成它。
            if (isSourceTerminated()) {
                drainedSignal.complete(null);
            }
        } catch (RuntimeException finalizeFailure) {
            handleFinalizeFailure(status, reason, errorCode, errorMessage, finalizeFailure);
        }
    }

    /**
     * 终态提交失败后的重试决策：永久性失败或达到上限时放弃并释放实例，否则按退避调度重试。
     * 由 {@link #finalizeTerminal} 在实例锁内调用。
     */
    private void handleFinalizeFailure(
            ChatRunStatus status,
            ChatRunFinishReason reason,
            ChatRunFailureCode errorCode,
            String errorMessage,
            RuntimeException finalizeFailure) {
        terminal = false;
        int attempt = ++finalizeAttempts;
        boolean permanent = isPermanentFinalizeFailure(finalizeFailure);
        // 数据行已删除等永久性失败或重试达到上限时停止提交，但仍释放实例，
        // 避免单个实例的终结故障永久占用内存运行容量。
        if (permanent || attempt >= MAX_FINALIZE_ATTEMPTS || scheduler.isShutdown()) {
            log.error(
                    "对话Run终结放弃数据库提交，释放实例: runId={}, attempt={}, permanent={}",
                    run.getId(),
                    attempt,
                    permanent,
                    finalizeFailure);
            terminal = true;
            if (isSourceTerminated()) {
                drainedSignal.complete(null);
            }
            return;
        }
        if (attempt == 5 || attempt % 10 == 0) {
            log.error("对话Run终结持续失败，将继续重试: runId={}, attempt={}", run.getId(), attempt, finalizeFailure);
        } else {
            log.warn("对话Run终结失败，将重试: runId={}, attempt={}", run.getId(), attempt, finalizeFailure);
        }
        scheduler.schedule(
                () -> runInTenant(() -> finalizeTerminal(status, reason, errorCode, errorMessage)),
                Math.min(attempt, 30),
                TimeUnit.SECONDS);
    }

    /** 判断终结失败是否为永久性（重试无意义）：底层数据行已不存在等。 */
    private static boolean isPermanentFinalizeFailure(RuntimeException failure) {
        return failure instanceof IllegalStateException;
    }

    /**
     * 排空信号：最终阶段源流与后处理全部结束时完成，协调器订阅以摘除注册表项；未注册实例无人订阅。
     */
    public CompletionStage<Void> drainedSignal() {
        return drainedSignal;
    }

    /** 生成当前运行的 AG-UI 引导事件。 */
    public synchronized AguiBootstrapModel bootstrap() {
        long cursor = eventStore.latestCursor(run.getId());
        return new AguiBootstrapModel(
                cursor,
                AguiBootstrapEncoder.encode(run, accumulator.buildSnapshot()),
                ChatRunStatus.isTerminal(run.getStatus()) || accumulator.hasPendingInteraction());
    }

    /** 立即写入执行检查点并收缩事件缓冲区。抛 {@link IllegalStateException} 表示非终态运行的检查点失败。 */
    synchronized void checkpointNow() {
        cancelCheckpointTask();
        if (runService.checkpoint(run, accumulator.buildSnapshot())) {
            checkpointDirty = false;
            return;
        }
        adoptExternalTerminalIfRejected("Run快照检查点未写入: ");
    }

    /**
     * 快照写入被数据库拒绝后的收敛：运行已被并发提交终态时同步本地终态，并在无活动源流时完成排空信号；
     * 非终态拒绝视为快照未写入的非法状态。
     *
     * @param failureMessage 非法状态异常消息前缀
     */
    private void adoptExternalTerminalIfRejected(String failureMessage) {
        ChatRunEntity current = runService.loadCurrentOrIdentity(run);
        run.setStatus(current.getStatus());
        if (!ChatRunStatus.isTerminal(current.getStatus())) {
            throw new IllegalStateException(failureMessage + run.getId());
        }
        terminal = true;
        checkpointDirty = false;
        if (isSourceTerminated()) {
            drainedSignal.complete(null);
        }
    }

    /** 取消未执行的延迟检查点任务并清空引用；允许重复调用。 */
    private void cancelCheckpointTask() {
        if (checkpointTask != null) {
            checkpointTask.cancel(false);
            checkpointTask = null;
        }
    }

    /** 判断 Agent 事件流是否正在运行。 */
    boolean isRunning() {
        return agentStreamLifecycle.isRunning();
    }

    /** 请求中断 Agent 状态会话。 */
    public void interruptAgent() {
        if (agentExecutionAdapter != null) {
            agentExecutionAdapter.interrupt();
        }
    }

    /** 取消活动事件流并终结运行。dispose 出锁以避免取消回调回流死锁，DB 终态迁移在锁内完成。 */
    public void forceStopIfRunning() {
        agentStreamLifecycle.forceDispose();
        finalizeStopped();
    }

    /**
     * 在实例锁内直接提交 STOPPED；数据库终态阻止迟到完成结果覆盖。若本地源流仍活动，返回 true 供协调器
     * 在锁外做协作式中断。
     *
     * @return 是否需要协调器在锁外中断活动源流
     */
    public synchronized boolean requestStop() {
        if (terminal) {
            return false;
        }
        boolean interruptRequired = isRunning();
        if (interruptRequired) {
            try {
                checkpointNow();
            } catch (RuntimeException checkpointFailure) {
                log.warn("停止Run前快照写入失败，仍提交停止终态: runId={}", run.getId(), checkpointFailure);
            }
        }
        finalizeStopped();
        return interruptRequired;
    }

    /** 保存当前检查点并中断 Agent。检查点在实例锁内写入，中断请求保持在锁外以避免回流死锁。 */
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

    /** 闭合 Agent 状态中的未决工具调用：补写拒绝结果，避免遗留调用阻塞该会话的后续对话。 */
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
