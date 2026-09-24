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
 * 请假单流程状态回写（§10.2 ④）
 *
 * <p>监听 workflow 模块 {@link WorkflowFlowEvent}，在 FINISH 钩子把引擎终态映射为
 * 业务状态机。事件无 bizType 字段，回写按 businessId（= 请假单 ID）定位，查无记录静默忽略。
 *
 * <p>引擎 flowStatus 口径（warm-flow 1.8.9 源码核实，TaskServiceImpl#setFlowStatus）：
 * 审批通过到达结束节点实例终态为 8（已完成）而非 2；退回到发起节点为 9；撤销为 6；
 * 终止/作废/失效为 4/5/10。事件同步发布（同线程同事务），无需恢复租户上下文。
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
     * 引擎 flowStatus → 业务状态（0 草稿 1 审批中 2 通过 3 驳回）；中间态返回 {@link #IGNORE}
     */
    private int mapStatus(String flowStatus) {
        if (flowStatus == null) {
            return IGNORE;
        }
        return switch (flowStatus) {
            // 2 审批通过 / 3 自动完成 / 8 已完成（结束节点终态，源码核实口径）
            case "2", "3", "8" -> LeaveEntity.STATUS_APPROVED;
            // 9 退回到发起节点 / 6 撤销 → 回草稿可编辑重提
            case "9", "6" -> LeaveEntity.STATUS_DRAFT;
            // 4 终止 / 5 作废 / 10 失效 → 驳回终态
            case "4", "5", "10" -> LeaveEntity.STATUS_REJECTED;
            // 0 待提交 / 1 审批中 / 7 取回 / 11 拿回 / 12 重启 / 13 暂存：流程仍在途，不回写
            default -> IGNORE;
        };
    }
}
