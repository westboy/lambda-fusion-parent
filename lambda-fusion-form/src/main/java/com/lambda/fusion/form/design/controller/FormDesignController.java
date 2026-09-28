package com.lambda.fusion.form.design.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.form.design.model.FormDefPage;
import com.lambda.fusion.form.design.model.FormDefSaveCommand;
import com.lambda.fusion.form.design.model.FormDefView;
import com.lambda.fusion.form.design.model.FormVersionView;
import com.lambda.fusion.form.design.model.TableColumnView;
import com.lambda.fusion.form.design.service.FormDesignService;
import com.lambda.fusion.form.design.service.FormPublishService;
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
