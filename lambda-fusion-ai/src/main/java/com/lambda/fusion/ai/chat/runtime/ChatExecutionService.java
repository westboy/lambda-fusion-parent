package com.lambda.fusion.ai.chat.runtime;

import com.lambda.fusion.ai.AiConstants.ChatRunFailureCode;
import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.apps.service.AppService;
import com.lambda.fusion.ai.chat.attachment.ChatAttachmentMessageBuilder;
import com.lambda.fusion.ai.chat.model.ConfirmToolCall;
import com.lambda.fusion.ai.chat.model.ConfirmTransition;
import com.lambda.fusion.ai.chat.model.SubmitToolInput;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatMessageEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.ChatExecutionCapacityLimiter.Permit;
import com.lambda.fusion.ai.chat.runtime.agui.AguiBootstrapEncoder;
import com.lambda.fusion.ai.chat.runtime.agui.AguiBootstrapModel;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEventStore;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshotCodec;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshotSanitizer;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.chat.service.ChatMessageService;
import com.lambda.fusion.ai.chat.service.ChatRunStateService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.core.utils.TenantUtils;
import io.agentscope.core.message.Msg;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 对话运行协调门面：数据库 Run 是业务状态与并发裁决的唯一来源；当前 JVM 只保留正在执行阶段的
 * 可丢弃句柄，用于本地停止、精确引导和资源释放。句柄不存在不表示 Run 已失效，也不会触发业务状态迁移。
 * Agent 构造与首阶段订阅由独立执行器承载，定时调度器只负责超时、检查点和停止宽限任务。
 *
 * @author Jin
 */
@Slf4j
@Component
public class ChatExecutionService {

    private final ChatRunStateService runService;
    private final ChatRunEventStore eventStore;
    private final ChatMessageService messageService;
    private final ChatAttachmentService attachmentService;
    private final ChatAttachmentMessageBuilder attachmentMessageBuilder;
    private final AppService appService;
    private final ChatExecutionInstanceFactory instanceFactory;
    private final AiProperties properties;
    private final ChatExecutionCapacityLimiter capacityLimiter;
    private final ConcurrentMap<String, LocalPhaseHandle> activeLocalPhases = new ConcurrentHashMap<>();
    private final ExecutorService executionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "chat-run-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 创建协调器。
     *
     * @param runService 运行状态服务
     * @param eventStore 运行事件存储
     * @param messageService 消息服务
     * @param attachmentService 附件服务
     * @param attachmentMessageBuilder 附件消息构造器
     * @param appService 应用服务
     * @param instanceFactory 执行实例工厂
     * @param properties AI 模块配置
     */
    public ChatExecutionService(
            ChatRunStateService runService,
            ChatRunEventStore eventStore,
            ChatMessageService messageService,
            ChatAttachmentService attachmentService,
            ChatAttachmentMessageBuilder attachmentMessageBuilder,
            AppService appService,
            ChatExecutionInstanceFactory instanceFactory,
            AiProperties properties) {
        this.runService = runService;
        this.eventStore = eventStore;
        this.messageService = messageService;
        this.attachmentService = attachmentService;
        this.attachmentMessageBuilder = attachmentMessageBuilder;
        this.appService = appService;
        this.instanceFactory = instanceFactory;
        this.properties = properties;
        this.capacityLimiter = new ChatExecutionCapacityLimiter(properties);
    }

    /** 启动本次请求刚创建的运行；方法只预占容量并安排后台构造，不在 HTTP 线程创建 Agent。 */
    public void start(ChatRunEntity run, ChatSessionEntity session) {
        TenantUtils.withTenant(session.getTenantId(), () -> {
            startInTenantContext(run, session);
            return null;
        });
    }

