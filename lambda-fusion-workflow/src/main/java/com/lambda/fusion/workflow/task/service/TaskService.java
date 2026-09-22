package com.lambda.fusion.workflow.task.service;

import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.workflow.instance.model.HistoryView;
import com.lambda.fusion.workflow.task.model.ApproveCommand;
import com.lambda.fusion.workflow.task.model.RejectCommand;
import com.lambda.fusion.workflow.task.model.SignatureCommand;
import com.lambda.fusion.workflow.task.model.TaskDonePage;
import com.lambda.fusion.workflow.task.model.TaskTodoPage;
import com.lambda.fusion.workflow.task.model.TaskView;
import com.lambda.fusion.workflow.task.model.TransferCommand;
import java.util.List;
import java.util.Map;

/**
 * 待办任务服务：待办/已办分页、通过/退回/转办/委派/加减签、管理端改办理人与任意跳转
 *
 * <p>审批动作的「谁能办」由引擎权限模型约束（当前用户权限标识须命中 flow_user.processed_by），
 * 本服务不重复做权限判定；REST 层管理端操作另挂 admin 权限码。
 *
 * @author jin
 */
public interface TaskService {

    /**
     * 我的待办分页
     */
    PageView<TaskView> pageTodo(TaskTodoPage page);

    /**
     * 我的已办分页
     */
    PageView<HistoryView> pageDone(TaskDonePage page);

    /**
     * 通过（流转到下一节点）
     */
    boolean pass(Long taskId, ApproveCommand command);

    /**
     * 退回上一节点
     */
    boolean reject(Long taskId, RejectCommand command);

    /**
     * 转办（任务移交他人办理，办理人变更为目标人）
     */
    boolean transfer(Long taskId, TransferCommand command);

    /**
     * 委派（他人审批后退回本人）
     */
    boolean depute(Long taskId, TransferCommand command);

    /**
     * 加签（为任务增加办理人）
     */
    boolean addSignature(Long taskId, SignatureCommand command);

    /**
     * 减签（移除任务办理人）
     */
    boolean reductionSignature(Long taskId, SignatureCommand command);

    /**
     * 管理端改办理人
     */
    boolean updateHandler(Long taskId, List<String> targetUsernames);

    /**
     * 管理端任意跳转到指定节点
     */
    boolean passAtWill(Long taskId, String nodeCode, String message, Map<String, Object> variable);
}
