package com.lambda.fusion.authority.role.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.lambda.cloud.core.utils.Assert;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.authority.AuthorityConstants;
import com.lambda.fusion.authority.role.model.*;
import com.lambda.fusion.authority.role.service.RoleService;
import com.lambda.fusion.core.FusionConstants;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.core.utils.SqlParamUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 用户角色API
 */
@RestController
@RequestMapping({"/authority/roles"})
@Tag(name = "角色管理")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class RoleController {

    private final RoleService roleService;

    @SaCheckPermission(value = "authority:role:list")
    @GetMapping
    @Operation(description = "获取所有角色列表", summary = "获取所有角色列表")
    public List<Role> list() {
        UserDetails userDetails = AuthUtils.getUser();
        return roleService.queryRoles(userDetails);
    }

    @SaCheckPermission(value = "authority:role:grouped")
    @GetMapping("/grouped")
    @Operation(
            description = "获取所有角色分组列表",
            summary = "获取所有角色分组列表",
            parameters = {@Parameter(name = "tenant_id", description = "租户id")})
    public List<GroupRole> grouped() {
        UserDetails userDetails = AuthUtils.getUser();
        return roleService.groupedRoles(userDetails, AuthUtils.getTenantId());
    }

    @SaCheckPermission(value = "authority:role:page")
    @GetMapping({"/page/{number:\\d+}", "/page/{number:\\d+}/size/{size:\\d+}"})
    @Operation(description = "分页查询所有角色列表", summary = "分页查询所有角色列表")
    public Page<Role> page(
            @PathVariable(required = false) Integer number,
            @PathVariable(required = false) Integer size,
            String alias,
            String groupId) {
        UserDetails userDetails = AuthUtils.getUser();
        Map<String, Object> parameters = Maps.newHashMapWithExpectedSize(4);
        if (ObjectUtil.isNotNull(groupId)) {
            parameters.put(AuthorityConstants.GROUP_ID, groupId);
        }
        if (StringUtils.isNotBlank(alias)) {
            parameters.put(AuthorityConstants.ALIAS, SqlParamUtils.fuzzyQuery(alias));
        }
        Set<String> excludes = Sets.newHashSet();
        excludes.add(FusionConstants.ROLE_USER);
        excludes.add(FusionConstants.ROLE_HMAC);
        excludes.add(FusionConstants.ROLE_DEV);
        excludes.add(FusionConstants.ROLE_SYSTEM);
        excludes.add(FusionConstants.ROLE_TENANT);
        if (!userDetails.isDev()) {
            excludes.add(FusionConstants.ROLE_ADMIN);
        }
        parameters.put(AuthorityConstants.EXCLUDES, excludes);
        String tenantId = AuthUtils.getTenantId();
        if (StringUtils.isNotBlank(tenantId)) {
            parameters.put(AuthorityConstants.TENANT_ID, tenantId);
        }
        return roleService.queryRoles(new Page<>(number, size), parameters);
    }

    @SaCheckPermission(value = "authority:role:check")
    @GetMapping("/check/{authority}")
    @Operation(description = "检查角色名称是否重复", summary = "检查角色名称是否重复")
    public Object check(@Parameter(description = "角色名称", required = true) @PathVariable String authority) {
        Map<String, Object> result = new HashMap<>(1);
        result.put("state", roleService.hasExists(authority));
        return result;
    }

    @SaCheckPermission(value = "authority:role:get")
    @GetMapping("/{authority}")
    @Operation(description = "查询角色信息", summary = "查询角色信息")
    public Role update(@Parameter(description = "角色名称", required = true) @PathVariable String authority) {
        Assert.notNull(authority, "角色名称不能为空！");
        return roleService.getRoleByAuthority(authority);
    }

    @SaCheckPermission(value = "authority:role:add")
    @OperationLog(module = "角色管理", value = "新增角色信息")
    @PostMapping
    @Operation(description = "新增角色信息", summary = "新增角色信息")
    public Role add(@Parameter(description = "角色信息", required = true) @RequestBody CreateRole createRole) {
        return roleService.saveRole(AuthUtils.getUser(), createRole);
    }

    @SaCheckPermission(value = "authority:role:update")
    @OperationLog(module = "角色管理", value = "更新角色信息")
    @PutMapping("/{authority}")
    @Operation(description = "更新角色信息", summary = "更新角色信息")
    public Role update(
            @Parameter(description = "角色名称", required = true) @PathVariable String authority,
            @Parameter(description = "角色信息", required = true) @RequestBody UpdateRole updateRole) {
        Assert.notNull(authority, "角色名称不能为空！");
        updateRole.setAuthority(authority);
        return roleService.updateRole(AuthUtils.getUser(), updateRole);
    }

    @SaCheckPermission(value = "authority:role:delete")
    @OperationLog(module = "角色管理", value = "删除角色信息")
    @DeleteMapping("/{authority}")
    @Operation(description = "删除角色信息", summary = "删除角色信息")
    public void delete(@Parameter(description = "角色名称", required = true) @PathVariable String authority) {
        Role source = roleService.getRoleByAuthority(authority);
        if (source != null) {
            roleService.deleteRoleById(authority);
        }
    }

    @SaCheckPermission(value = "authority:role:disable")
    @OperationLog(module = "角色管理", value = "禁用角色")
    @PatchMapping("/{authority}/disabled")
    @Operation(description = "禁用角色", summary = "禁用角色")
    public void disabled(@PathVariable @Parameter(description = "角色名称", required = true) String authority) {
        roleService.prohibitRole(0, authority);
    }

    @SaCheckPermission(value = "authority:role:enable")
    @OperationLog(module = "角色管理", value = "启用角色")
    @PatchMapping("/{authority}/enabled")
    @Operation(description = "启用角色", summary = "启用角色")
    public void enabled(@PathVariable @Parameter(description = "角色名称", required = true) String authority) {
        roleService.prohibitRole(1, authority);
    }

    @SaCheckPermission(value = "authority:role:permission-list")
    @GetMapping("/{authority}/permissions")
    @Operation(description = "查询指定角色的权限信息", summary = "查询指定角色的权限信息")
    public List<AccessPermission> getAccessPermission(
            @Parameter(description = "角色名称", required = true) @PathVariable String authority,
            @Parameter(description = "模式-0:后台资源,1:APP资源") Integer mode,
            @Parameter(description = "所属应用ID，不传表示全部应用") @RequestParam(required = false) String application) {
        return roleService.getAccessPermission(AuthUtils.getUser(), authority, mode, application);
    }

    @SaCheckPermission(value = "authority:role:permission-grant")
    @OperationLog(module = "角色管理", value = "授予指定角色的特定权限")
    @PutMapping("/{authority}/grant/{resourceId}")
    @Operation(description = "授予指定角色的特定权限", summary = "授予指定角色的特定权限")
    public void grantRolePermission(
            @Parameter(description = "角色名称", required = true) @PathVariable String authority,
            @Parameter(description = "资源编号", required = true) @PathVariable String resourceId,
            @Parameter(description = "授权模式.-0:仅使用,1:可管理", schema = @Schema(defaultValue = "1"))
                    @RequestParam(defaultValue = "1")
                    Integer status) {
        roleService.grantRolePermission(authority, resourceId, status, AuthUtils.getUser());
    }

    @SaCheckPermission(value = "authority:role:permission-revoke")
    @OperationLog(module = "角色管理", value = "删除指定角色的特定权限")
    @DeleteMapping("/{authority}/grant/{resourceId}")
    @Operation(description = "删除指定角色的特定权限", summary = "删除指定角色的特定权限")
    public void revokeRolePermission(
            @Parameter(description = "角色名称", required = true) @PathVariable String authority,
            @Parameter(description = "资源编号", required = true) @PathVariable String resourceId) {
        roleService.revokeRolePermission(authority, resourceId, AuthUtils.getUser());
    }

    @SaCheckPermission(value = "authority:role:assign-users")
    @OperationLog(module = "角色管理", value = "角色批量分配用户")
    @Operation(description = "角色批量分配用户", summary = "角色批量分配用户")
    @PostMapping("/assignUsers")
    public void assignUsersToRole(@Valid @RequestBody BatchAssignUserRole req) {
        UserDetails userDetails = AuthUtils.getUser();
        roleService.assignUsersToRole(userDetails, req);
    }

    @SaCheckPermission(value = "authority:role-group:list")
    @Operation(description = "分组列表", summary = "分组列表")
    @GetMapping("/group")
    public List<Group> listGroups() {
        UserDetails userDetails = AuthUtils.getUser();
        return roleService.listGroups(userDetails);
    }

    @SaCheckPermission(value = "authority:role-group:add")
    @OperationLog(module = "角色管理", value = "新增角色分组")
    @Operation(description = "新增角色分组", summary = "新增角色分组")
    @PostMapping("/group")
    public Group addGroup(@Parameter Group group) {
        return roleService.addGroup(group);
    }

    @SaCheckPermission(value = "authority:role-group:update")
    @OperationLog(module = "角色管理", value = "修改角色分组")
    @Operation(description = "修改角色分组", summary = "修改角色分组")
    @PutMapping("/group")
    public Group updateGroup(@Parameter Group group) {
        return roleService.updateGroup(group);
    }

    @SaCheckPermission(value = "authority:role-group:delete")
    @OperationLog(module = "角色管理", value = "删除角色分组")
    @Operation(description = "删除角色分组", summary = "删除角色分组")
    @DeleteMapping("/group/{groupId}")
    public void deleteGroup(@Parameter(description = "分组ID") @PathVariable String groupId) {
        roleService.deleteGroup(groupId);
    }
}
