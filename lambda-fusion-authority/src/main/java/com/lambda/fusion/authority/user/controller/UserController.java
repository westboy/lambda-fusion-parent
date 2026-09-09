package com.lambda.fusion.authority.user.controller;

import static com.lambda.fusion.core.FusionConstants.AT;
import static com.lambda.fusion.core.FusionConstants.ROLE_TENANT;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.cloud.core.principal.LoginUser;
import com.lambda.cloud.core.utils.ConvertUtils;
import com.lambda.cloud.core.utils.OperatorUtils;
import com.lambda.fusion.authority.exception.AuthorityBusinessException;
import com.lambda.fusion.authority.organization.service.OrganizationService;
import com.lambda.fusion.authority.role.model.SimpleRole;
import com.lambda.fusion.authority.user.assembler.UserQueryAssembler;
import com.lambda.fusion.authority.user.model.*;
import com.lambda.fusion.authority.user.service.UserCenterService;
import com.lambda.fusion.authority.user.service.UserInfoService;
import com.lambda.fusion.authority.user.service.UserService;
import com.lambda.fusion.authority.user.service.UserThirdPartService;
import com.lambda.fusion.core.FusionConstants;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.core.tree.builder.TreeBuilder;
import com.lambda.fusion.core.utils.AuthUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户信息Api
 *
 */
@Slf4j
@RestController
@RequestMapping({"/authority/users"})
@Tag(name = "用户管理")
@RequiredArgsConstructor
public class UserController {

    private final OrganizationService organizationService;
    private final UserService userService;
    private final UserQueryAssembler userQueryAssembler;
    private final UserCenterService userCenterService;
    private final UserInfoService userInfoService;
    private final UserThirdPartService userThirdpartService;

    @SaCheckPermission(value = "authority:user:page")
    @GetMapping({"", "/page", "/page/{number:\\d+}/size/{size:\\d+}"})
    @Operation(summary = "分页查询所有用户列表")
    public Page<User> page(
            @PathVariable(required = false) Integer number,
            @PathVariable(required = false) Integer size,
            @Valid UserQuery userQuery) {
        if (number != null) {
            userQuery.setPageNum(number);
        }
        if (size != null) {
            userQuery.setPageSize(size);
        }
        UserQueryContext userQueryContext = userQueryAssembler.buildUserQueryContext(userQuery);
        return userService.getUsers(userQuery.getPage(), userQueryContext);
    }

    @SaCheckPermission(value = "authority:user:check")
    @GetMapping(value = "/{username}/check")
    @Operation(summary = "检查用户名是否存在")
    public Boolean checkName(@PathVariable @Parameter(description = "用户名", required = true) String username) {
        return userService.checkUserName(StrUtil.trim(username));
    }

    @SaCheckPermission(value = "authority:user:get")
    @GetMapping(value = "/{username}")
    @Operation(summary = "查询用户信息")
    public User getUser(@PathVariable @Parameter(description = "用户名", required = true) String username) {
        return userService.getByUsername(StrUtil.trim(username));
    }

    @SaCheckPermission(value = "authority:user:search")
    @GetMapping("/search")
    @Operation(summary = "根据关键字模糊查询用户列表")
    public List<User> search(@Parameter(description = "关键字", required = true) @RequestParam("key") String key) {
        return userService.getUsersByKey(key);
    }

    @SaCheckPermission(value = "authority:user:list")
    @GetMapping("/allUser")
    @Operation(summary = "查询用户下拉列表")
    public List<UserProfile> allUser(@RequestParam(required = false, defaultValue = "false") Boolean isAll) {
        UserDetails loginUser = AuthUtils.getUser();
        List<String> orgIds =
                isAll != null && isAll ? Collections.emptyList() : organizationService.getSubOrganizations(loginUser);
        return userService.getUserProfiles(loginUser, orgIds);
    }

    @SaCheckPermission(value = "authority:user:current")
    @GetMapping("/current")
    @Operation(summary = "查询当前用户的详细信息")
    public User getCurrent() {
        return userService.getCurrentUser(AuthUtils.getUser());
    }

    @SaCheckPermission(value = "authority:user:get-by-role")
    @GetMapping("/authority/{authority}")
    @Operation(summary = "根据角色查询用户名", description = "根据角色查询用户")
    public List<String> getNamesByAuthority(@PathVariable String authority) {
        LoginUser operator = OperatorUtils.getOperator();
        return userService.getUserNamesByAuthority(operator.getOrgId(), authority);
    }

