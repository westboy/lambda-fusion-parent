package com.lambda.fusion.ai.chat.service;

import com.lambda.fusion.ai.chat.model.ChatRunFinalizationCommand;
import com.lambda.fusion.ai.chat.model.ChatRunFinalizationResult;
import com.lambda.fusion.ai.chat.model.ConfirmTransition;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;

/**
 * 对话 Run 的运行时状态机面：仅供 {@code ChatRunCoordinator} 使用，不面向 Controller 链路暴露。与
 * {@link ChatRunService}（HTTP 编排面）的边界：方法不做会话归属校验（入参为已过权的领域对象）；状态迁移均以
 * {@code REQUIRES_NEW} 独立提交，单次状态推进/快照不被驱动事务的后续失败回滚（Run 进度可恢复的关键）；迁移以
 * 「带前置条件的 UPDATE」(CAS) 实现，{@code changed == 1} 才视为成功；终结契约
 * {@link ChatRunFinalizationCommand} / {@link ChatRunFinalizationResult}
 * 定义在 {@code chat.model}（ChatRun 域词汇）。
 *
 * <p>ChatRun 是 AgentScope 之上的业务状态，不解释执行节点、租约、接管或故障转移；AgentScope 负责底层执行与状态。
 */
public interface ChatRunStateService {

    /**
     * 以 {@code status=RUNNING} 与快照版本号为前置条件 CAS 写入运行中 UI 快照。
     * 版本围栏保证迟到的检查点不会覆盖更新的快照（待交互提交、下一阶段初始快照）。
     *
     * @param run 运行实体
     * @param snapshot 执行快照
     * @param expectedSnapshotVersion 快照构建时的持久化版本号
     * @return 是否写入成功；终态记录或版本碰撞拒绝写入时返回 {@code false}
     */
    boolean checkpoint(ChatRunEntity run, ChatRunSnapshot snapshot, long expectedSnapshotVersion);

    /**
     * 在独立事务内推进确认：复核会话归属、做阶段幂等守卫与状态校验，以 {@code (status=RUNNING, phaseNo)}
     * 为前置条件推进到下一阶段。AgentScope ASKING 是确认状态的权威来源，快照只保存前端恢复投影。
     *
     * @param identity 运行标识实体（携带 id 与 sessionId）
     * @param expectedSession 调用方已校验归属的会话，用于复核用户归属
     * @param sourcePhaseNo 确认来源阶段号
     * @param snapshot 当前待确认快照
     * @return 迁移结果；{@code resumed=false} 表示阶段已被处理过（幂等重放）
     */
    ConfirmTransition advanceConfirmation(
            ChatRunEntity identity, ChatSessionEntity expectedSession, int sourcePhaseNo, ChatRunSnapshot snapshot);

    ChatRunFinalizationResult finalizeExecution(ChatRunEntity run, ChatRunFinalizationCommand command);

    ChatRunEntity loadCurrent(String runId);

    /** 查询最新持久化运行；不存在时返回传入的标识实体。 */
    default ChatRunEntity loadCurrentOrIdentity(ChatRunEntity identity) {
        ChatRunEntity current = loadCurrent(identity.getId());
        return current == null ? identity : current;
    }
}
