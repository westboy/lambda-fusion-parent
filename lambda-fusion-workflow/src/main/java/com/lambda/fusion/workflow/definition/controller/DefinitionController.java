package com.lambda.fusion.workflow.definition.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.workflow.definition.model.DefinitionPage;
import com.lambda.fusion.workflow.definition.model.DefinitionSaveCommand;
import com.lambda.fusion.workflow.definition.model.DefinitionView;
import com.lambda.fusion.workflow.definition.service.DefinitionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.warm.flow.core.dto.FlowCombine;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流程定义管理 API（管理端）
 *
 * @author jin
 */
@RestController
@RequestMapping("/workflow/definition")
@Tag(name = "流程定义管理")
@RequiredArgsConstructor
public class DefinitionController {

    private final DefinitionService definitionService;

    @SaCheckPermission("workflow_definition_list")
    @GetMapping
    @Operation(summary = "定义分页")
    public PageView<DefinitionView> page(DefinitionPage page) {
        return definitionService.page(page);
    }

    @SaCheckPermission("workflow_definition_list")
    @GetMapping("{id}")
    @Operation(summary = "定义详情（含节点与出边，设计器加载用）")
    public FlowCombine detail(@PathVariable Long id) {
        return definitionService.detail(id);
    }

    @SaCheckPermission("workflow_definition_add")
    @PostMapping
    @Operation(summary = "保存设计器 JSON（新增或升版）")
    @OperationLog(module = "工作流", value = "保存流程定义")
    public DefinitionView save(@Valid @RequestBody DefinitionSaveCommand command) {
        return definitionService.save(command);
    }

    @SaCheckPermission("workflow_definition_publish")
    @PutMapping("{id}/publish")
    @Operation(summary = "发布定义")
    @OperationLog(module = "工作流", value = "发布流程定义")
    public boolean publish(@PathVariable Long id) {
        return definitionService.publish(id);
    }

    @SaCheckPermission("workflow_definition_publish")
    @PutMapping("{id}/unpublish")
    @Operation(summary = "失效定义")
    @OperationLog(module = "工作流", value = "失效流程定义")
    public boolean unPublish(@PathVariable Long id) {
        return definitionService.unPublish(id);
    }

    @SaCheckPermission("workflow_definition_add")
    @PostMapping("{id}/copy")
    @Operation(summary = "复制定义")
    @OperationLog(module = "工作流", value = "复制流程定义")
    public boolean copy(@PathVariable Long id) {
        return definitionService.copy(id);
    }

    @SaCheckPermission("workflow_definition_delete")
    @DeleteMapping("{id}")
    @Operation(summary = "删除定义（已发布须先失效）")
    @OperationLog(module = "工作流", value = "删除流程定义")
    public void remove(@PathVariable Long id) {
        definitionService.remove(id);
    }

    @SaCheckPermission("workflow_definition_export")
    @GetMapping("{id}/export")
    @Operation(summary = "导出定义 JSON")
    public String exportJson(@PathVariable Long id) {
        return definitionService.exportJson(id);
    }
}
