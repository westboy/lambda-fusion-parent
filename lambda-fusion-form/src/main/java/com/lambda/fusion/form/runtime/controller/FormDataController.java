package com.lambda.fusion.form.runtime.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import com.lambda.fusion.form.runtime.model.FormSchemaView;
import com.lambda.fusion.form.runtime.service.FormRuntimeService;
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
 * <p>权限码 form:{formKey}:{action} 运行时拼接，故用 StpUtil 编程式校验替代注解。
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
    @Operation(summary = "渲染元数据（含当前用户按钮权限）")
    public FormSchemaView schema(@PathVariable String formKey) {
        check(formKey, "page");
        return formRuntimeService.schema(formKey);
    }

    @PostMapping("{formKey}/page")
    @Operation(summary = "分页查询（AST 条件 + 白名单排序）")
    public PageView<Map<String, Object>> page(
            @PathVariable String formKey, @Valid @RequestBody FormDataPageQuery query) {
        check(formKey, "page");
        return formRuntimeService.page(formKey, query);
    }

    @GetMapping("{formKey}/tree")
    @Operation(summary = "TREE 类型树节点（按父节点取子集）")
    public List<Map<String, Object>> tree(
            @PathVariable String formKey, @RequestParam(required = false) String parentValue) {
        check(formKey, "page");
        return formRuntimeService.tree(formKey, parentValue);
    }

    @GetMapping("{formKey}/{id}")
    @Operation(summary = "详情")
    public Map<String, Object> detail(@PathVariable String formKey, @PathVariable String id) {
        check(formKey, "page");
        return formRuntimeService.getById(formKey, id);
    }

    @PostMapping("{formKey}")
    @Operation(summary = "新增")
    @OperationLog(module = "动态表单", value = "新增表单数据")
    public String create(@PathVariable String formKey, @RequestBody Map<String, Object> data) {
        check(formKey, "add");
        return formRuntimeService.create(formKey, data);
    }

    @PutMapping("{formKey}/{id}")
    @Operation(summary = "编辑")
    @OperationLog(module = "动态表单", value = "编辑表单数据")
    public void update(@PathVariable String formKey, @PathVariable String id, @RequestBody Map<String, Object> data) {
        check(formKey, "edit");
        formRuntimeService.update(formKey, id, data);
    }

    @DeleteMapping("{formKey}/{id}")
    @Operation(summary = "删除（按级联策略）")
    @OperationLog(module = "动态表单", value = "删除表单数据")
    public void remove(@PathVariable String formKey, @PathVariable String id) {
        check(formKey, "delete");
        formRuntimeService.delete(formKey, id);
    }

    private void check(String formKey, String action) {
        StpUtil.checkPermission(String.format(FormConstants.RUNTIME_PERMISSION_TEMPLATE, formKey, action));
    }
}
