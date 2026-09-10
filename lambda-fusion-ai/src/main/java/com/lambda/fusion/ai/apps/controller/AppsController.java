package com.lambda.fusion.ai.apps.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.ai.apps.model.AppPageQuery;
import com.lambda.fusion.ai.apps.model.CreateApp;
import com.lambda.fusion.ai.apps.model.UpdateApp;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.apps.service.AppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

@Tag(name = "智能应用管理")
@RestController
@RequestMapping("/v1/ai/apps")
@RequiredArgsConstructor
public class AppsController {

    private final AppService appService;

    @SaCheckPermission(value = "ai:app:page")
    @Operation(summary = "分页查询智能应用")
    @GetMapping("/page")
    public Page<AppEntity> page(@Valid AppPageQuery query) {
        return appService.page(query);
    }

    @SaCheckPermission(value = "ai:app:get")
    @Operation(summary = "查询智能应用详情")
    @GetMapping("/{id}")
    public AppEntity get(@Parameter(description = "应用ID", required = true) @PathVariable String id) {
        return appService.get(id);
    }

    @SaCheckPermission(value = "ai:app:add")
    @OperationLog(module = "AI", value = "新增智能应用")
    @Operation(summary = "新增智能应用")
    @PostMapping
    public AppEntity create(@RequestBody @Valid CreateApp dto) {
        return appService.create(dto);
    }

    @SaCheckPermission(value = "ai:app:update")
    @OperationLog(module = "AI", value = "更新智能应用")
    @Operation(summary = "更新智能应用")
    @PutMapping("/{id}")
    public void update(
            @Parameter(description = "应用ID", required = true) @PathVariable String id,
            @RequestBody @Valid UpdateApp dto) {
        appService.update(id, dto);
    }

    @SaCheckPermission(value = "ai:app:delete")
    @OperationLog(module = "AI", value = "删除智能应用")
    @Operation(summary = "删除智能应用")
    @DeleteMapping("/{id}")
    public void delete(@Parameter(description = "应用ID", required = true) @PathVariable String id) {
        appService.delete(id);
    }
}
