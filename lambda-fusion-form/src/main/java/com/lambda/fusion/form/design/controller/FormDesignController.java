package com.lambda.fusion.form.design.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.form.design.model.FormDefPage;
import com.lambda.fusion.form.design.model.FormDefSaveCommand;
import com.lambda.fusion.form.design.model.FormDefView;
import com.lambda.fusion.form.design.model.FormPreviewDataCommand;
import com.lambda.fusion.form.design.model.FormPreviewSource;
import com.lambda.fusion.form.design.model.FormRouteCommand;
import com.lambda.fusion.form.design.model.FormRouteInfo;
import com.lambda.fusion.form.design.model.FormVersionView;
import com.lambda.fusion.form.design.model.TableColumnView;
import com.lambda.fusion.form.design.service.FormDesignService;
import com.lambda.fusion.form.design.service.FormPreviewService;
import com.lambda.fusion.form.design.service.FormPublishService;
import com.lambda.fusion.form.design.service.FormRouteService;
import com.lambda.fusion.form.runtime.model.FormSchemaView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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
 * 动态表单设计 API（草稿 CRUD / 选表导列 / 发布版本）
 *
 * @author Zx
 */
@RestController
@RequestMapping("/v1/form-design")
@Tag(name = "动态表单设计")
@RequiredArgsConstructor
public class FormDesignController {

    private final FormDesignService formDesignService;
    private final FormPublishService formPublishService;
    private final FormPreviewService formPreviewService;
    private final FormRouteService formRouteService;

    @SaCheckPermission("form:design:page")
    @PostMapping("routes")
    @Operation(summary = "查询表单实际路由挂载状态")
    public Map<String, FormRouteInfo> routes(@RequestBody List<String> ids) {
        return formRouteService.states(ids);
    }

    @SaCheckPermission("form:design:route")
    @GetMapping("{id}/route")
    @Operation(summary = "读取路由挂载配置")
    public FormRouteInfo route(@PathVariable String id) {
        return formRouteService.detail(id);
    }

    @SaCheckPermission("form:design:route")
    @PutMapping("{id}/route")
    @Operation(summary = "独立发布路由，不发布版本或自动授予角色权限")
    @OperationLog(module = "动态表单", value = "发布路由")
    public FormRouteInfo saveRoute(@PathVariable String id, @Valid @RequestBody FormRouteCommand command) {
        return formRouteService.save(id, command);
    }

    @SaCheckPermission("form:design:route")
    @DeleteMapping("{id}/route")
    @Operation(summary = "撤销本表单管理的路由与资源，保留生效版本")
    @OperationLog(module = "动态表单", value = "撤销路由")
    public void removeRoute(@PathVariable String id) {
        formRouteService.remove(id);
    }

    @SaCheckPermission("form:design:preview")
    @PostMapping("preview")
    @Operation(summary = "预览未保存设计，不产生业务写入")
    public FormSchemaView preview(@Valid @RequestBody FormPreviewSource source) {
        return formPreviewService.preview(source);
    }

    @SaCheckPermission("form:design:preview")
    @PostMapping("preview/data/page")
    @Operation(summary = "按预览模型查询真实业务数据")
    public PageView<Map<String, Object>> previewPage(@Valid @RequestBody FormPreviewDataCommand command) {
        return formPreviewService.page(command.source(), command.query());
    }

    @SaCheckPermission("form:design:preview")
    @PostMapping("preview/data/tree")
    @Operation(summary = "按预览模型读取树节点（树表/左树预览数据源）")
    public List<Map<String, Object>> previewTree(
            @Valid @RequestBody FormPreviewSource source, @RequestParam(required = false) String parentValue) {
        return formPreviewService.tree(source, parentValue);
    }

    @SaCheckPermission("form:design:preview")
    @PostMapping("preview/data/{recordId}/detail")
    @Operation(summary = "按预览模型读取真实业务记录")
    public Map<String, Object> previewDetail(
            @PathVariable String recordId, @Valid @RequestBody FormPreviewSource source) {
        return formPreviewService.detail(source, recordId);
    }

    @SaCheckPermission("form:design:preview")
    @PostMapping("preview/data")
    @Operation(summary = "按预览模型新增真实业务记录")
    @OperationLog(module = "动态表单", value = "预览新增业务数据")
    public String previewCreate(@Valid @RequestBody FormPreviewDataCommand command) {
        return formPreviewService.create(command.source(), command.data());
    }

