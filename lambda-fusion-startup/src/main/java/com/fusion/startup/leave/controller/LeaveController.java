package com.fusion.startup.leave.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fusion.startup.leave.model.LeaveEntity;
import com.fusion.startup.leave.model.LeavePage;
import com.fusion.startup.leave.model.LeaveSaveCommand;
import com.fusion.startup.leave.service.LeaveService;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.core.pagination.PageView;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 请假单 API（工作流演示业务）
 *
 * <p>审批操作不在此出口——办理人走 workflow 模块通用审批面（/workflow/task/*），
 * 本控制器只承载业务单据的增删改查与提交（§10.3：不直连 flow_*，不重复审批出口）。
 *
 * @author jin
 */
@RestController
@RequestMapping("/leave/demo")
@Tag(name = "请假演示")
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class LeaveController {

    private final LeaveService leaveService;

    @SaCheckPermission("leave_demo_list")
    @GetMapping("page")
    @Operation(summary = "请假单分页")
    public PageView<LeaveEntity> page(LeavePage page) {
        return leaveService.page(page);
    }

    @SaCheckPermission("leave_demo_list")
    @GetMapping("{id}")
    @Operation(summary = "请假单详情")
    public LeaveEntity detail(@PathVariable String id) {
        return leaveService.detail(id);
    }

    @SaCheckPermission("leave_demo_add")
    @PostMapping
    @Operation(summary = "新增请假单（草稿）")
    @OperationLog(module = "请假演示", value = "新增请假单")
    public void save(@Valid @RequestBody LeaveSaveCommand command) {
        leaveService.save(command);
    }

    @SaCheckPermission("leave_demo_add")
    @PutMapping("{id}")
    @Operation(summary = "修改请假单（仅草稿）")
    @OperationLog(module = "请假演示", value = "修改请假单")
    public void update(@PathVariable String id, @Valid @RequestBody LeaveSaveCommand command) {
        leaveService.update(id, command);
    }

    @SaCheckPermission("leave_demo_submit")
    @PostMapping("{id}/submit")
    @Operation(summary = "提交审批（发起 leave_demo 流程）")
    @OperationLog(module = "请假演示", value = "提交请假审批")
    public void submit(@PathVariable String id) {
        leaveService.submit(id);
    }

    @SaCheckPermission("leave_demo_delete")
    @DeleteMapping("{id}")
    @Operation(summary = "删除请假单（仅草稿）")
    @OperationLog(module = "请假演示", value = "删除请假单")
    public void remove(@PathVariable String id) {
        leaveService.remove(id);
    }
}