    private void startInTenantContext(ChatRunEntity run, ChatSessionEntity session) {
        if (!ChatRunStatus.RUNNING.name().equals(run.getStatus())) {
            return;
        }
        eventStore.registerLocalRun(run.getId());
        Optional<Permit> acquired = capacityLimiter.tryAcquire(session);
        if (acquired.isEmpty()) {
            failStart(run, session, new IllegalStateException("当前节点对话运行容量已满"));
            return;
        }
        Permit permit = acquired.get();
        try {
            executionExecutor.execute(
                    () -> TenantUtils.withTenant(session.getTenantId(), () -> buildAndStart(run, session, permit)));
        } catch (RuntimeException scheduleFailure) {
            permit.close();
            failStart(run, session, scheduleFailure);
        }
    }

    /** 在后台执行器中构造 Agent、跟踪本地阶段句柄并订阅首阶段事件流。 */
    private void buildAndStart(ChatRunEntity run, ChatSessionEntity session, Permit permit) {
        ChatExecutionInstance execution;
        try {
            execution = instanceFactory.createAgentBacked(run, session, scheduler);
        } catch (RuntimeException createFailure) {
            permit.close();
            failStart(run, session, createFailure);
            return;
        }

        LocalPhaseSelection localPhase = trackLocalPhase(execution, permit, run.getPhaseNo(), false);
        if (!localPhase.created()) {
            return;
        }
        try {
            ChatRunEntity current = runService.loadCurrentOrIdentity(run);
            if (ChatRunStatus.isTerminal(current.getStatus())
                    || !Objects.equals(current.getPhaseNo(), localPhase.handle().activePhaseNo())) {
                releaseLocalPhase(localPhase.handle());
                return;
            }
            startExecution(execution);
        } catch (RuntimeException startFailure) {
            execution.finalizeFailed(
                    ChatRunFailureCode.START_FAILED, ChatRunSnapshotSanitizer.safeMessage(startFailure));
        }
    }

    /**
     * 确认持久化的 HITL 工具调用并推进下一阶段。Run 快照和数据库阶段号先行校验，执行实例只承载
     * 本次恢复阶段；上一阶段的本地对象不是确认事实来源。
     *
     * @param run 运行实体
     * @param session 会话实体
     * @param command 用户确认命令
     * @return 迁移结果；{@code resumed=false} 表示来源阶段已经被处理
     */
    public ConfirmTransition confirm(ChatRunEntity run, ChatSessionEntity session, ConfirmToolCall command) {
        return TenantUtils.withTenant(session.getTenantId(), () -> {
            int sourcePhaseNo = requireSourcePhase(command == null ? null : command.getPhaseNo());
            if (run.getPhaseNo() > sourcePhaseNo) {
                return new ConfirmTransition(run, session, false);
            }
            ChatRunSnapshot snapshot = ChatRunSnapshotCodec.decode(run.getSnapshotJson());
            if (!ChatRunStatus.RUNNING.name().equals(run.getStatus())
                    || run.getPhaseNo() < sourcePhaseNo
                    || snapshot.pendingTools().isEmpty()) {
                throw new AiBusinessException(AiErrorCode.CHAT_RUN_STATE_CONFLICT, run.getStatus());
            }
            return resumePhase(run, session, sourcePhaseNo, execution -> execution.confirm(command));
        });
    }

    /**
     * 提交持久化的 HITL 用户输入并推进下一阶段。Run 快照和数据库阶段号先行校验，执行实例只承载
     * 本次恢复阶段。
     *
     * @param run 运行实体
     * @param session 会话实体
     * @param command 用户输入命令
     * @return 迁移结果；{@code resumed=false} 表示来源阶段已经被处理
     */
    public ConfirmTransition submitInput(ChatRunEntity run, ChatSessionEntity session, SubmitToolInput command) {
        return TenantUtils.withTenant(session.getTenantId(), () -> {
            int sourcePhaseNo = requireSourcePhase(command == null ? null : command.getPhaseNo());
            if (run.getPhaseNo() > sourcePhaseNo) {
                return new ConfirmTransition(run, session, false);
            }
            ChatRunSnapshot snapshot = ChatRunSnapshotCodec.decode(run.getSnapshotJson());
            if (!ChatRunStatus.RUNNING.name().equals(run.getStatus())
                    || run.getPhaseNo() < sourcePhaseNo
                    || snapshot.pendingInputs().isEmpty()) {
                throw new AiBusinessException(AiErrorCode.CHAT_RUN_STATE_CONFLICT, run.getStatus());
            }
            return resumePhase(run, session, sourcePhaseNo, execution -> execution.submitInput(command));
        });
    }