    @SaCheckPermission("form:design:preview")
    @PutMapping("preview/data/{recordId}")
    @Operation(summary = "按预览模型修改真实业务记录")
    @OperationLog(module = "动态表单", value = "预览修改业务数据")
    public void previewUpdate(@PathVariable String recordId, @Valid @RequestBody FormPreviewDataCommand command) {
        formPreviewService.update(command.source(), recordId, command.data());
    }

    @SaCheckPermission("form:design:preview")
    @DeleteMapping("preview/data/{recordId}")
    @Operation(summary = "按预览模型删除真实业务记录")
    @OperationLog(module = "动态表单", value = "预览删除业务数据")
    public void previewDelete(@PathVariable String recordId, @Valid @RequestBody FormPreviewSource source) {
        formPreviewService.delete(source, recordId);
    }

    @SaCheckPermission("form:design:preview")
    @GetMapping("{id}/preview")
    @Operation(summary = "预览已保存草稿")
    public FormSchemaView previewDraft(@PathVariable String id) {
        return formPreviewService.previewDraft(id);
    }

    @SaCheckPermission("form:design:preview")
    @GetMapping("{id}/versions/{version}/preview")
    @Operation(summary = "预览指定发布版本")
    public FormSchemaView previewVersion(@PathVariable String id, @PathVariable long version) {
        return formPreviewService.previewVersion(id, version);
    }

    @SaCheckPermission("form:design:publish")
    @PostMapping("{id}/enable")
    @OperationLog(module = "动态表单", value = "启用表单")
    public void enable(@PathVariable String id) {
        formPublishService.enable(id);
    }

    @SaCheckPermission("form:design:page")
    @GetMapping("page")
    @Operation(summary = "表单分页")
    public PageView<FormDefView> page(FormDefPage page) {
        return formDesignService.page(page);
    }

    @SaCheckPermission("form:design:detail")
    @GetMapping("{id}")
    @Operation(summary = "草稿详情（def + fields + relations）")
    public FormDefView detail(@PathVariable String id) {
        return formDesignService.detail(id);
    }

    @SaCheckPermission("form:design:add")
    @PostMapping
    @Operation(summary = "新建草稿")
    @OperationLog(module = "动态表单", value = "新建表单草稿")
    public String save(@Valid @RequestBody FormDefSaveCommand command) {
        return formDesignService.save(command);
    }

    @SaCheckPermission("form:design:edit")
    @PutMapping("{id}")
    @Operation(summary = "保存草稿（乐观锁）")
    @OperationLog(module = "动态表单", value = "保存表单草稿")
    public void update(@PathVariable String id, @Valid @RequestBody FormDefSaveCommand command) {
        formDesignService.update(id, command);
    }

    @SaCheckPermission("form:design:delete")
    @DeleteMapping("{id}")
    @Operation(summary = "删除草稿（已发布须先停用）")
    @OperationLog(module = "动态表单", value = "删除表单草稿")
    public void remove(@PathVariable String id) {
        formDesignService.remove(id);
    }

    @SaCheckPermission("form:design:table")
    @GetMapping("tables")
    @Operation(summary = "可映射物理表清单")
    public List<String> tables() {
        return formDesignService.listTables();
    }

    @SaCheckPermission("form:design:table")
    @GetMapping("tables/{tableName}/columns")
    @Operation(summary = "列元数据（选表导列一键生成字段草稿）")
    public List<TableColumnView> columns(@PathVariable String tableName) {
        return formDesignService.listColumns(tableName);
    }

    @SaCheckPermission("form:design:publish")
    @PostMapping("{id}/publish")
    @Operation(summary = "发布（校验 → 快照 → 拨指针）")
    @OperationLog(module = "动态表单", value = "发布表单")
    public void publish(@PathVariable String id) {
        formPublishService.publish(id);
    }

    @SaCheckPermission("form:design:publish")
    @PostMapping("{id}/disable")
    @Operation(summary = "停用")
    @OperationLog(module = "动态表单", value = "停用表单")
    public void disable(@PathVariable String id) {
        formPublishService.disable(id);
    }

    @SaCheckPermission("form:design:publish")
    @PostMapping("{id}/rollback/{version}")
    @Operation(summary = "回滚到历史快照")
    @OperationLog(module = "动态表单", value = "回滚表单版本")
    public void rollback(@PathVariable String id, @PathVariable Long version) {
        formPublishService.rollback(id, version);
    }

    @SaCheckPermission("form:design:detail")
    @GetMapping("{id}/versions")
    @Operation(summary = "版本历史")
    public List<FormVersionView> versions(@PathVariable String id) {
        return formPublishService.versions(id);
    }
}
