package com.fusion.startup.leave.service;

import com.fusion.startup.leave.model.LeaveEntity;
import com.fusion.startup.leave.model.LeavePage;
import com.fusion.startup.leave.model.LeaveSaveCommand;
import com.lambda.fusion.core.pagination.PageView;

/**
 * 请假单服务（工作流演示业务，docs/design/workflow.md §10.2 五步范式样板）
 *
 * @author jin
 */
public interface LeaveService {

    /**
     * 分页查询（租户内全量，演示视角）
     */
    PageView<LeaveEntity> page(LeavePage page);

    /**
     * 详情
     */
    LeaveEntity detail(String id);

    /**
     * 新增（固定落草稿）
     */
    void save(LeaveSaveCommand command);

    /**
     * 修改（仅草稿可改）
     */
    void update(String id, LeaveSaveCommand command);

    /**
     * 提交审批：状态机校验草稿 → 置审批中 → 发起 leave_demo 流程（§10.2 ②）
     */
    void submit(String id);

    /**
     * 删除（仅草稿可删）
     */
    void remove(String id);

    /**
     * 流程终态回写（供 {@code LeaveFlowStatusUpdater} 事件回调；businessId 即请假单 ID，
     * 查无记录静默忽略——事件无 bizType 字段，靠此路由）
     */
    void updateFlowStatus(String businessId, int status);
}
