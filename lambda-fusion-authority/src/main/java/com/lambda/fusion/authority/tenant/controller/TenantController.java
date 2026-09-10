package com.lambda.fusion.authority.tenant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.annotation.SaMode;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.authority.tenant.model.TenantDomainInfo;
import com.lambda.fusion.authority.tenant.model.TenantEntity;
import com.lambda.fusion.authority.tenant.model.TenantOption;
import com.lambda.fusion.authority.tenant.model.TenantQuery;
import com.lambda.fusion.authority.tenant.service.TenantService;
import com.lambda.fusion.core.FusionConstants;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 租户信息表相关接口
 */
@SaCheckRole(
        value = {FusionConstants.ROLE_ADMIN, FusionConstants.ROLE_SYSTEM, FusionConstants.ROLE_DEV},
        mode = SaMode.OR)
@Slf4j
@RestController
@RequestMapping("/authority/tenant")
@Tag(name = "租户管理")
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP")
public class TenantController {

    private final TenantService tenantService;

    @SaCheckPermission(value = "authority:tenant:page")
    @PostMapping("/page")
    @Operation(summary = "分页查询所有租户数据列表（V2版本）", description = "使用LambdaQueryWrapper进行分页查询，支持更灵活的排序和查询条件")
    public Page<TenantEntity> pageTenant(@RequestBody TenantQuery queryDTO) {
        return tenantService.pageTenant(queryDTO.getPage(), queryDTO.getLambdaQueryWrapper());
    }

    @SaCheckPermission(value = "authority:tenant:list")
    @GetMapping("/options")
    @Operation(summary = "获取租户下拉列表", description = "查询租户下拉列表")
    public List<TenantOption> tenantOptions() {
        return tenantService.getTenantOptions();
    }

    @SaCheckPermission(value = "authority:tenant:get")
    @GetMapping("/{id}")
    @Operation(summary = "根据编号查询租户信息信息", description = "根据id查询租户信息信息")
    public TenantEntity getTenant(@Parameter(description = "租户信息编号", required = true) @PathVariable String id) {
        return tenantService.getById(id);
    }

    @SaCheckPermission(value = "authority:tenant:add")
    @OperationLog(module = "租户管理", value = "新增租户信息信息(含LOGO)")
    @PostMapping
    @Operation(summary = "新增租户信息信息(含LOGO)", description = "新增租户信息信息，支持表单与LOGO同请求提交")
    public TenantEntity saveTenant(
            @Parameter(description = "租户信息信息", required = true) @RequestPart("tenant") String tenant,
            @Parameter(description = "LOGO文件") @RequestPart(value = "logo", required = false) MultipartFile logo,
            @Parameter(description = "OSS客户端名称（可选）") @RequestParam(value = "client", required = false)
                    String clientName) {
        return tenantService.createTenantWithLogo(tenant, logo, clientName);
    }

    @SaCheckPermission(value = "authority:tenant:update")
    @OperationLog(module = "租户管理", value = "更新租户信息信息(含LOGO)")
    @PostMapping(value = "/{id}")
    @Operation(summary = "更新租户信息信息(含LOGO)", description = "更新租户信息信息，支持表单与LOGO同请求提交")
    public TenantEntity updateTenant(
            @Parameter(description = "租户信息编号", required = true) @PathVariable String id,
            @Parameter(description = "租户信息信息", required = true) @RequestPart("tenant") String tenant,
            @Parameter(description = "LOGO文件") @RequestPart(value = "logo", required = false) MultipartFile logo,
            @Parameter(description = "OSS客户端名称（可选）")
                    @RequestParam(value = "client", defaultValue = "default", required = false)
                    String clientName) {
        return tenantService.updateTenantWithLogo(id, tenant, logo, clientName);
    }

    @SaCheckPermission(value = "authority:tenant:delete")
    @OperationLog(module = "租户管理", value = "删除租户信息信息")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除租户信息信息", description = "删除租户信息信息")
    public void deleteTenant(@Parameter(description = "租户编号", required = true) @PathVariable String id) {
        tenantService.deleteTenant(id);
    }

    @SaCheckPermission(value = "authority:tenant:enable")
    @OperationLog(module = "租户管理", value = "启用租户")
    @PatchMapping("/{id}/enabled")
    @Operation(summary = "启用租户")
    public void enabledTenant(@PathVariable @Parameter(description = "租户编号", required = true) String id) {
        tenantService.enableTenant(id);
    }

    @SaCheckPermission(value = "authority:tenant:disable")
    @OperationLog(module = "租户管理", value = "禁用租户")
    @PatchMapping("/{id}/disabled")
    @Operation(summary = "禁用租户")
    public void disabledTenant(@PathVariable @Parameter(description = "租户编号", required = true) String id) {
        tenantService.disableTenant(id);
    }

    @SaCheckPermission(value = "authority:tenant:domain-bind")
    @OperationLog(module = "租户管理", value = "绑定租户域名")
    @PutMapping("/{id}/domain")
    @Operation(summary = "绑定租户域名", description = "为指定租户绑定自定义域名，域名全局唯一")
    public void bindDomain(
            @PathVariable @Parameter(description = "租户编号", required = true) String id,
            @RequestParam @Parameter(description = "域名", required = true) String domain) {
        tenantService.bindDomain(id, domain);
    }

    @SaCheckPermission(value = "authority:tenant:domain-unbind")
    @OperationLog(module = "租户管理", value = "解绑租户域名")
    @DeleteMapping("/{id}/domain")
    @Operation(summary = "解绑租户域名", description = "解除指定租户的域名绑定")
    public void unbindDomain(@PathVariable @Parameter(description = "租户编号", required = true) String id) {
        tenantService.unbindDomain(id);
    }

    @SaIgnore
    @GetMapping("/resolve")
    @Operation(summary = "根据域名解析租户信息", description = "公开接口，根据域名获取租户展示信息（Logo、名称、备案号等）")
    public TenantDomainInfo resolveByDomain(
            @RequestParam @Parameter(description = "域名", required = true) String domain) {
        TenantEntity entity = tenantService.resolveByDomain(domain);
        return TenantDomainInfo.fromEntity(entity);
    }
}
