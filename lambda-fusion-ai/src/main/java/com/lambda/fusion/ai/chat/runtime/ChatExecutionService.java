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
import io.agentscope.core.message.ToolUseBlock;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 对话运行协调门面：负责业务 Run 的启动、确认与停止编排。活动实例注册表与容量约束由
 * {@link ChatExecutionInstanceRegistry} 承载；
 * Agent 事件流由执行实例持有，不依赖 SSE 连接生命周期。新运行注册后立即异步订阅 {@code streamEvents}；
 * 同一 {@code (userId, sessionId)} 的核心状态调用由 AgentScope 自身串行保护，上一轮记忆整理等后处理
 * 不阻塞下一轮交互。
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
    private final ChatExecutionInstanceRegistry registry;
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
        this.registry = new ChatExecutionInstanceRegistry(instanceFactory, properties);
    }

    /** 启动本次请求刚创建的运行；显式媒体请求携带服务端构造的 AgentScope 工具调用。 */
    public void start(ChatRunEntity run, ChatSessionEntity session, ToolUseBlock mediaToolCall) {
        TenantUtils.withTenant(session.getTenantId(), () -> {
            startInTenantContext(run, session, mediaToolCall);
            return null;
        });
    }

    private void startInTenantContext(ChatRunEntity run, ChatSessionEntity session, ToolUseBlock mediaToolCall) {
        if (!ChatRunStatus.RUNNING.name().equals(run.getStatus())) {
            return;
        }
        Optional<ChatExecutionInstance> selected;
        try {
            selected = registry.registerForStartIfCapacity(run, session, scheduler);
        } catch (RuntimeException createFailure) {
            failStart(run, session, createFailure);
            return;
        }
        // 不做跨节点排队或后台扫描；无法在当前请求节点启动时立即给出可重试的失败结果。
        if (selected.isEmpty()) {
            failStart(run, session, new IllegalStateException("当前节点对话运行容量已满"));
            return;
        }
        // 注册成功后排空信号由注册表监听，最终源流结束时按实例身份安全摘除。
        // 只把实际订阅移出请求线程；不等待上一轮的记忆整理、Workspace 审计等后处理。
        ChatExecutionInstance execution = selected.get();
        try {
            scheduler.execute(() -> execution.runInTenant(() -> startExecution(execution, mediaToolCall)));
        } catch (RuntimeException scheduleFailure) {
            execution.finalizeFailed(
                    ChatRunFailureCode.START_FAILED, ChatRunSnapshotSanitizer.safeMessage(scheduleFailure));
        }
    }

    /**
     * 在规范实例锁内原子地确认并推进到下一阶段。只有上一阶段完整排空并进入待确认态后才允许确认；
     * 锁顺序始终为实例 monitor，再进入 {@code REQUIRES_NEW} 数据库事务。
     *
     * @param run 运行实体
     * @param session 会话实体
     * @param command 用户确认命令
     * @return 迁移结果；{@code resumed=false} 表示来源阶段已经被处理
     */
    public ConfirmTransition confirm(ChatRunEntity run, ChatSessionEntity session, ConfirmToolCall command) {
        return TenantUtils.withTenant(session.getTenantId(), () -> {
            ChatExecutionInstance execution = registry.get(run.getId());
            if (execution == null) {
                Integer sourcePhaseNo = command == null ? null : command.getPhaseNo();
                if (sourcePhaseNo == null) {
                    throw new AiBusinessException(AiErrorCode.INVALID_PARAMETER, "phaseNo不能为空");
                }
                if (run.getPhaseNo() > sourcePhaseNo) {
                    return new ConfirmTransition(run, session, false);
                }
                var snapshot = ChatRunSnapshotCodec.decode(run.getSnapshotJson());
                if (!ChatRunStatus.RUNNING.name().equals(run.getStatus())
                        || run.getPhaseNo() < sourcePhaseNo
                        || snapshot.pendingTools().isEmpty()) {
                    throw new AiBusinessException(AiErrorCode.CHAT_RUN_STATE_CONFLICT, run.getStatus());
                }
                // 进程重启会丢失本地注册表，但 AgentScope ASKING 状态和 Run 快照均已持久化。
                // 用户显式确认时只重建暂停上下文，不恢复旧阶段，也不接管正在执行的工具。
                execution = registry.registerPausedConfirmation(run, session, scheduler);
            }
            return execution.confirm(command);
        });
    }

    /**
     * 在规范实例锁内原子地提交用户输入并推进到下一阶段。只有上一阶段完整排空并进入待输入态后才允许提交；
     * 锁顺序始终为实例 monitor，再进入 {@code REQUIRES_NEW} 数据库事务。
     *
     * @param run 运行实体
     * @param session 会话实体
     * @param command 用户输入命令
     * @return 迁移结果；{@code resumed=false} 表示来源阶段已经被处理
     */
    public ConfirmTransition submitInput(ChatRunEntity run, ChatSessionEntity session, SubmitToolInput command) {
        return TenantUtils.withTenant(session.getTenantId(), () -> {
            ChatExecutionInstance execution = registry.get(run.getId());
            if (execution == null) {
                Integer sourcePhaseNo = command == null ? null : command.getPhaseNo();
                if (sourcePhaseNo == null) {
                    throw new AiBusinessException(AiErrorCode.INVALID_PARAMETER, "phaseNo不能为空");
                }
                if (run.getPhaseNo() > sourcePhaseNo) {
                    return new ConfirmTransition(run, session, false);
                }
                var snapshot = ChatRunSnapshotCodec.decode(run.getSnapshotJson());
                if (!ChatRunStatus.RUNNING.name().equals(run.getStatus())
                        || run.getPhaseNo() < sourcePhaseNo
                        || snapshot.pendingInputs().isEmpty()) {
                    throw new AiBusinessException(AiErrorCode.CHAT_RUN_STATE_CONFLICT, run.getStatus());
                }
                // 进程重启会丢失本地注册表，但 AgentScope 挂起状态和 Run 快照均已持久化。
                // 用户显式提交时只重建暂停上下文，不恢复旧阶段，也不接管正在执行的工具。
                execution = registry.registerPausedConfirmation(run, session, scheduler);
            }
            return execution.submitInput(command);
        });
    }

    /**
     * 生成运行的 AG-UI 引导事件。本节点无实例的 RUNNING 运行已无实时流可续接：存在待交互投影的
     * HITL 挂起仍可由用户显式确认或输入恢复；其余视为进程重启遗留的孤儿运行，收敛为失败终态，
     * 使重连方获得明确终局并解除会话的活动运行锁。
     *
     * @param run 运行实体
     * @param session 会话实体
     * @return 引导事件批次
     */
    public AguiBootstrapModel bootstrap(ChatRunEntity run, ChatSessionEntity session) {
        ChatExecutionInstance execution = registry.get(run.getId());
        if (execution != null) {
            return execution.bootstrap();
        }
        ChatRunEntity current = runService.loadCurrentOrIdentity(run);
        ChatRunSnapshot snapshot = ChatRunSnapshotCodec.decode(current.getSnapshotJson());
        if (isOrphanedRun(current, snapshot)) {
            current = finalizeOrphaned(current, session);
            snapshot = ChatRunSnapshotCodec.decode(current.getSnapshotJson());
        }
        long cursor = eventStore.latestCursor(current.getId());
        return new AguiBootstrapModel(
                cursor,
                AguiBootstrapEncoder.encode(current, snapshot),
                ChatRunStatus.isTerminal(current.getStatus())
                        || snapshot.hasPendingInteraction()
                        || !eventStore.contains(current.getId()));
    }

    /** 判定无本地实例的运行是否为孤儿：仍处 RUNNING 且无待交互投影（HITL 挂起由确认/输入路径恢复）。 */
    private static boolean isOrphanedRun(ChatRunEntity run, ChatRunSnapshot snapshot) {
        return ChatRunStatus.RUNNING.name().equals(run.getStatus()) && !snapshot.hasPendingInteraction();
    }

    /**
     * 收敛孤儿运行为失败终态：优先用带 Agent 的实例补写未决工具调用（中断多发生在工具执行中，
     * 遗留未决调用会阻塞该状态会话的后续请求），Agent 状态不可用时降级为纯终结实例。
     * 落终态后回读数据库权威状态，使引导事件按终态编码。
     *
     * @param run 运行实体
     * @param session 会话实体
     * @return 终态后的运行实体
     */
    private ChatRunEntity finalizeOrphaned(ChatRunEntity run, ChatSessionEntity session) {
        log.warn("Run无本地实例且无待交互投影，按服务重启中断收敛: runId={}", run.getId());
        return TenantUtils.withTenant(session.getTenantId(), () -> {
            createOrphanExecution(run, session).finalizeFailed(ChatRunFailureCode.INTERRUPTED, "服务重启导致对话运行中断，请重新发送消息");
            return runService.loadCurrentOrIdentity(run);
        });
    }

    /** 为孤儿运行构造终结实例：优先带 Agent 以补写拒绝结果清理 AgentScope 未决工具调用，失败时降级为纯终结。 */
    private ChatExecutionInstance createOrphanExecution(ChatRunEntity run, ChatSessionEntity session) {
        try {
            return instanceFactory.createAgentBacked(run, session, scheduler);
        } catch (RuntimeException stateUnavailable) {
            log.warn("收敛孤儿Run时无法清理AgentScope未决工具调用，仅提交业务终态: runId={}", run.getId(), stateUnavailable);
            return instanceFactory.createTerminalOnly(run, session, scheduler);
        }
    }

    /**
     * 请求停止运行。
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
        ChatExecutionInstance execution = registry.get(current.getId());
        if (execution == null) {
            var snapshot = ChatRunSnapshotCodec.decode(current.getSnapshotJson());
            ChatExecutionInstance terminalOnly = createStoppedExecution(
                    current, session, snapshot.pendingTools().isEmpty());
            terminalOnly.requestStop();
            return;
        }
        // 本地活动实例仍按实例 monitor -> 独立数据库事务的顺序协作式停止。
        // 实例锁内先提交 STOPPED，迟到的 Agent 完成结果会被数据库终态拒绝。
        if (!execution.requestStop()) {
            return;
        }
        // 先在锁外发起协作式中断，再以宽限期后的强制停止作为兜底，避免取消回调造成锁重入。
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

    /** 中断本节点活动运行并关闭本地调度线程池。 */
    @PreDestroy
    public void shutdown() {
        registry.forEachActive(execution -> execution.runInTenant(execution::interruptForShutdown));
        scheduler.shutdown();
    }

    /**
     * 在调度线程上执行注册实例的首个阶段：装配用户消息（含附件与应用上下文）后交给实例启动。
     *
     * <p>用户消息或应用缺失视为启动失败，统一落 {@code START_FAILED} 终态并释放实例。
     *
     * @param execution 已注册的执行实例
     */
    private void startExecution(ChatExecutionInstance execution, ToolUseBlock mediaToolCall) {
        ChatRunEntity run = execution.run();
        try {
            ChatMessageEntity userMessage = messageService
                    .findByIdAndSession(run.getUserMessageId(), run.getSessionId())
                    .orElseThrow(() -> new IllegalStateException("Run用户消息不存在: " + run.getId()));
            List<ChatAttachmentEntity> attachments = attachmentService.listByMessageIds(List.of(userMessage.getId()));
            AppEntity app = appService.loadById(execution.session().getAppId());
            Msg msg = attachmentMessageBuilder.buildUserMsg(
                    execution.session(), app, userMessage.getContent(), attachments);
            execution.startPhase(msg, mediaToolCall);
        } catch (RuntimeException startFailure) {
            execution.finalizeFailed(
                    ChatRunFailureCode.START_FAILED, ChatRunSnapshotSanitizer.safeMessage(startFailure));
        }
    }

    /**
     * 启动被拒或构造失败时的兜底路径：用无 Agent 的纯终结实例提交 {@code START_FAILED} 终态，
     * 保证 Run 不会停留在 RUNNING 状态。
     *
     * @param run 运行实体
     * @param session 会话实体
     * @param failure 启动失败原因
     */
    private void failStart(ChatRunEntity run, ChatSessionEntity session, RuntimeException failure) {
        ChatExecutionInstance rejected = instanceFactory.createTerminalOnly(run, session, scheduler);
        rejected.finalizeFailed(ChatRunFailureCode.START_FAILED, ChatRunSnapshotSanitizer.safeMessage(failure));
    }

    /**
     * 为本地无活动实例的停止请求构造替代执行实例。
     *
     * <p>无待确认工具时用纯终结实例直接落 STOPPED；HITL 暂停中则优先用带 Agent 的实例补写
     * 拒绝结果、清理 AgentScope ASKING 状态，Agent 状态不可用时降级为纯终结实例。
     *
     * @param run 运行实体（已加载当前状态）
     * @param session 会话实体
     * @param noPendingConfirmation 快照中是否存在待确认工具（无则允许纯终结）
     * @return 承接停止请求的执行实例
     */
    private ChatExecutionInstance createStoppedExecution(
            ChatRunEntity run, ChatSessionEntity session, boolean noPendingConfirmation) {
        if (noPendingConfirmation) {
            return instanceFactory.createTerminalOnly(run, session, scheduler);
        }
        try {
            // HITL 已暂停且没有活动工具；加载 AgentScope 状态只为补写拒绝结果，避免 ASKING 污染后续对话。
            return instanceFactory.createAgentBacked(run, session, scheduler);
        } catch (RuntimeException stateUnavailable) {
            log.warn("停止Run时无法清理AgentScope待确认状态，仅提交业务终态: runId={}", run.getId(), stateUnavailable);
            return instanceFactory.createTerminalOnly(run, session, scheduler);
        }
    }
}
