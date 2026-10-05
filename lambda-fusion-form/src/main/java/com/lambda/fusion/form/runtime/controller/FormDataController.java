package com.lambda.fusion.form.runtime.controller;

import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.form.runtime.interceptor.FormPermission;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import com.lambda.fusion.form.runtime.model.FormDataPageView;
import com.lambda.fusion.form.runtime.model.FormSchemaView;
import com.lambda.fusion.form.runtime.service.FormRuntimeService;
import com.lambda.fusion.form.runtime.support.FormAction;
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
 * 动态表单数据 API（通用运行时出口）
 *
 * <p>FormPermission 同时驱动请求期权限校验与动态接口目录，不依赖编译期生成具体 formKey。
 *
 * @author Zx
 */
@RestController
@RequestMapping("/v1/form")
@Tag(name = "动态表单运行时")
@RequiredArgsConstructor
public class FormDataController {

    private final FormRuntimeService formRuntimeService;

    @GetMapping("{formKey}/schema")
    @FormPermission(FormAction.PAGE)
    @Operation(summary = "渲染元数据（含当前用户按钮权限）")
    public FormSchemaView schema(@PathVariable String formKey) {
        return formRuntimeService.schema(formKey);
    }

    @PostMapping("{formKey}/page")
    @FormPermission(FormAction.PAGE)
    @Operation(summary = "分页查询（AST 条件 + 白名单排序）")
    public FormDataPageView page(@PathVariable String formKey, @Valid @RequestBody FormDataPageQuery query) {
        return formRuntimeService.page(formKey, query);
    }

    @GetMapping("{formKey}/tree")
    @FormPermission(FormAction.PAGE)
    @Operation(summary = "TREE 类型树节点（按父节点取子集）")
    public List<Map<String, Object>> tree(
            @PathVariable String formKey, @RequestParam(required = false) String parentValue) {
        return formRuntimeService.tree(formKey, parentValue);
    }

    @GetMapping("{formKey}/{id}")
    @FormPermission(FormAction.PAGE)
    @Operation(summary = "详情")
    public Map<String, Object> detail(@PathVariable String formKey, @PathVariable String id) {
        return formRuntimeService.getById(formKey, id);
    }

    @GetMapping("{formKey}/{id}/subs/{subFormKey}")
    @FormPermission(FormAction.PAGE)
    @Operation(summary = "台账式子表分页（外键由服务端绑定主行 id）")
    public PageView<Map<String, Object>> subRowsPage(
            @PathVariable String formKey,
            @PathVariable String id,
            @PathVariable String subFormKey,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return formRuntimeService.pageSubRows(formKey, id, subFormKey, pageNum, pageSize);
    }

    @PostMapping("{formKey}/{id}/subs/{subFormKey}")
    @FormPermission(FormAction.ADD)
    @Operation(summary = "台账式子表新增（外键由服务端绑定，并校验子表单新增权限）")
    @OperationLog(module = "动态表单", value = "新增子表数据")
    public void subRowCreate(
            @PathVariable String formKey,
            @PathVariable String id,
            @PathVariable String subFormKey,
            @RequestBody Map<String, Object> data) {
        formRuntimeService.createSubRow(formKey, id, subFormKey, data);
    }

    @PostMapping("{formKey}")
    @FormPermission(FormAction.ADD)
    @Operation(summary = "新增")
    @OperationLog(module = "动态表单", value = "新增表单数据")
    public String create(@PathVariable String formKey, @RequestBody Map<String, Object> data) {
        return formRuntimeService.create(formKey, data);
    }

    @PutMapping("{formKey}/{id}")
    @FormPermission(FormAction.EDIT)
    @Operation(summary = "编辑")
    @OperationLog(module = "动态表单", value = "编辑表单数据")
    public void update(@PathVariable String formKey, @PathVariable String id, @RequestBody Map<String, Object> data) {
        formRuntimeService.update(formKey, id, data);
    }

    @DeleteMapping("{formKey}/{id}")
    @FormPermission(FormAction.DELETE)
    @Operation(summary = "删除（按级联策略）")
    @OperationLog(module = "动态表单", value = "删除表单数据")
    public void remove(@PathVariable String formKey, @PathVariable String id) {
        formRuntimeService.delete(formKey, id);
    }

    @PostMapping("{formKey}/export")
    @FormPermission(FormAction.EXPORT)
    @Operation(summary = "导出当前筛选全集为 CSV（UTF-8 BOM；行数上限由 max-export-rows 控制）")
    @OperationLog(module = "动态表单", value = "导出表单数据")
    public void export(
            @PathVariable String formKey,
            @RequestBody FormDataPageQuery query,
            jakarta.servlet.http.HttpServletResponse response)
            throws java.io.IOException {
        response.setContentType("text/csv; charset=UTF-8");
        String filename = java.net.URLEncoder.encode(
                        formKey + "-"
                                + java.time.LocalDateTime.now()
                                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                                + ".csv",
                        java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
        formRuntimeService.export(formKey, query, response.getWriter());
    }
}
