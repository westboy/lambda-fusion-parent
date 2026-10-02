package com.lambda.fusion.form.runtime.controller;

import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.form.runtime.interceptor.FormPermission;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
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
    public PageView<Map<String, Object>> page(
            @PathVariable String formKey, @Valid @RequestBody FormDataPageQuery query) {
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
}
