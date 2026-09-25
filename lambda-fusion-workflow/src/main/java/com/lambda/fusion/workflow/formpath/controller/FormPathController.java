package com.lambda.fusion.workflow.formpath.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.workflow.formpath.model.FormPathEntity;
import com.lambda.fusion.workflow.formpath.model.FormPathPage;
import com.lambda.fusion.workflow.formpath.model.FormPathSaveCommand;
import com.lambda.fusion.workflow.formpath.service.FormPathManagerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流程自定义表单注册表 API（后台维护）
 *
 * @author jin
 */
@RestController
@RequestMapping("/workflow/form-path")
@Tag(name = "流程自定义表单")
@RequiredArgsConstructor
public class FormPathController {

    private final FormPathManagerService formPathManagerService;

    @SaCheckPermission("workflow_form_path_list")
    @GetMapping("page")
    @Operation(summary = "表单注册表分页")
    public PageView<FormPathEntity> page(FormPathPage page) {
        return formPathManagerService.page(page);
    }

    @GetMapping("list-enabled")
    @Operation(summary = "启用表单全量（设计器下拉数据源同源）")
    public List<FormPathEntity> listEnabled() {
        return formPathManagerService.listEnabled();
    }

    @SaCheckPermission("workflow_form_path_add")
    @PostMapping
    @Operation(summary = "新增表单（路径唯一）")
    @OperationLog(module = "工作流", value = "新增自定义表单")
    public void save(@Valid @RequestBody FormPathSaveCommand command) {
        formPathManagerService.save(command);
    }

    @SaCheckPermission("workflow_form_path_update")
    @PutMapping("{id}")
    @Operation(summary = "修改表单（路径唯一）")
    @OperationLog(module = "工作流", value = "修改自定义表单")
    public void update(@PathVariable String id, @Valid @RequestBody FormPathSaveCommand command) {
        formPathManagerService.update(id, command);
    }

    @SaCheckPermission("workflow_form_path_update")
    @PutMapping("{id}/toggle")
    @Operation(summary = "启停切换")
    @OperationLog(module = "工作流", value = "启停自定义表单")
    public void toggle(@PathVariable String id, @RequestParam boolean enabled) {
        formPathManagerService.toggle(id, enabled);
    }

    @SaCheckPermission("workflow_form_path_delete")
    @DeleteMapping("{id}")
    @Operation(summary = "删除表单")
    @OperationLog(module = "工作流", value = "删除自定义表单")
    public void remove(@PathVariable String id) {
        formPathManagerService.remove(id);
    }
}
