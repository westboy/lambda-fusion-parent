package com.fusion.startup.leave.listener;

import com.fusion.startup.leave.model.LeaveEntity;
import com.fusion.startup.leave.service.LeaveService;
import com.lambda.fusion.workflow.WorkflowConstants;
import com.lambda.fusion.workflow.bridge.WorkflowFlowEvent;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 请假单流程状态回写
 *
 * <p>监听 workflow 模块 {@link WorkflowFlowEvent}，在 FINISH 钩子把引擎终态映射为
 * 业务状态机。事件无 bizType 字段，回写按 businessId（= 请假单 ID）定位，查无记录静默忽略。
 *
 * <p>引擎 flowStatus 分组口径由 workflow 模块 {@link WorkflowConstants#isPassed} 等谓词统一承载
 * （审批通过到达结束节点实例终态为 8 而非 2），本类只做分组 → 业务状态的映射。
 * 事件同步发布（同线程同事务），无需恢复租户上下文。
 *
 * @author jin
 */
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class LeaveFlowStatusUpdater {

    private static final int IGNORE = -1;

    private final LeaveService leaveService;

    @EventListener
    public void on(WorkflowFlowEvent event) {
        if (event.type() != WorkflowConstants.Type.FINISH) {
            return;
        }
        int status = mapStatus(event.flowStatus());
        if (status != IGNORE) {
            leaveService.updateFlowStatus(event.businessId(), status);
        }
    }

    /**
     * 引擎 flowStatus → 业务状态（0 草稿 1 审批中 2 通过 3 驳回）；中间态返回 {@link #IGNORE}。
     * 分组口径统一消费 {@link WorkflowConstants} 谓词，不逐值硬编码
     */
    private int mapStatus(String flowStatus) {
        if (WorkflowConstants.isPassed(flowStatus)) {
            return LeaveEntity.STATUS_APPROVED;
        }
        if (WorkflowConstants.isReturned(flowStatus)) {
            return LeaveEntity.STATUS_DRAFT;
        }
        if (WorkflowConstants.isTerminated(flowStatus)) {
            return LeaveEntity.STATUS_REJECTED;
        }
        return IGNORE;
    }
}
