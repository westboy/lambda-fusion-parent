package com.lambda.fusion.ai.chat.service;

import com.lambda.fusion.ai.chat.model.ChatRunStep;
import java.util.List;

/**
 * 对话运行执行步骤存储：推理段/文本段/工具调用时间线的增量落库与查询。
 *
 * @author Jin
 */
public interface ChatRunStepService {

    /**
     * 幂等追加步骤：同一 (runId, stepNo) 重复写入按幂等跳过，其余行照常入库。
     *
     * @param sessionId 会话ID
     * @param runId     运行ID
     * @param steps     待追加步骤（按 stepNo 升序）
     */
    void appendSteps(String sessionId, String runId, List<ChatRunStep> steps);

    /**
     * 查询运行已持久化的最大步骤序号，供执行实例重建快照累加器时续接序号。
     *
     * @param runId 运行ID
     * @return 最大步骤序号；无记录时返回 {@code 0}
     */
    int maxStepNo(String runId);

    /**
     * 按助手消息查询执行步骤时间线：先校验会话归属，再经 Run 反查步骤，按 stepNo 升序返回。
     *
     * @param sessionId 会话ID
     * @param messageId 助手消息ID
     * @return 步骤时间线
     */
    List<ChatRunStep> listByMessage(String sessionId, Long messageId);
}
