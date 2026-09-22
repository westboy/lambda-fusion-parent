package com.lambda.fusion.workflow.instance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.workflow.instance.model.ChartView;
import com.lambda.fusion.workflow.instance.model.HistoryView;
import com.lambda.fusion.workflow.instance.model.InstancePage;
import com.lambda.fusion.workflow.instance.model.InstanceView;
import com.lambda.fusion.workflow.instance.model.StartCommand;
import com.lambda.fusion.workflow.instance.service.InstanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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
 * 流程实例 API：发起、我的申请、详情/历史/流程图、撤回/终止/挂起/激活
 *
 * <p>查询与发起类接口仅要求登录（业务系统任何用户都可发起与查看自己的单据）；
 * 管理端全量分页与终止/挂起/激活要求 admin 权限码。
 *
 * @author jin
 */
@RestController
@RequestMapping("/workflow/instance")
@Tag(name = "流程实例")
@RequiredArgsConstructor
public class InstanceController {

    private final InstanceService instanceService;

    @SaCheckPermission("workflow_instance_add")
    @PostMapping("start")
    @Operation(summary = "发起流程")
    @OperationLog(module = "工作流", value = "发起流程实例")
    public InstanceView start(@Valid @RequestBody StartCommand command) {
        return instanceService.start(command);
    }

    @SaCheckPermission("workflow_instance_list")
    @GetMapping("page")
    @Operation(summary = "管理端实例分页（全量）")
    public PageView<InstanceView> page(InstancePage page) {
        return instanceService.page(page);
    }

    @GetMapping("mine")
    @Operation(summary = "我发起的实例分页")
    public PageView<InstanceView> pageMine(InstancePage page) {
        return instanceService.pageMine(page);
    }

    @GetMapping("business/{businessId}")
    @Operation(summary = "按业务单据查实例列表")
    public List<InstanceView> listByBusiness(@PathVariable String businessId) {
        return instanceService.listByBusiness(businessId);
    }

    @GetMapping("{id}")
    @Operation(summary = "实例详情")
    public InstanceView detail(@PathVariable Long id) {
        return instanceService.detail(id);
    }

    @GetMapping("{id}/history")
    @Operation(summary = "审批历史")
    public List<HistoryView> history(@PathVariable Long id) {
        return instanceService.history(id);
    }

    @GetMapping("{id}/chart")
    @Operation(summary = "流程图数据（定义结构 + 当前状态）")
    public ChartView chart(@PathVariable Long id) {
        return instanceService.chart(id);
    }

    @PutMapping("{id}/revoke")
    @Operation(summary = "发起人撤回（引擎校验发起人身份）")
    @OperationLog(module = "工作流", value = "撤回流程实例")
    public boolean revoke(@PathVariable Long id, @RequestParam(required = false) String message) {
        return instanceService.revoke(id, message);
    }

    @SaCheckPermission("workflow_instance_admin")
    @PutMapping("{id}/terminate")
    @Operation(summary = "终止实例")
    @OperationLog(module = "工作流", value = "终止流程实例")
    public boolean terminate(@PathVariable Long id, @RequestParam(required = false) String message) {
        return instanceService.terminate(id, message);
    }

    @SaCheckPermission("workflow_instance_admin")
    @PutMapping("{id}/active")
    @Operation(summary = "激活实例")
    @OperationLog(module = "工作流", value = "激活流程实例")
    public boolean active(@PathVariable Long id) {
        return instanceService.active(id);
    }

    @SaCheckPermission("workflow_instance_admin")
    @PutMapping("{id}/unactive")
    @Operation(summary = "挂起实例")
    @OperationLog(module = "工作流", value = "挂起流程实例")
    public boolean unActive(@PathVariable Long id) {
        return instanceService.unActive(id);
    }
}
