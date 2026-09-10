package com.lambda.fusion.authority.resource.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.authority.authentication.model.MenuQuery;
import com.lambda.fusion.authority.resource.model.ApiPermissionTreeNode;
import com.lambda.fusion.authority.resource.model.CreateResource;
import com.lambda.fusion.authority.resource.model.MoveResource;
import com.lambda.fusion.authority.resource.model.Resource;
import com.lambda.fusion.authority.resource.model.ResourceTree;
import com.lambda.fusion.authority.resource.service.PermissionService;
import com.lambda.fusion.authority.resource.service.ResourceService;
import com.lambda.fusion.core.FusionConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 资源服务API
 */
@RestController
@SaCheckRole(
        value = {FusionConstants.ROLE_ADMIN, FusionConstants.ROLE_SYSTEM, FusionConstants.ROLE_DEV},
        mode = SaMode.OR)
@RequestMapping({"/authority/resources"})
@Tag(name = "资源管理")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class ResourceController {

    private final ResourceService resourceService;
    private final PermissionService permissionService;

    @SaCheckPermission(value = "authority:resource:tree")
    @GetMapping("/tree")
    @Operation(
            summary = "以树形的方式获取资源权限列表",
            description = "以树形的方式获取资源权限列表",
            parameters = {
                @Parameter(name = "parentId", description = "父菜单ID"),
                @Parameter(name = "level", description = "菜单层级"),
                @Parameter(name = "mode", description = "资源模式(0:系统资源,1:App资源)")
            })
    public List<ResourceTree> tree(@Parameter MenuQuery menuQuery) {
        return resourceService.getResourceTree(menuQuery);
    }

    @SaCheckPermission(value = "authority:resource:list")
    @GetMapping("/list")
    @Operation(summary = "以平铺的方式获取资源权限列表", description = "以平铺的方式获取资源权限列表")
    public List<Resource> list() {
        return resourceService.getAllResources();
    }

    @SaCheckPermission(value = "authority:resource:add")
    @OperationLog(module = "资源管理", value = "新增资源信息")
    @PostMapping({"/{id}", "/"})
    @Operation(summary = "新增资源信息", description = "当id为非空时新增其子资源信息")
    public Resource add(
            @PathVariable(required = false) String id, @Validated @RequestBody CreateResource createResource) {
        if (StringUtils.isNotBlank(id)) {
            createResource.setParentId(id);
        }
        return resourceService.addResource(createResource);
    }

    @SaCheckPermission(value = "authority:resource:update")
    @OperationLog(module = "资源管理", value = "更新资源信息")
    @PutMapping("/{id}")
    @Operation(summary = "更新资源信息", description = "根据编号更新指定的资源信息")
    public Resource update(
            @PathVariable @Parameter(description = "资源编号", required = true) String id, @RequestBody Resource resource) {
        resource.setId(id);
        return resourceService.updateResource(resource);
    }

    @SaCheckPermission(value = "authority:resource:delete")
    @OperationLog(module = "资源管理", value = "删除资源信息")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除资源信息", description = "根据编号删除指定的资源信息")
    public void delete(@PathVariable @Parameter(description = "资源编号", required = true) String id) {
        resourceService.deleteResource(id);
    }

    @SaCheckPermission(value = "authority:resource:move")
    @OperationLog(module = "资源管理", value = "移动资源")
    @PatchMapping("/{id}")
    @Operation(
            summary = "移动资源",
            description = "移动指定的资源到其它位置",
            parameters = {
                @Parameter(name = "tid", description = "参照对象编号"),
                @Parameter(
                        name = "type",
                        description = "移动类型(0:下级,1:之前,2:之后)",
                        schema = @Schema(allowableValues = {"0", "1", "2"}))
            })
    public void move(
            @PathVariable @Parameter(description = "资源编号", required = true) String id,
            @RequestBody MoveResource parameter) {
        parameter.setId(id);
        resourceService.move(parameter);
    }

    @SaCheckPermission(value = "authority:resource:api-permission-list")
    @GetMapping("/{id}/api-permissions")
    @Operation(summary = "查询接口权限树", description = "按应用与分组返回接口权限树，支持按应用和接口名称过滤")
    public List<ApiPermissionTreeNode> listApiPermissions(
            @PathVariable @Parameter(description = "资源编号", required = true) String id,
            @RequestParam(required = false) @Parameter(description = "应用名称") String application,
            @RequestParam(required = false) @Parameter(description = "接口名称") String name) {
        return permissionService.listPermissionTree(id, application, name);
    }

    @SaCheckPermission(value = "authority:resource:api-permission-bind")
    @OperationLog(module = "资源管理", value = "绑定接口权限")
    @PutMapping("/{id}/api-permissions/{permissionId}")
    @Operation(summary = "绑定接口权限", description = "将指定接口权限绑定到菜单资源")
    public void bindApiPermission(
            @PathVariable @Parameter(description = "资源编号", required = true) String id,
            @PathVariable @Parameter(description = "接口权限ID", required = true) String permissionId) {
        permissionService.bind(id, permissionId);
    }

    @SaCheckPermission(value = "authority:resource:api-permission-unbind")
    @OperationLog(module = "资源管理", value = "解绑接口权限")
    @DeleteMapping("/{id}/api-permissions/{permissionId}")
    @Operation(summary = "解绑接口权限", description = "将指定接口权限从菜单资源解绑")
    public void unbindApiPermission(
            @PathVariable @Parameter(description = "资源编号", required = true) String id,
            @PathVariable @Parameter(description = "接口权限ID", required = true) String permissionId) {
        permissionService.unbind(id, permissionId);
    }
}