    /** 校验交互命令携带的来源阶段号。 */
    private static int requireSourcePhase(Integer sourcePhaseNo) {
        if (sourcePhaseNo == null) {
            throw new AiBusinessException(AiErrorCode.INVALID_PARAMETER, "phaseNo不能为空");
        }
        return sourcePhaseNo;
    }

    /** 构造并协调一个 HITL 恢复阶段；数据库 CAS 仍是跨节点唯一赢家判定。 */
    private ConfirmTransition resumePhase(
            ChatRunEntity run,
            ChatSessionEntity session,
            int sourcePhaseNo,
            Function<ChatExecutionInstance, ConfirmTransition> operation) {
        Permit permit =
                capacityLimiter.tryAcquire(session).orElseThrow(() -> new IllegalStateException("当前节点对话运行容量已满"));
        ChatExecutionInstance candidate;
        try {
            candidate = instanceFactory.createAgentBacked(run, session, scheduler);
        } catch (RuntimeException createFailure) {
            permit.close();
            throw createFailure;
        }

        LocalPhaseSelection localPhase;
        try {
            localPhase = trackLocalPhase(candidate, permit, sourcePhaseNo + 1, true);
        } catch (RuntimeException trackingFailure) {
            permit.close();
            throw trackingFailure;
        }
        LocalPhaseHandle handle = localPhase.handle();
        if (!localPhase.created()) {
            return replayAfterLocalResume(handle, run);
        }

        try {
            ConfirmTransition transition = operation.apply(handle.execution());
            if (transition.resumed()) {
                eventStore.registerLocalRun(run.getId());
            }
            handle.completeResume(transition);
            if (!transition.resumed()) {
                releaseLocalPhase(handle);
            }
            return transition;
        } catch (RuntimeException resumeFailure) {
            handle.failResume(resumeFailure);
            releaseLocalPhase(handle);
            throw resumeFailure;
        }
    }

