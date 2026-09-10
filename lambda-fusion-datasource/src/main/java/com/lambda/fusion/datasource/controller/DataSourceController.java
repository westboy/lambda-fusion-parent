package com.lambda.fusion.datasource.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.core.FusionConstants;
import com.lambda.fusion.datasource.model.DataSourceEntity;
import com.lambda.fusion.datasource.model.QueryDataSource;
import com.lambda.fusion.datasource.model.UpsertDataSource;
import com.lambda.fusion.datasource.service.DataSourceManageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@SaCheckRole(
        value = {FusionConstants.ROLE_ADMIN, FusionConstants.ROLE_SYSTEM, FusionConstants.ROLE_DEV},
        mode = SaMode.OR)
@Tag(name = "数据源管理")
@RestController
@RequestMapping("/datasource")
public class DataSourceController {

    private final DataSourceManageService dataSourceManageService;

    public DataSourceController(DataSourceManageService dataSourceManageService) {
        this.dataSourceManageService = dataSourceManageService;
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询数据源", description = "分页查询数据源列表")
    public Page<DataSourceEntity> page(@Valid QueryDataSource query) {
        return dataSourceManageService.page(query);
    }

    @GetMapping("/list")
    @Operation(summary = "查询数据源列表", description = "查询数据源列表")
    public List<DataSourceEntity> list() {
        return dataSourceManageService.listAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询数据源详情", description = "按ID查询记录")
    public DataSourceEntity get(@Parameter(description = "数据源编号", required = true) @PathVariable String id) {
        return dataSourceManageService.getById(id);
    }

    @OperationLog(module = "数据源管理", value = "新增数据源")
    @PostMapping
    @Operation(summary = "新增数据源", description = "新增记录")
    public void save(@RequestBody @Valid UpsertDataSource input) {
        dataSourceManageService.save(input);
    }

    @OperationLog(module = "数据源管理", value = "更新数据源")
    @PutMapping("/{id}")
    @Operation(summary = "更新数据源", description = "更新记录")
    public void update(
            @Parameter(description = "数据源编号", required = true) @PathVariable String id,
            @RequestBody @Valid UpsertDataSource input) {
        dataSourceManageService.update(id, input);
    }

    @OperationLog(module = "数据源管理", value = "删除数据源")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除数据源", description = "删除记录")
    public void delete(@Parameter(description = "数据源编号", required = true) @PathVariable String id) {
        dataSourceManageService.delete(id);
    }

    @GetMapping("/{id}/test")
    @Operation(summary = "测试数据源连接", description = "测试对应连接")
    public boolean test(@Parameter(description = "数据源编号", required = true) @PathVariable String id) {
        return dataSourceManageService.test(id);
    }

    @PutMapping("/{id}/enable")
    @Operation(summary = "启用数据源", description = "更新 status=1 并同步到运行时动态数据源")
    public void enable(@Parameter(description = "数据源编号", required = true) @PathVariable String id) {
        dataSourceManageService.enable(id);
    }

    @PutMapping("/{id}/disable")
    @Operation(summary = "禁用数据源", description = "更新 status=0 并从运行时动态数据源移除")
    public void disable(@Parameter(description = "数据源编号", required = true) @PathVariable String id) {
        dataSourceManageService.disable(id);
    }
}
