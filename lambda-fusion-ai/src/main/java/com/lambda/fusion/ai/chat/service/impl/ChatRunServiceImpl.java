package com.lambda.fusion.ai.chat.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lambda.fusion.ai.AiConstants.ChatRunFailureCode;
import com.lambda.fusion.ai.AiConstants.ChatRunFinishReason;
import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.apps.service.AppService;
import com.lambda.fusion.ai.chat.mapper.ChatRunMapper;
import com.lambda.fusion.ai.chat.mapper.ChatSessionMapper;
import com.lambda.fusion.ai.chat.model.ChatRun;
import com.lambda.fusion.ai.chat.model.ChatRunFinalizationCommand;
import com.lambda.fusion.ai.chat.model.ChatRunFinalizationResult;
import com.lambda.fusion.ai.chat.model.ConfirmTransition;
import com.lambda.fusion.ai.chat.model.RunContext;
import com.lambda.fusion.ai.chat.model.SendMessage;
import com.lambda.fusion.ai.chat.model.entity.ChatMessageEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshotCodec;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.chat.service.ChatMessageService;
import com.lambda.fusion.ai.chat.service.ChatRunService;
import com.lambda.fusion.ai.chat.service.ChatRunStateService;
import com.lambda.fusion.ai.chat.service.ChatSessionService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.core.service.AbstractCrudService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对话运行的唯一持久化服务，负责幂等创建、检查点写入、确认迁移与终态提交。同时实现
 * {@link ChatRunService}（HTTP 编排入口，校验会话归属）与 {@link ChatRunStateService}
 * （执行状态机入口，不做归属校验）；后者各方法在 {@link Propagation#REQUIRES_NEW}
 * 独立事务中提交，保证执行流程后续失败时仍可依据已保存状态恢复。
 *
 * <p>ChatRun 只保留 {@code RUNNING/COMPLETED/STOPPED/FAILED} 四个业务状态，AgentScope
 * 的执行会话与 ASKING 等底层状态不复制到本表。状态迁移均为带前置条件的 UPDATE（CAS），
 * 只有影响一行才视为成功，终态会拒绝迟到的写入；创建请求以 {@code clientRequestId} 去重，
 * 并用 {@code requestHash} 校验请求内容，重复终态提交幂等返回既有结果。
 *
 * @author Jin
 */
@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class ChatRunServiceImpl extends AbstractCrudService<ChatRunEntity, ChatRun, ChatRunMapper>
        implements ChatRunService, ChatRunStateService {

    private final ChatRunMapper runMapper;
    private final ChatSessionMapper sessionMapper;
    private final ChatSessionService sessionService;
    private final ChatMessageService messageService;
    private final ChatAttachmentService attachmentService;
    private final AppService appService;
    /**
     * 幂等创建或加载运行：同一 {@code clientRequestId} 复用已有记录并校验 {@code requestHash} 一致，
     * 否则在会话无活动运行时创建新 Run、保存用户消息并绑定附件。
     *
     * <p>同一会话同一时刻只允许一个活动运行，已有活动 Run 时抛 {@code CHAT_RUN_ALREADY_ACTIVE}。
     *
     * @param sessionId 会话标识
     * @param message 发送消息请求
     * @return 运行上下文；{@code RunContext.created} 标识本次是否新创建
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public RunContext createOrLoad(String sessionId, SendMessage message) {
        ChatSessionEntity session = sessionService.loadOwnedForUpdate(sessionId);
        String requestHash = hashRequest(message);
        // 命中同一 clientRequestId 时复用已有运行，并继续校验请求内容是否一致。
        ChatRunEntity existing = findByRequest(sessionId, message.getClientRequestId());
        if (existing != null) {
            requireSameRequest(existing, requestHash);
            return new RunContext(existing, session, false);
        }
        appService.loadAvailable(session.getAppId());
        // 同一会话只允许一个活动运行，避免多个执行上下文竞争状态槽。
        ChatRunEntity active = findActive(sessionId);
        if (active != null) {
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_ALREADY_ACTIVE, active.getId());
        }

        LocalDateTime now = LocalDateTime.now();
        ChatRunEntity run = new ChatRunEntity();
        run.setId(IdUtil.getSnowflakeNextIdStr());
        run.setSessionId(sessionId);
        run.setClientRequestId(message.getClientRequestId());
        run.setRequestHash(requestHash);
        run.setStatus(ChatRunStatus.RUNNING.name());
        run.setPhaseNo(1);
        run.setAguiRunId(newAguiRunId());
        run.setStartedAt(now);
        runMapper.insert(run);

        // 先保存用户消息并绑定附件，再回填消息 ID 和初始空快照。
        ChatMessageEntity userMessage =
                messageService.saveUserMessage(session, StringUtils.defaultString(message.getContent()));
        if (message.getAttachmentIds() != null) {
            attachmentService.bindToMessage(
                    session, message.getAttachmentIds().stream().distinct().toList(), userMessage.getId());
        }
        run.setUserMessageId(userMessage.getId());
        run.setSnapshotJson(
                ChatRunSnapshotCodec.encode(ChatRunSnapshot.empty(run.getId(), run.getAguiRunId(), run.getPhaseNo())));
        runMapper.updateById(run);
        // 刷新会话最近消息时间，用于会话列表排序。
        sessionMapper.update(
                null,
                new LambdaUpdateWrapper<ChatSessionEntity>()
                        .eq(ChatSessionEntity::getId, sessionId)
                        .set(ChatSessionEntity::getLastMessageAt, now)
                        .set(ChatSessionEntity::getUpdatedAt, now));
        return new RunContext(run, session, true);
    }

    /** 查询当前用户拥有的会话下唯一活跃 Run。 */
    @Override
    public Optional<ChatRun> getActiveOwned(String sessionId) {
        sessionService.loadOwned(sessionId);
        return Optional.ofNullable(findActive(sessionId)).map(this::toRunView);
    }

    /**
     * 按运行 ID 载入当前用户拥有的 Run 视图。
     *
     * @param sessionId 会话标识
     * @param runId 运行标识
     * @return Run 视图（含 RUNNING 下的待确认工具投影）
     */
    @Override
    public ChatRun getOwned(String sessionId, String runId) {
        return toRunView(loadOwned(sessionId, runId).run());
    }

    /**
     * 载入当前用户拥有的 Run 及其会话。
     *
     * @param sessionId 会话标识
     * @param runId 运行标识
     * @return 运行上下文（实体 + 会话）
     * @throws AiBusinessException 会话无该 Run 时抛 {@code CHAT_RUN_NOT_FOUND}
     */
    @Override
    public RunContext loadOwned(String sessionId, String runId) {
        ChatSessionEntity session = sessionService.loadOwned(sessionId);
        ChatRunEntity run = runMapper.selectOne(ownedQuery(sessionId, runId));
        if (run == null) {
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_NOT_FOUND, runId);
        }
        return new RunContext(run, session);
    }

    /**
     * 在独立事务中推进用户确认。方法复核资源归属并校验阶段，以
     * {@code status=RUNNING, phaseNo=sourcePhaseNo} 为前置条件执行 CAS 迁移。
     * 运行已越过来源阶段时按重复确认返回 {@code resumed=false}；运行尚未到达该阶段时按过期命令处理。
     * 确认内容由执行实例校验，本方法只负责权威状态迁移。
     *
     * @param identity 执行实例持有的运行实体（身份用）
     * @param expectedSession 执行实例持有的会话实体（用于复核归属）
     * @param sourcePhaseNo 确认命令的来源阶段号
     * @param snapshot 待确认快照（阶段号与待确认工具已校验）
     * @return 迁移结果；{@code resumed=false} 表示重复确认
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ConfirmTransition advanceConfirmation(
            ChatRunEntity identity, ChatSessionEntity expectedSession, int sourcePhaseNo, ChatRunSnapshot snapshot) {
        // 在行锁内复核会话所有权和运行归属，使并发确认串行化。
        ChatSessionEntity session = sessionMapper.selectOne(new LambdaQueryWrapper<ChatSessionEntity>()
                .eq(ChatSessionEntity::getId, identity.getSessionId())
                .eq(ChatSessionEntity::getUserId, expectedSession.getUserId())
                .last("FOR UPDATE"));
        if (session == null) {
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_NOT_FOUND, identity.getId());
        }
        ChatRunEntity run = runMapper.selectOne(new LambdaQueryWrapper<ChatRunEntity>()
                .eq(ChatRunEntity::getId, identity.getId())
                .eq(ChatRunEntity::getSessionId, session.getId())
                .last("FOR UPDATE"));
        if (run == null) {
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_NOT_FOUND, identity.getId());
        }
        // 当前阶段号更大表示确认已处理，幂等返回；更小表示命令尚不适用于当前阶段。
        if (!Objects.equals(run.getPhaseNo(), sourcePhaseNo)) {
            if (run.getPhaseNo() > sourcePhaseNo) {
                return new ConfirmTransition(run, session, false);
            }
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_STATE_CONFLICT, run.getStatus());
        }
        // ChatRun 不复制 AgentScope ASKING 状态；RUNNING 仅表示这次业务请求尚未终结。
        // 确认与输入两类待交互投影共用本推进：待确认（ASKING）或待输入（AWAITING_INPUT）任一非空即可。
        if (!ChatRunStatus.RUNNING.name().equals(run.getStatus())
                || snapshot == null
                || !Objects.equals(snapshot.runId(), run.getId())
                || !Objects.equals(snapshot.aguiRunId(), run.getAguiRunId())
                || snapshot.phaseNo() != sourcePhaseNo
                || (snapshot.pendingTools().isEmpty()
                        && snapshot.pendingInputs().isEmpty())) {
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_STATE_CONFLICT, run.getStatus());
        }

        int targetPhase = run.getPhaseNo() + 1;
        String targetAguiRunId = newAguiRunId();
        ChatRunSnapshot nextSnapshot = snapshot.beginPhase(targetAguiRunId, targetPhase);
        // 阶段号是确认幂等键；同事务清除待确认 UI 投影，避免重连读到旧阶段中断。
        LocalDateTime now = LocalDateTime.now();
        int changed = runMapper.update(
                null,
                new LambdaUpdateWrapper<ChatRunEntity>()
                        .eq(ChatRunEntity::getId, run.getId())
                        .eq(ChatRunEntity::getStatus, ChatRunStatus.RUNNING.name())
                        .eq(ChatRunEntity::getPhaseNo, sourcePhaseNo)
                        .set(ChatRunEntity::getPhaseNo, targetPhase)
                        .set(ChatRunEntity::getAguiRunId, targetAguiRunId)
                        .set(ChatRunEntity::getSnapshotJson, ChatRunSnapshotCodec.encode(nextSnapshot))
                        .set(ChatRunEntity::getUpdatedAt, now));
        if (changed != 1) {
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_STATE_CONFLICT, run.getId());
        }
        // 用迁移后的值刷新内存对象，供调用方继续执行。
        run.setStatus(ChatRunStatus.RUNNING.name());
        run.setPhaseNo(targetPhase);
        run.setAguiRunId(targetAguiRunId);
        run.setSnapshotJson(ChatRunSnapshotCodec.encode(nextSnapshot));
        return new ConfirmTransition(run, session, true);
    }

    /**
     * 在独立事务中以 {@code status=RUNNING} 为前置条件 CAS 写入运行中 UI 快照。
     *
     * @param run 运行实体
     * @param snapshot 执行快照
     * @return 是否写入成功；终态记录拒绝迟到的快照写入时返回 {@code false}
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean checkpoint(ChatRunEntity run, ChatRunSnapshot snapshot) {
        LocalDateTime now = LocalDateTime.now();
        return runMapper.update(
                        null,
                        new LambdaUpdateWrapper<ChatRunEntity>()
                                .eq(ChatRunEntity::getId, run.getId())
                                .eq(ChatRunEntity::getStatus, ChatRunStatus.RUNNING.name())
                                .set(ChatRunEntity::getSnapshotJson, ChatRunSnapshotCodec.encode(snapshot))
                                .set(ChatRunEntity::getUpdatedAt, now))
                == 1;
    }

    /**
     * 在独立事务中提交运行终态与助手消息并清空运行中快照；已有终态时幂等返回既有结果。
     *
     * <p>会话与运行行先加锁串行化并发终结；仅 COMPLETED 或仍有正文/工具调用输出时落库助手消息，
     * STOPPED 终态不保留错误码与错误消息。
     *
     * @param identity 执行实例持有的运行实体（身份用）
     * @param command 终态提交命令（目标状态、快照、结束原因与错误信息）
     * @return 终态提交结果；{@code committed} 标识本次是否实际写入
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ChatRunFinalizationResult finalizeExecution(ChatRunEntity identity, ChatRunFinalizationCommand command) {
        // 悲观锁定会话和运行，使终结过程与其他状态迁移串行执行。
        ChatSessionEntity session = sessionMapper.selectForUpdate(identity.getSessionId());
        if (session == null) {
            throw new IllegalStateException("Run会话不存在，无法终结: " + identity.getSessionId());
        }
        ChatRunEntity run = runMapper.selectOne(new LambdaQueryWrapper<ChatRunEntity>()
                .eq(ChatRunEntity::getId, identity.getId())
                .last("FOR UPDATE"));
        if (run == null) {
            throw new IllegalStateException("Run不存在，无法终结: " + identity.getId());
        }
        // 已终态：幂等返回 committed=false，携带既有终态信息。
        if (ChatRunStatus.isTerminal(run.getStatus())) {
            return new ChatRunFinalizationResult(
                    false, run.getStatus(), run.getFinishReason(), run.getErrorCode(), run.getErrorMessage());
        }

        ChatRunStatus targetStatus = command.targetStatus();
        ChatRunSnapshot snapshot = command.snapshot();
        String toolCallJson = command.toolCallJson();
        ChatRunFinishReason finalReason = command.finishReason();
        ChatRunFailureCode finalErrorCode = targetStatus == ChatRunStatus.STOPPED ? null : command.errorCode();
        String finalErrorMessage = targetStatus == ChatRunStatus.STOPPED ? null : command.errorMessage();

        // 仅 COMPLETED 或仍有正文/工具调用时才落库助手消息。
        ChatMessageEntity assistant = null;
        if (targetStatus == ChatRunStatus.COMPLETED || !snapshot.text().isBlank() || toolCallJson != null) {
            assistant = messageService.saveAssistantMessage(session, snapshot.text(), toolCallJson);
        }
        List<String> generatedAttachmentIds = command.generatedAttachmentIds() == null
                ? List.of()
                : command.generatedAttachmentIds().stream().distinct().toList();
        if (!generatedAttachmentIds.isEmpty()) {
            if (assistant == null) {
                attachmentService.discardGenerated(session.getId(), generatedAttachmentIds);
            } else {
                attachmentService.bindToMessage(session, generatedAttachmentIds, assistant.getId());
            }
        }
        LocalDateTime now = LocalDateTime.now();
        // 行已在上方 FOR UPDATE 锁内确认为非终态，notIn 终态前置条件属锁内双保险，正常不可能未命中；
        // 真发生时抛 IllegalStateException，由上游按永久性失败处理（放弃重试、释放实例）。
        int changed = runMapper.update(
                null,
                new LambdaUpdateWrapper<ChatRunEntity>()
                        .eq(ChatRunEntity::getId, run.getId())
                        .notIn(ChatRunEntity::getStatus, ChatRunStatus.terminalNames())
                        .set(ChatRunEntity::getStatus, targetStatus.name())
                        .set(ChatRunEntity::getFinishReason, finalReason == null ? null : finalReason.name())
                        .set(ChatRunEntity::getAssistantMessageId, assistant == null ? null : assistant.getId())
                        .set(ChatRunEntity::getSnapshotJson, null)
                        .set(ChatRunEntity::getErrorCode, finalErrorCode == null ? null : finalErrorCode.name())
                        .set(ChatRunEntity::getErrorMessage, finalErrorMessage)
                        .set(ChatRunEntity::getFinishedAt, now)
                        .set(ChatRunEntity::getUpdatedAt, now));
        if (changed != 1) {
            throw new IllegalStateException("Run终结状态更新失败: " + run.getId());
        }
        // 刷新会话最近消息时间。
        sessionMapper.update(
                null,
                new LambdaUpdateWrapper<ChatSessionEntity>()
                        .eq(ChatSessionEntity::getId, run.getSessionId())
                        .eq(ChatSessionEntity::getUserId, session.getUserId())
                        .set(ChatSessionEntity::getLastMessageAt, now)
                        .set(ChatSessionEntity::getUpdatedAt, now));
        return new ChatRunFinalizationResult(
                true,
                targetStatus.name(),
                finalReason == null ? null : finalReason.name(),
                finalErrorCode == null ? null : finalErrorCode.name(),
                finalErrorMessage);
    }

    /** 按运行 ID 加载实体，不校验用户归属，仅供内部执行状态机使用。 */
    @Override
    public ChatRunEntity loadCurrent(String runId) {
        return runMapper.selectById(runId);
    }

    /** 实体转视图；RUNNING 下的待确认工具与待输入挂起调用来自持久化快照投影。 */
    private ChatRun toRunView(ChatRunEntity entity) {
        ChatRun view = toVO(entity);
        ChatRunSnapshot snapshot = ChatRunSnapshotCodec.decode(entity.getSnapshotJson());
        boolean running = ChatRunStatus.RUNNING.name().equals(entity.getStatus());
        view.setPendingConfirm(
                running && !snapshot.pendingTools().isEmpty()
                        ? snapshot.pendingTools().stream()
                                .map(tool -> new ChatRun.PendingTool(tool.toolCallId(), tool.toolCallName()))
                                .toList()
                        : List.of());
        view.setPendingInputs(
                running && !snapshot.pendingInputs().isEmpty()
                        ? snapshot.pendingInputs().stream()
                                .map(input -> new ChatRun.PendingInput(
                                        input.toolCallId(),
                                        input.toolCallName(),
                                        input.question(),
                                        input.inputKind(),
                                        input.responseSchemaJson()))
                                .toList()
                        : List.of());
        return view;
    }

    /** 按会话 ID 和客户端请求 ID 查找已有运行，用于幂等去重。 */
    private ChatRunEntity findByRequest(String sessionId, String requestId) {
        return runMapper.selectOne(new LambdaQueryWrapper<ChatRunEntity>()
                .eq(ChatRunEntity::getSessionId, sessionId)
                .eq(ChatRunEntity::getClientRequestId, requestId));
    }

    /** 查找会话内非终态的活跃 Run。 */
    private ChatRunEntity findActive(String sessionId) {
        return runMapper.selectOne(new LambdaQueryWrapper<ChatRunEntity>()
                .eq(ChatRunEntity::getSessionId, sessionId)
                .notIn(ChatRunEntity::getStatus, ChatRunStatus.terminalNames()));
    }

    /** 使用运行 ID 和会话 ID 构造双键查询，确保运行归属目标会话。 */
    private static LambdaQueryWrapper<ChatRunEntity> ownedQuery(String sessionId, String runId) {
        return new LambdaQueryWrapper<ChatRunEntity>()
                .eq(ChatRunEntity::getId, runId)
                .eq(ChatRunEntity::getSessionId, sessionId);
    }

    /** 校验既有 Run 的请求体哈希一致，不一致视为同 ID 不同请求体冲突。 */
    private static void requireSameRequest(ChatRunEntity existing, String requestHash) {
        if (!Objects.equals(existing.getRequestHash(), requestHash)) {
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_REQUEST_CONFLICT, existing.getClientRequestId());
        }
    }

    /** 计算请求体规范化哈希：内容 + 排序去重附件，采用 length-prefixed 编码避免拼接歧义。 */
    private static String hashRequest(SendMessage message) {
        List<String> attachments = message.getAttachmentIds() == null
                ? List.of()
                : message.getAttachmentIds().stream().distinct().sorted().toList();
        StringBuilder canonical = new StringBuilder();
        appendHashPart(canonical, StringUtils.defaultString(message.getContent()));
        attachments.forEach(attachment -> appendHashPart(canonical, attachment));
        return sha256(canonical.toString());
    }

    /** 以 {@code 长度:值} 形式追加一段，防止不同分段拼接出相同哈希。 */
    private static void appendHashPart(StringBuilder target, String value) {
        String safeValue = StringUtils.defaultString(value);
        target.append(safeValue.length()).append(':').append(safeValue);
    }

    /** SHA-256 十六进制摘要。 */
    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 生成新的 AGUI 侧 Run 标识。 */
    private static String newAguiRunId() {
        return "agui-" + IdUtil.randomUUID();
    }
}