    /** 等待同节点已经登记的同一目标阶段完成数据库迁移，并把当前请求按幂等重放返回。 */
    private static ConfirmTransition replayAfterLocalResume(LocalPhaseHandle handle, ChatRunEntity fallbackRun) {
        CompletableFuture<ConfirmTransition> transition = handle.resumeTransition();
        if (transition == null) {
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_STATE_CONFLICT, fallbackRun.getStatus());
        }
        try {
            ConfirmTransition completed = transition.join();
            return new ConfirmTransition(completed.run(), completed.session(), false);
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw failure;
        }
    }

    /**
     * 生成运行的 AG-UI 引导事件。本地句柄存在时使用内存快照，否则只读数据库快照和本地事件缓冲。
     * 本地句柄或事件缓冲缺失只表示请求没有落到执行节点，绝不据此终结 Run。
     *
     * @param run 运行实体
     * @param session 会话实体；归属已由调用方校验
     * @return 引导事件批次
     */
    public AguiBootstrapModel bootstrap(ChatRunEntity run, ChatSessionEntity session) {
        LocalPhaseHandle handle = activeLocalPhases.get(run.getId());
        if (handle != null) {
            return handle.execution().bootstrap();
        }
        ChatRunEntity current = runService.loadCurrentOrIdentity(run);
        ChatRunSnapshot snapshot = ChatRunSnapshotCodec.decode(current.getSnapshotJson());
        long cursor = eventStore.latestCursor(current.getId());
        return new AguiBootstrapModel(
                cursor,
                AguiBootstrapEncoder.encode(current, snapshot),
                ChatRunStatus.isTerminal(current.getStatus())
                        || snapshot.hasPendingInteraction()
                        || !eventStore.contains(current.getId()));
    }

    /**
     * 请求停止运行。本节点存在阶段句柄时先提交终态再中断 Agent；不存在时只按数据库权威状态终结，
     * 不尝试推断或远程控制其他节点。
     *
     * @param run 运行实体
     * @param session 会话实体
     */
    public void stop(ChatRunEntity run, ChatSessionEntity session) {
        TenantUtils.withTenant(session.getTenantId(), () -> stopInContext(run, session));
    }

    private void stopInContext(ChatRunEntity run, ChatSessionEntity session) {
        ChatRunEntity current = runService.loadCurrentOrIdentity(run);
        if (ChatRunStatus.isTerminal(current.getStatus())) {
            return;
        }
        LocalPhaseHandle handle = activeLocalPhases.get(current.getId());
        if (handle == null) {
            ChatRunSnapshot snapshot = ChatRunSnapshotCodec.decode(current.getSnapshotJson());
            boolean noPendingInteraction = snapshot.pendingTools().isEmpty()
                    && snapshot.pendingInputs().isEmpty();
            ChatExecutionInstance terminalOnly = createStoppedExecution(current, session, noPendingInteraction);
            terminalOnly.requestStop();
            return;
        }
        ChatExecutionInstance execution = handle.execution();
        if (!execution.requestStop()) {
            return;
        }
        try {
            execution.interruptAgent();
        } catch (RuntimeException interruptFailure) {
            log.warn("协作式停止Run失败，将等待宽限期后强制停止: runId={}", run.getId(), interruptFailure);
        } finally {
            scheduler.schedule(
                    () -> execution.runInTenant(execution::forceStopIfRunning),
                    properties.getChat().getRun().getStopGraceSeconds(),
                    TimeUnit.SECONDS);
        }
    }

    /** 中断本节点活动阶段并关闭后台执行器与纯定时调度器。 */
    @PreDestroy
    public void shutdown() {
        List<LocalPhaseHandle> active = List.copyOf(activeLocalPhases.values());
        active.forEach(handle -> handle.execution().runInTenant(handle.execution()::interruptForShutdown));
        active.forEach(this::releaseLocalPhase);
        executionExecutor.shutdownNow();
        scheduler.shutdown();
    }

    /** 在后台执行器上装配用户消息（含附件与应用上下文）后订阅 Agent 首阶段。 */
    private void startExecution(ChatExecutionInstance execution) {
        ChatRunEntity run = execution.run();
        try {
            ChatMessageEntity userMessage = messageService
                    .findByIdAndSession(run.getUserMessageId(), run.getSessionId())
                    .orElseThrow(() -> new IllegalStateException("Run用户消息不存在: " + run.getId()));
            List<ChatAttachmentEntity> attachments = attachmentService.listByMessageIds(List.of(userMessage.getId()));
            AppEntity app = appService.loadById(execution.session().getAppId());
            Msg msg = attachmentMessageBuilder.buildUserMsg(
                    execution.session(), app, userMessage.getContent(), attachments);
            execution.startPhase(msg);
        } catch (RuntimeException startFailure) {
            execution.finalizeFailed(
                    ChatRunFailureCode.START_FAILED, ChatRunSnapshotSanitizer.safeMessage(startFailure));
        }
    }

    /** 启动被拒或构造失败时，用无 Agent 实例提交 {@code START_FAILED}，避免 Run 悬挂在 RUNNING。 */
    private void failStart(ChatRunEntity run, ChatSessionEntity session, RuntimeException failure) {
        ChatExecutionInstance rejected = instanceFactory.createTerminalOnly(run, session, scheduler);
        rejected.finalizeFailed(ChatRunFailureCode.START_FAILED, ChatRunSnapshotSanitizer.safeMessage(failure));
    }

    /**
     * 为本地无活动阶段的停止请求构造替代实例。无待交互投影时直接落 STOPPED；HITL 暂停中优先加载
     * AgentScope 状态补写拒绝结果，状态不可用时降级为纯业务终结。
     */
    private ChatExecutionInstance createStoppedExecution(
            ChatRunEntity run, ChatSessionEntity session, boolean noPendingInteraction) {
        if (noPendingInteraction) {
            return instanceFactory.createTerminalOnly(run, session, scheduler);
        }
        try {
            return instanceFactory.createAgentBacked(run, session, scheduler);
        } catch (RuntimeException stateUnavailable) {
            log.warn("停止Run时无法清理AgentScope待交互状态，仅提交业务终态: runId={}", run.getId(), stateUnavailable);
            return instanceFactory.createTerminalOnly(run, session, scheduler);
        }
    }

    /**
     * 跟踪当前 JVM 的阶段句柄。较旧阶段必须先排空；同一或更新阶段已存在时复用其协调结果。
     * 这里只协调本地资源，不判断 Run 是否有效，也不参与跨节点赢家裁决。
     */
    private LocalPhaseSelection trackLocalPhase(
            ChatExecutionInstance execution, Permit permit, int activePhaseNo, boolean resumePhase) {
        LocalPhaseHandle candidate = new LocalPhaseHandle(execution, permit, activePhaseNo, resumePhase);
        while (true) {
            LocalPhaseHandle existing =
                    activeLocalPhases.putIfAbsent(execution.run().getId(), candidate);
            if (existing == null) {
                execution.localPhaseDrainedSignal().whenComplete((ignored, failure) -> releaseLocalPhase(candidate));
                return new LocalPhaseSelection(candidate, true);
            }
            if (existing.activePhaseNo() < activePhaseNo) {
                existing.execution()
                        .localPhaseDrainedSignal()
                        .toCompletableFuture()
                        .join();
                releaseLocalPhase(existing);
                continue;
            }
            permit.close();
            return new LocalPhaseSelection(existing, false);
        }
    }

    /** 按句柄身份摘除本地阶段并幂等释放容量许可。 */
    private void releaseLocalPhase(LocalPhaseHandle handle) {
        activeLocalPhases.remove(handle.execution().run().getId(), handle);
        handle.permit().close();
    }

    private record LocalPhaseSelection(LocalPhaseHandle handle, boolean created) {}

    /** 当前 JVM 单个执行阶段的资源句柄；不持久化，也不表达 Run 所有权。 */
    private static final class LocalPhaseHandle {

        private final ChatExecutionInstance execution;
        private final Permit permit;
        private final int activePhaseNo;
        private final CompletableFuture<ConfirmTransition> resumeTransition;

        private LocalPhaseHandle(
                ChatExecutionInstance execution, Permit permit, int activePhaseNo, boolean resumePhase) {
            this.execution = execution;
            this.permit = permit;
            this.activePhaseNo = activePhaseNo;
            this.resumeTransition = resumePhase ? new CompletableFuture<>() : null;
        }

        private ChatExecutionInstance execution() {
            return execution;
        }

        private Permit permit() {
            return permit;
        }

        private int activePhaseNo() {
            return activePhaseNo;
        }

        private CompletableFuture<ConfirmTransition> resumeTransition() {
            return resumeTransition;
        }

        private void completeResume(ConfirmTransition transition) {
            if (resumeTransition != null) {
                resumeTransition.complete(transition);
            }
        }

        private void failResume(RuntimeException failure) {
            if (resumeTransition != null) {
                resumeTransition.completeExceptionally(failure);
            }
        }
    }
}
