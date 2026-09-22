package com.lambda.fusion.workflow.task.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.workflow.instance.model.HistoryView;
import com.lambda.fusion.workflow.task.model.ApproveCommand;
import com.lambda.fusion.workflow.task.model.RejectCommand;
import com.lambda.fusion.workflow.task.model.SignatureCommand;
import com.lambda.fusion.workflow.task.model.TaskDonePage;
import com.lambda.fusion.workflow.task.model.TaskTodoPage;
import com.lambda.fusion.workflow.task.model.TaskView;
import com.lambda.fusion.workflow.task.model.TransferCommand;
import com.lambda.fusion.workflow.task.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 待办任务 API：我的待办/已办、通过/退回/转办/委派/加减签；管理端改办理人与任意跳转
 *
 * <p>审批类接口仅要求登录——「谁能办」由引擎权限模型（flow_user 权限标识匹配）约束，
 * 非任务办理人调用会被引擎拒绝；管理端操作挂 admin 权限码。
 *
 * @author jin
 */
@RestController
@RequestMapping("/workflow/task")
@Tag(name = "待办任务")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @GetMapping("todo")
    @Operation(summary = "我的待办分页")
    public PageView<TaskView> pageTodo(TaskTodoPage page) {
        return taskService.pageTodo(page);
    }

    @GetMapping("done")
    @Operation(summary = "我的已办分页")
    public PageView<HistoryView> pageDone(TaskDonePage page) {
        return taskService.pageDone(page);
    }

    @PostMapping("{id}/pass")
    @Operation(summary = "通过")
    @OperationLog(module = "工作流", value = "任务通过")
    public boolean pass(@PathVariable Long id, @Valid @RequestBody ApproveCommand command) {
        return taskService.pass(id, command);
    }

    @PostMapping("{id}/reject")
    @Operation(summary = "退回上一节点")
    @OperationLog(module = "工作流", value = "任务退回")
    public boolean reject(@PathVariable Long id, @Valid @RequestBody RejectCommand command) {
        return taskService.reject(id, command);
    }

    @PostMapping("{id}/transfer")
    @Operation(summary = "转办")
    @OperationLog(module = "工作流", value = "任务转办")
    public boolean transfer(@PathVariable Long id, @Valid @RequestBody TransferCommand command) {
        return taskService.transfer(id, command);
    }

    @PostMapping("{id}/depute")
    @Operation(summary = "委派")
    @OperationLog(module = "工作流", value = "任务委派")
    public boolean depute(@PathVariable Long id, @Valid @RequestBody TransferCommand command) {
        return taskService.depute(id, command);
    }

    @PostMapping("{id}/signature/add")
    @Operation(summary = "加签")
    @OperationLog(module = "工作流", value = "任务加签")
    public boolean addSignature(@PathVariable Long id, @Valid @RequestBody SignatureCommand command) {
        return taskService.addSignature(id, command);
    }

    @PostMapping("{id}/signature/reduction")
    @Operation(summary = "减签")
    @OperationLog(module = "工作流", value = "任务减签")
    public boolean reductionSignature(@PathVariable Long id, @Valid @RequestBody SignatureCommand command) {
        return taskService.reductionSignature(id, command);
    }

    @SaCheckPermission("workflow_task_admin")
    @PutMapping("{id}/handler")
    @Operation(summary = "改办理人（管理端）")
    @OperationLog(module = "工作流", value = "修改任务办理人")
    public boolean updateHandler(@PathVariable Long id, @RequestBody List<String> targetUsernames) {
        return taskService.updateHandler(id, targetUsernames);
    }

    @SaCheckPermission("workflow_task_admin")
    @PostMapping("{id}/skip")
    @Operation(summary = "任意跳转到指定节点（管理端）")
    @OperationLog(module = "工作流", value = "任务任意跳转")
    public boolean passAtWill(
            @PathVariable Long id,
            @RequestParam String nodeCode,
            @RequestParam(required = false) String message,
            @RequestBody(required = false) Map<String, Object> variable) {
        return taskService.passAtWill(id, nodeCode, message, variable);
    }
}