    @SaCheckPermission(value = "authority:user:add")
    @PostMapping
    @Operation(summary = "新增用户信息")
    public User add(@Parameter(description = "用户信息", required = true) @Valid @RequestBody CreateUser createUser) {
        userService.addUser(createUser, AuthUtils.getUser());
        if (MapUtils.isNotEmpty(createUser.getPersonal())) {
            userService.addUserFields(createUser.getPersonal(), createUser.getUsername());
        }
        return userService.getByUsername(createUser.getUsername());
    }

    @SaCheckPermission(value = "authority:user:update")
    @PutMapping(value = "/{username}")
    @Operation(summary = "更新用户信息")
    public User update(
            @PathVariable @Parameter(description = "用户名称", required = true) String username,
            @Parameter(description = "用户信息", required = true) @Valid @RequestBody UpdateUser updateUser) {
        updateUser.setUsername(username);
        userService.updateUser(updateUser, AuthUtils.getUser());
        return userService.getByUsername(username);
    }

    @SaCheckPermission(value = "authority:user:delete")
    @DeleteMapping(value = "/{username}")
    @Operation(summary = "删除用户信息")
    public void delete(@PathVariable @Parameter(description = "用户名", required = true) String username) {
        userService.deleteUser(AuthUtils.getUser(), username);
    }

    @SaCheckLogin
    @PutMapping("/password/edit")
    @Operation(summary = "修改用户密码", description = "用于用户自己修改密码")
    public void updateUserPassword(
            @Parameter(description = "修改密码参数", required = true) @RequestBody ResetPassword resetPassword) {
        LoginUser operator = OperatorUtils.getOperator();
        String oldPassword = resetPassword.getOldPassword();
        String newPassword = resetPassword.getNewPassword();
        if (oldPassword == null) {
            throw AuthorityBusinessException.invalidParameter("原密码不能为空");
        }
        if (newPassword == null) {
            throw AuthorityBusinessException.invalidParameter("新密码不能为空");
        }
        resetPassword.setUsername(operator.getName());
        userService.updateUserPassword(operator.getName(), oldPassword, newPassword);
    }

    @SaCheckPermission(value = "authority:user:password-reset")
    @PutMapping("/password/reset")
    @Operation(summary = "重置用户密码", description = "主要由用户管理员使用")
    public String resetUserPassword(
            @Parameter(description = "重置密码参数", required = true) @RequestBody ResetPassword resetPassword) {
        String username = resetPassword.getUsername();
        if (username == null) {
            throw AuthorityBusinessException.invalidParameter("username不能为空");
        }
        return userService.resetUserPassword(resetPassword);
    }

    @SaCheckPermission(value = "authority:user:disable")
    @PatchMapping("/{username}/disabled")
    @Operation(summary = "禁用用户")
    public void disabled(@PathVariable @Parameter(description = "用户名称", required = true) String username) {
        userService.deactivateUser(AuthUtils.getUser(), FusionConstants.DISABLED, username);
    }

    @SaCheckPermission(value = "authority:user:enable")
    @PatchMapping("/{username}/enabled")
    @Operation(summary = "启用用户")
    public void enabled(@PathVariable @Parameter(description = "用户名称", required = true) String username) {
        userService.deactivateUser(AuthUtils.getUser(), FusionConstants.ENABLED, username);
    }

    @SaCheckPermission(value = "authority:user:unlock")
    @PatchMapping("/{username}/unlock")
    @Operation(summary = "解锁用户")
    public void unlock(@PathVariable @Parameter(description = "用户名称", required = true) String username) {
        userService.unlockUser(username, AuthUtils.getUser());
    }

    @SaCheckPermission(value = "authority:user:permission")
    @GetMapping("/{username}/permission")
    @Operation(summary = "查询用户所有权限")
    public List<Permission> userPermissions(
            @PathVariable @Parameter(description = "用户ID", required = true) String username,
            @RequestParam(required = false) String mode,
            @Parameter(description = "所属应用ID，不传表示全部应用") @RequestParam(required = false) String application) {
        List<Permission> permissions = userService.getUserPermissions(username, mode, application);
        return TreeBuilder.build(permissions);
    }

    @SaCheckPermission(value = "authority:user:third-part")
    @GetMapping(value = "/{username}/thirdpart")
    @Operation(summary = "查询用户第三方绑定列表")
    public List<ThirdPartBinding> listThirdPartBindings(
            @PathVariable @Parameter(description = "用户名", required = true) String username) {
        return userThirdpartService.listByUsername(username);
    }

