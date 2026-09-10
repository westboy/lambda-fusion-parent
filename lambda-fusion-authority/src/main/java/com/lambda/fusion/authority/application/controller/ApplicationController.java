package com.lambda.fusion.authority.application.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.authority.application.model.ApplicationEntity;
import com.lambda.fusion.authority.application.model.ApplicationQuery;
import com.lambda.fusion.authority.application.model.UpsertApplication;
import com.lambda.fusion.authority.application.service.ApplicationService;
import com.lambda.fusion.authority.exception.AuthorityBusinessException;
import com.lambda.fusion.authority.resource.mapper.ResourceMapper;
import com.lambda.fusion.authority.resource.model.entity.ResourceEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * 应用管理API
 *
 * <p>应用作为资源（菜单）的分类维度，一个应用对应一个 Spring application.name，
 * 用于资源管理按应用归类菜单。标记为独立前端（STANDALONE）的应用，其菜单不并入
 * 主控台导航，由对应前端按 application 参数单独获取（见 /auth/menus）。
 */
@RestController
@RequestMapping({"/authority/applications"})
@Tag(name = "应用管理")
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationService applicationService;
    private final ResourceMapper resourceMapper;

    @SaCheckPermission(value = "authority:application:page")
    @GetMapping({"/page", "/page/{number:\\d+}", "/page/{number:\\d+}/size/{size:\\d+}"})
    @Operation(summary = "分页查询应用列表", description = "分页查询应用列表")
    public Page<ApplicationEntity> page(
            @PathVariable(required = false) Integer number,
            @PathVariable(required = false) Integer size,
            @Valid ApplicationQuery applicationQuery) {
        if (number != null) {
            applicationQuery.setPageNum(number);
        }
        if (size != null) {
            applicationQuery.setPageSize(size);
        }
        return applicationService.page(applicationQuery.getPage(), applicationQuery.getLambdaQueryWrapper());
    }

    @SaCheckPermission(value = "authority:application:get")
    @GetMapping("/{id}")
    @Operation(summary = "根据编号查询应用信息", description = "根据id查询应用信息")
    public ApplicationEntity get(@Parameter(description = "应用ID", required = true) @PathVariable String id) {
        return applicationService.getById(id);
    }

    @SaCheckPermission(value = "authority:application:add")
    @OperationLog(module = "应用管理", value = "新增应用信息")
    @PostMapping
    @Operation(summary = "新增应用信息", description = "新增应用信息")
    public void save(@Parameter(description = "应用信息", required = true) @Valid @RequestBody UpsertApplication source) {
        ensureNameUnique(source.getName(), null);
        ensureSpringNameUnique(source.getSpringApplicationName(), null);
        ApplicationEntity entity = source.toEntity();
        if (entity.getEnabled() == null) {
            entity.setEnabled(Boolean.TRUE);
        }
        entity.setSecret(IdUtil.fastUUID());
        applicationService.save(entity);
    }

    @SaCheckPermission(value = "authority:application:update")
    @OperationLog(module = "应用管理", value = "更新应用信息")
    @PutMapping("/{id}")
    @Operation(summary = "更新应用信息", description = "更新应用信息")
    public void update(
            @Parameter(description = "应用ID", required = true) @PathVariable String id,
            @Parameter(description = "应用信息", required = true) @Valid @RequestBody UpsertApplication source) {
        ApplicationEntity original = applicationService.getById(id);
        if (original == null) {
            throw AuthorityBusinessException.applicationNotFound(id);
        }
        ensureNameUnique(source.getName(), id);
        ensureSpringNameUnique(source.getSpringApplicationName(), id);
        ApplicationEntity entity = source.toEntity();
        entity.setId(id);
        // 密钥不在更新接口暴露，保留原密钥
        entity.setSecret(original.getSecret());
        applicationService.updateById(entity);
    }

    @SaCheckPermission(value = "authority:application:delete")
    @OperationLog(module = "应用管理", value = "删除应用信息")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除应用信息", description = "删除应用信息，应用下存在资源时不允许删除")
    public void delete(@Parameter(description = "应用ID", required = true) @PathVariable String id) {
        ApplicationEntity original = applicationService.getById(id);
        if (original == null) {
            throw AuthorityBusinessException.applicationNotFound(id);
        }
        Long resourceCount = resourceMapper.selectCount(
                Wrappers.<ResourceEntity>lambdaQuery().eq(ResourceEntity::getApplication, id));
        if (resourceCount != null && resourceCount > 0) {
            throw AuthorityBusinessException.applicationHasResources(id);
        }
        applicationService.removeById(id);
    }

    private void ensureNameUnique(String name, String excludeId) {
        if (StringUtils.isBlank(name)) {
            return;
        }
        long count = applicationService.count(Wrappers.<ApplicationEntity>lambdaQuery()
                .eq(ApplicationEntity::getName, name)
                .ne(StringUtils.isNotBlank(excludeId), ApplicationEntity::getId, excludeId));
        if (count > 0) {
            throw AuthorityBusinessException.applicationNameExists(name);
        }
    }

    private void ensureSpringNameUnique(String springApplicationName, String excludeId) {
        if (StringUtils.isBlank(springApplicationName)) {
            return;
        }
        long count = applicationService.count(Wrappers.<ApplicationEntity>lambdaQuery()
                .eq(ApplicationEntity::getSpringApplicationName, springApplicationName)
                .ne(StringUtils.isNotBlank(excludeId), ApplicationEntity::getId, excludeId));
        if (count > 0) {
            throw AuthorityBusinessException.applicationSpringNameExists(springApplicationName);
        }
    }
}
