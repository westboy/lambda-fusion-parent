package com.lambda.fusion.authority.organization.controller;

import static com.lambda.fusion.core.utils.SqlParamUtils.fuzzyQuery;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lambda.cloud.core.utils.OperatorUtils;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.authority.exception.AuthorityBusinessException;
import com.lambda.fusion.authority.organization.model.CreateOrganization;
import com.lambda.fusion.authority.organization.model.Organization;
import com.lambda.fusion.authority.organization.model.OrganizationQuery;
import com.lambda.fusion.authority.organization.model.OrganizationTree;
import com.lambda.fusion.authority.organization.model.UpdateOrganization;
import com.lambda.fusion.authority.organization.model.UserOrganization;
import com.lambda.fusion.authority.organization.model.UserOrganizationChange;
import com.lambda.fusion.authority.organization.service.OrganizationService;
import com.lambda.fusion.authority.resource.model.MoveResource;
import com.lambda.fusion.core.utils.AuthUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping({"/authority/organization"})
@Tag(name = "组织管理")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class OrganizationController {

    private final OrganizationService organizationService;

    @SaCheckPermission(value = "authority:org:tree")
    @GetMapping("/tree")
    @Operation(summary = "以树形的方式获取组织机构列表", description = "以树形的方式获取组织机构列表")
    public List<Organization> tree(
            @RequestParam(required = false) @Parameter(description = "组织编码") String name,
            @RequestParam(required = false) @Parameter(description = "组织别名") String alias,
            @RequestParam(required = false) Boolean enabled) {
        OrganizationQuery organizationQuery = buildOrganizationQuery();
        if (BooleanUtils.isTrue(enabled)) {
            organizationQuery.setEnabled(true);
        }
        if (StringUtils.isNotBlank(name)) {
            organizationQuery.setName(name);
        }
        if (StringUtils.isNotBlank(alias)) {
            organizationQuery.setAlias(fuzzyQuery(alias));
        }
        organizationQuery.setTenantId(AuthUtils.getTenantId());
        return organizationService.organizationTreeList(organizationQuery);
    }

    @SaCheckPermission(value = "authority:org:list")
    @GetMapping("/list")
    @Operation(summary = "获取组织机构树形下拉列表", description = "查询组织机构列表树形下拉列表")
    public List<OrganizationTree> list() {
        OrganizationQuery parameters = buildOrganizationQuery();
        parameters.setEnabled(true);
        return organizationService.getOrganizationTree(parameters);
    }

    @SaCheckPermission(value = "authority:org:add")
    @OperationLog(module = "组织管理", value = "新增组织机构信息")
    @PostMapping({"", "/{id}"})
    @Operation(summary = "新增组织机构信息", description = "当id为非空时新增其子组织机构信息")
    public Organization addOrganization(
            @Parameter(description = "组织编号") @PathVariable(required = false) String id,
            @Parameter(description = "组织信息", required = true) @Valid @RequestBody
                    CreateOrganization createOrganization) {
        if (StringUtils.isNotBlank(id)) {
            createOrganization.setParentId(id);
            Organization organization = organizationService.queryOrganizationById(id);
            if (organization == null) {
                throw AuthorityBusinessException.organizationNotFound(id);
            }
        }
        return organizationService.addOrganization(createOrganization);
    }

    @OperationLog(module = "组织管理", value = "更新组织机构信息")
    @PutMapping("/{id}")
    @Operation(summary = "更新组织机构信息", description = "根据编号更新指定的组织机构信息")
    public Organization updateOrganization(
            @Parameter(description = "组织编号", required = true) @PathVariable String id,
            @Parameter(description = "组织信息", required = true) @Valid @RequestBody
                    UpdateOrganization updateOrganization) {
        Organization org = organizationService.queryOrganizationById(id);
        if (org == null) {
            throw AuthorityBusinessException.organizationNotFound(id);
        }
        updateOrganization.setId(id);
        return organizationService.updateOrganization(updateOrganization);
    }

    @SaCheckPermission(value = "authority:org:delete")
    @OperationLog(module = "组织管理", value = "删除组织机构信息")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除组织机构信息", description = "根据编号删除指定的组织机构信息")
    public void delete(@Parameter(description = "组织编号", required = true) @PathVariable String id) {
        Organization org = organizationService.queryOrganizationById(id);
        if (org == null) {
            throw AuthorityBusinessException.organizationNotFound(id);
        }
        organizationService.deleteOrganization(id);
    }

    @SaCheckPermission(value = "authority:org:user-get")
    @GetMapping("/user/{username}")
    @Operation(summary = "查询用户组织信息", description = "查询用户组织信息")
    public UserOrganization queryUserOrganization(
            @Parameter(description = "用户名称", required = true) @PathVariable String username) {
        UserOrganizationChange resource = new UserOrganizationChange();
        resource.setUsername(username);
        return organizationService.queryUserOrganization(resource);
    }

    @SaCheckPermission(value = "authority:org:user-add")
    @OperationLog(module = "组织管理", value = "添加用户组织信息")
    @PostMapping("/user/{username}")
    @Operation(summary = "添加用户组织信息", description = "添加用户组织信息")
    public UserOrganization addUserOrganization(
            @Parameter(description = "用户名称", required = true) @PathVariable String username,
            @Parameter(description = "用户组织信息", required = true) @RequestBody UserOrganizationChange resource) {
        resource.setUsername(username);
        return organizationService.addUserOrganization(resource);
    }

    @SaCheckPermission(value = "authority:org:user-delete")
    @OperationLog(module = "组织管理", value = "删除用户组织信息")
    @DeleteMapping("/user/{username}")
    @Operation(summary = "删除用户组织信息", description = "删除用户添加组织信息")
    public void deleteUserOrganization(
            @Parameter(description = "用户名称", required = true) @PathVariable String username) {
        organizationService.deleteUserOrganization(username);
    }

    @SaCheckPermission(value = "authority:org:user-update")
    @OperationLog(module = "组织管理", value = "更新用户组织关系")
    @PutMapping("/user/{username}")
    @Operation(summary = "更新用户组织关系", description = "更新用户组织关系")
    public UserOrganization updateUserOrganization(
            @Parameter(description = "用户名称", required = true) @PathVariable String username,
            @Parameter(description = "用户组织信息", required = true) @RequestBody UserOrganizationChange resource) {
        resource.setUsername(username);
        return organizationService.updateUserOrganization(resource);
    }

    @SaCheckPermission(value = "authority:org:enable")
    @OperationLog(module = "组织管理", value = "启用组织机构")
    @PatchMapping("/{id}/enabled")
    @Operation(summary = "启用组织机构")
    public void enabled(@PathVariable @Parameter(description = "机构Id", required = true) String id) {
        Organization org = organizationService.queryOrganizationById(id);
        if (org == null) {
            throw AuthorityBusinessException.organizationNotFound(id);
        }
        organizationService.prohibitOrganization(1, id);
    }

    @SaCheckPermission(value = "authority:org:disable")
    @OperationLog(module = "组织管理", value = "禁用组织机构")
    @PatchMapping("/{id}/disabled")
    @Operation(summary = "禁用组织机构")
    public void disabled(@PathVariable @Parameter(description = "机构Id", required = true) String id) {
        Organization org = organizationService.queryOrganizationById(id);
        if (org == null) {
            throw AuthorityBusinessException.organizationNotFound(id);
        }
        organizationService.prohibitOrganization(0, id);
    }

    @SaCheckPermission(value = "authority:org:import")
    @OperationLog(module = "组织管理", value = "导入excel批量增加组织")
    @Operation(summary = "导入excel批量增加组织")
    @PostMapping({"/import"})
    public void importExcel(@RequestParam("file") MultipartFile file) {
        organizationService.addOrganizationByImport(file);
    }

    @SaCheckPermission(value = "authority:org:move")
    @OperationLog(module = "组织管理", value = "移动组织")
    @PatchMapping("/{id}")
    @Operation(
            summary = "移动组织",
            description = "移动指定的组织到其它位置",
            parameters = {
                @Parameter(name = "tid", description = "目标节点编号"),
                @Parameter(
                        name = "type",
                        description = "移动类型(0:下级,1:之前,2:之后)",
                        schema = @Schema(allowableValues = {"0", "1", "2"}))
            })
    public void move(
            @PathVariable @Parameter(description = "拖动节点", required = true) String id,
            @Parameter(description = "目标节点", required = true) @RequestParam("tid") String tid,
            @Parameter(description = "移动类型", required = true) @RequestParam("type") int type) {
        MoveResource parameter = new MoveResource();
        parameter.setId(id);
        parameter.setTid(tid);
        parameter.setType(type);
        organizationService.move(parameter);
    }

    /**
     * 获取查询组织机构的参数
     */
    private OrganizationQuery buildOrganizationQuery() {
        OrganizationQuery parameters = new OrganizationQuery();
        String tenantId = OperatorUtils.getOperator().getTenantId();
        parameters.setOwner(StringUtils.isNotBlank(tenantId) ? tenantId : null);
        return parameters;
    }
}