    @SaCheckPermission(value = "authority:user:third-part-unbind")
    @PatchMapping(value = "/unbind/{username}/{type}")
    @Operation(summary = "解除第三方绑定")
    public void unbind(
            @PathVariable @Parameter(description = "用户编号", required = true) String username,
            @PathVariable
                    @Parameter(
                            description = "第三方绑定类型(ThirdType code，如：dingTalk、wxOpen、wxMa、alipayMa)",
                            required = true,
                            schema = @Schema(defaultValue = "dingTalk"))
                    String type) {
        LoginUser operator = OperatorUtils.getOperator();
        userInfoService.unbindUserInfo(operator, type, username);
    }

    @SaCheckPermission(value = "authority:user:mobile-update")
    @PutMapping(value = "/update/mobile")
    @Operation(summary = "更新用户手机号")
    public void updateMobile(
            @Parameter(description = "手机号", required = true) @RequestParam("mobile") String mobile,
            @Parameter(description = "验证码", required = true) @RequestParam("verifyCode") String verifyCode) {
        LoginUser operator = OperatorUtils.getOperator();
        userCenterService.updateMobile(operator.getName(), mobile, verifyCode);
    }

    @SaCheckPermission(value = "authority:user:email-update")
    @PutMapping(value = "/update/email")
    @Operation(summary = "更新用户邮箱")
    public void updateEmail(
            @Parameter(description = "邮箱", required = true) @RequestParam("email") String email,
            @Parameter(description = "验证码", required = true) @RequestParam("verifyCode") String verifyCode) {
        LoginUser operator = OperatorUtils.getOperator();
        userCenterService.updateEmail(operator.getName(), email, verifyCode);
    }

    @SaCheckPermission(value = "authority:user:info-update")
    @PostMapping(value = "/update/info")
    @Operation(
            summary = "更新个人信息",
            parameters = {
                @Parameter(name = "nickname", description = "昵称", required = true),
                @Parameter(name = "email", description = "邮箱", required = true),
                @Parameter(name = "personal", description = "新增字段")
            })
    public User updateInfo(MultipartFile avatar, RestUserInfo restUserInfo) {
        LoginUser operator = OperatorUtils.getOperator();
        restUserInfo.setUsername(operator.getName());
        if (avatar != null) {
            log.info("file: {}", avatar);
        }
        return userCenterService.updateInfo(restUserInfo);
    }

    @SaCheckPermission(value = "authority:user:mobile-code-send")
    @PostMapping(value = "/send/mobile/code")
    @Operation(summary = "发送手机验证码")
    public VerifyCode sendMobileVerifyCode(
            @Parameter(description = "手机号", required = true) @RequestParam("mobile") String mobile) {
        LoginUser operator = OperatorUtils.getOperator();
        return userCenterService.sendMobileVerifyCode(operator.getName(), mobile);
    }

    @SaCheckPermission(value = "authority:user:tenant-admin-list")
    @GetMapping("/tenant/{tenantId}/admins")
    @Operation(summary = "查询租户管理员")
    public List<User> tenantAdmins(@PathVariable @Parameter(description = "租户ID", required = true) String tenantId) {
        return userService.queryTenantAdmins(tenantId);
    }

    @SaCheckPermission(value = "authority:user:tenant-admin-add")
    @PutMapping(value = "/tenant/{tenantId}/admins")
    @Operation(summary = "添加租户管理员用户信息")
    public User addTenantUser(
            @PathVariable @Parameter(description = "租户ID", required = true) String tenantId,
            @Parameter(description = "用户信息", required = true) @Valid @RequestBody CreateTenantUser createTenantUser) {
        CreateUser createUser = ConvertUtils.convert(createTenantUser);
        createUser.setTenantId(tenantId);
        SimpleRole simpleRole = new SimpleRole(ROLE_TENANT + AT + tenantId);
        createUser.setAuthorities(List.of(simpleRole));
        userService.addUser(createUser, AuthUtils.getUser());
        return userService.getByUsername(createTenantUser.getUsername());
    }

    @SaCheckPermission(value = "authority:user:tenant-admin-update")
    @PutMapping(value = "/tenant/{tenantId}/admins/{username}")
    @Operation(summary = "更新租户管理员用户信息")
    public User updateTenantUser(
            @PathVariable @Parameter(description = "租户ID", required = true) String tenantId,
            @PathVariable @Parameter(description = "用户名称", required = true) String username,
            @Parameter(description = "用户信息", required = true) @Valid @RequestBody UpdateTenantUser updateTenantUser) {
        User user = ConvertUtils.convert(updateTenantUser);
        user.setTenantId(tenantId);
        user.setUsername(username);
        userService.updateTenantUser(user, AuthUtils.getUser());
        return userService.getByUsername(username);
    }
}
