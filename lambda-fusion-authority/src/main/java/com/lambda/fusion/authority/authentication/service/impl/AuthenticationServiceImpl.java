package com.lambda.fusion.authority.authentication.service.impl;

import static com.lambda.fusion.core.FusionConstants.AT;
import static com.lambda.fusion.core.FusionConstants.ROLE_TENANT;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.common.collect.Sets;
import com.lambda.cloud.core.principal.LoginUser;
import com.lambda.cloud.web.TenantHolder;
import com.lambda.fusion.authority.AuthorityConstants;
import com.lambda.fusion.authority.AuthorityProperties;
import com.lambda.fusion.authority.authentication.assembler.MenuRouteAssembler;
import com.lambda.fusion.authority.authentication.mapper.AuthenticationMapper;
import com.lambda.fusion.authority.authentication.model.*;
import com.lambda.fusion.authority.authentication.service.AuthenticationService;
import com.lambda.fusion.authority.exception.AuthorityBusinessException;
import com.lambda.fusion.authority.role.mapper.RoleMapper;
import com.lambda.fusion.authority.role.model.SimpleRole;
import com.lambda.fusion.authority.role.model.UserAuthority;
import com.lambda.fusion.authority.user.mapper.UserInfoMapper;
import com.lambda.fusion.authority.user.mapper.UserMapper;
import com.lambda.fusion.authority.user.mapper.UserThirdPartMapper;
import com.lambda.fusion.authority.user.model.CreateUser;
import com.lambda.fusion.authority.user.model.User;
import com.lambda.fusion.authority.user.model.UserInfo;
import com.lambda.fusion.authority.user.model.UserProfile;
import com.lambda.fusion.authority.user.model.entity.UserInfoEntity;
import com.lambda.fusion.authority.user.service.UserService;
import com.lambda.fusion.authority.user.service.UserThirdPartService;
import com.lambda.fusion.authority.utils.AuthorityHelper;
import com.lambda.fusion.config.ConfigProperties;
import com.lambda.fusion.core.FusionConstants;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.core.tree.builder.TreeBuilder;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.security.exception.AuthenticationException;
import com.lambda.security.exception.UsernameNotFoundException;
import com.lambda.security.provider.ThirdPartLoginResult;
import com.lambda.security.service.ThirdPartyLoginService;
import com.lambda.security.service.UserDetailService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证服务实现类
 * 负责用户认证、授权和导航菜单相关的业务逻辑实现
 */
@Slf4j
@Primary
@Service
@SuppressFBWarnings("EI_EXPOSE_REP2")
@RequiredArgsConstructor
public class AuthenticationServiceImpl implements AuthenticationService, UserDetailService, ThirdPartyLoginService {

    private final AuthenticationMapper authenticationMapper;
    private final UserInfoMapper userInfoMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final ConfigProperties configProperties;
    private final AuthorityProperties authorityProperties;
    private final UserThirdPartMapper userThirdpartMapper;
    private final UserService userService;
    private final UserThirdPartService userThirdPartService;
    private final MenuRouteAssembler menuRouteAssembler;

    @Override
    public LoginUser loginByUsername(String username, String loginType) {
        if (username == null) {
            throw AuthorityBusinessException.invalidParameter("username不能为空");
        }
        AuthUser authUser = authenticationMapper.selectUserDetailByUsername(username);
        if (authUser == null) {
            throw AuthorityBusinessException.userNotFound(username);
        }
        return prepareLoginUser(authUser.toUserDetails());
    }

    @Override
    @Cacheable(value = "auth:role", key = "#loginId.toString()")
    public List<String> getRoleList(Object loginId, String loginType) {
        List<String> roles = new ArrayList<>();
        List<UserAuthority> authorities = roleMapper.getUserAuthorityByUsername(loginId.toString());
        if (CollUtil.isEmpty(authorities)) {
            roles.add(FusionConstants.ROLE_USER);
        } else {
            authorities.forEach(userAuthority -> {
                if (userAuthority.getAuthority().startsWith(ROLE_TENANT)) {
                    String tenantAuthority = ROLE_TENANT + AT + userAuthority.getOrgId();
                    roles.add(tenantAuthority);
                } else {
                    roles.add(userAuthority.getAuthority());
                }
            });
        }
        return roles;
    }

    @Override
    @Cacheable(value = "auth:permission", key = "#loginId.toString()")
    public List<String> getPermissionList(Object loginId, String loginType) {
        List<String> authorities = authenticationMapper.selectAuthoritiesByUsername(loginId.toString());
        List<String> permissions = authenticationMapper.selectPermissionsByUsername(loginId.toString());
        if (CollUtil.isNotEmpty(permissions)) {
            authorities.addAll(permissions);
        }

        User user = userMapper.selectUserByUsername(loginId.toString());
        if (AuthorityHelper.isTenant(user)) {
            List<String> tenantAdminPermissions =
                    authenticationMapper.selectTenantAdminPermissions(ROLE_TENANT + AT + user.getTenantId());
            if (CollUtil.isNotEmpty(tenantAdminPermissions)) {
                authorities.addAll(tenantAdminPermissions);
            }
            List<String> tenantAdminAuthorities =
                    authenticationMapper.selectTenantAdminAuthorities(ROLE_TENANT + AT + user.getTenantId());
            if (CollUtil.isNotEmpty(tenantAdminAuthorities)) {
                authorities.addAll(tenantAdminAuthorities);
            }
        }

        return authorities;
    }

    @Override
    public LoginUser loginByMobile(String mobile, String loginType) throws AuthenticationException {
        List<AuthUser> details = authenticationMapper.selectUserDetailsByMobile(mobile);
        AuthUser user = Optional.ofNullable(details)
                .filter(CollUtil::isNotEmpty)
                .filter(d -> d.size() == 1)
                .map(List::getFirst)
                .orElseThrow(() -> new UsernameNotFoundException("手机号不存在！"));
        return prepareLoginUser(user.toUserDetails());
    }

    @Override
    public List<MenuRoute> getUserMenus(UserDetails loginUser, String parentId, Integer level) {
        MenuQuery query = new MenuQuery();
        query.setParentId(parentId);
        query.setLevel(level);
        query.setMode(0);
        if (!loginUser.isDev()) {
            query.setIds(new ArrayList<>(loginUser.getRoles()));
        }
        return getUserMenus(loginUser, query);
    }

    /**
     * 菜单下发语义（SQL 层统一过滤，dev/非 dev 一致）：
     *
     * <ul>
     *   <li>主控台前端：{@code /auth/menus?mode=0}（不传 application）——下发 APPLICATION 为空的
     *       菜单 ∪ 属于 STANDALONE=0（非独立前端）应用的菜单；
     *   <li>独立前端：{@code /auth/menus?mode=0&application={appId}}——只返回该应用的菜单树。
     * </ul>
     */
    @Override
    public List<MenuRoute> getUserMenus(MenuQuery query) {
        UserDetails loginUser = AuthUtils.getUser();
        MenuQuery effective = new MenuQuery();
        effective.setParentId(query.getParentId());
        effective.setLevel(query.getLevel());
        effective.setMode(query.getMode());
        effective.setApplication(query.getApplication());
        effective.setName(query.getName());
        // 独立前端按 application 取菜单时同样受角色约束：非 dev 用户仍只下发其角色被授权的资源
        if (!loginUser.isDev()) {
            effective.setIds(new ArrayList<>(loginUser.getRoles()));
        }
        return getUserMenus(loginUser, effective);
    }

    @Override
    public List<MenuRoute> getUserMenus(UserDetails userDetails, MenuQuery query) {
        List<MenuRoute> menuRoutes = authenticationMapper.selectNavigation(query);
        List<MenuRoute> menuRouteTree = TreeBuilder.build(menuRoutes);
        menuRouteAssembler.enrich(menuRouteTree);
        return menuRouteTree;
    }

    @Override
    public List<UserProfile> getUsersByRoleId(String roleId) {
        return authenticationMapper.selectUserProfileByRoleId(roleId);
    }

    @Override
    public AuthenticatedUser getUserInfo() {
        LoginUser loginUser = AuthUtils.getUser();
        AuthUser authUser = authenticationMapper.selectUserDetailByUsername(loginUser.getName());
        if (authUser == null) {
            throw new UsernameNotFoundException("user not found");
        }

        AuthenticatedUser user = new AuthenticatedUser();
        BeanUtil.copyProperties(authUser, user);

        user.setUserId(authUser.getUsername());
        user.setRealName(authUser.getNickname());

        if (CollUtil.isNotEmpty(authUser.getAuthorities())) {
            user.setRoles(new ArrayList<>(authUser.getAuthorities()));
        } else {
            user.setRoles(new ArrayList<>());
        }

        UserInfoEntity userInfoEntity = userInfoMapper.selectById(loginUser.getName());
        if (userInfoEntity != null) {
            user.setAvatar(userInfoEntity.getAvatar());
            user.setDesc(userInfoEntity.getRemark());
        }

        user.setToken(StpUtil.getTokenValue());
        user.setHomePath(configProperties.getApplication().getHomePath());

        return user;
    }

    @Override
    public List<String> getUserPermissions() {
        return authenticationMapper.selectAuthoritiesByUsername(
                AuthUtils.getUser().getName());
    }

    private LoginUser prepareLoginUser(UserDetails userDetails) {
        Set<String> roles = userDetails.getRoles();
        if (CollUtil.isEmpty(roles)) {
            userDetails.setRoles(Sets.newHashSet(FusionConstants.ROLE_USER));
        } else {
            userDetails.getRoles().stream()
                    .filter(role -> role.startsWith(ROLE_TENANT))
                    .findFirst()
                    .ifPresent(role -> {
                        String tenantAuthority = ROLE_TENANT + AT + userDetails.getOrgId();
                        userDetails.setRoles(Sets.newHashSet(tenantAuthority));
                    });
        }
        Optional.ofNullable(TenantHolder.getTenantId())
                .filter(StrUtil::isNotBlank)
                .ifPresent(userDetails::setTenantId);

        return userDetails;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginUser loadByThirdLoginResult(ThirdPartLoginResult thirdLoginResult, String loginType) {
        ThirdPartyUser thirdPartyUser = thirdLoginResult.getBody(ThirdPartyUser.class);
        if (thirdPartyUser == null) {
            log.warn("三方登录结果解析失败，无法获取第三方用户信息");
            throw AuthorityBusinessException.invalidParameter("三方登录信息为空");
        }
        String username = userThirdpartMapper.findUsernameByThirdTypeAndOpenId(
                thirdPartyUser.getThirdType(), thirdPartyUser.getOpenId());
        if (username == null) {
            if (!supportsThirdPartyAutoRegister(loginType)) {
                throw AuthorityBusinessException.authUserNotFound(loginType);
            }
            log.info(
                    "三方用户首次登录，自动注册: loginType={}, thirdType={}, openId={}",
                    loginType,
                    thirdPartyUser.getThirdType(),
                    thirdPartyUser.getOpenId());
            username = autoRegisterThirdPartyUser(thirdPartyUser);
        }
        return loginByUsername(username, loginType);
    }

    private boolean supportsThirdPartyAutoRegister(String loginType) {
        AuthorityProperties.ThirdPartConfig thirdPartConfig = authorityProperties.getThirdPart();
        return thirdPartConfig.isAutoRegister()
                && CollUtil.contains(thirdPartConfig.getAutoRegisterLoginTypes(), loginType);
    }

    private String autoRegisterThirdPartyUser(ThirdPartyUser thirdPartyUser) {
        AuthorityConstants.ThirdType thirdType = AuthorityConstants.ThirdType.of(thirdPartyUser.getThirdType());
        String generatedUsername = generateUsername(thirdType, thirdPartyUser.getOpenId());

        String nickname = StrUtil.blankToDefault(thirdPartyUser.getNickname(), thirdType.getDefaultNickname());

        CreateUser createUser = new CreateUser();
        createUser.setUsername(generatedUsername);
        createUser.setNickname(nickname);
        createUser.setEnabled(true);

        createUser.setAuthorities(List.of(new SimpleRole(FusionConstants.ROLE_USER)));
        UserInfo userInfo = new UserInfo();
        userInfo.setAvatar(thirdPartyUser.getAvatar());
        userInfo.setRemark(thirdPartyUser.getRemark());
        createUser.setProps(userInfo);

        try {
            userService.addUser(createUser, AuthUtils.getUser());
            userThirdPartService.bind(generatedUsername, thirdPartyUser.getThirdType(), thirdPartyUser.getOpenId());
        } catch (DuplicateKeyException e) {
            String existingUsername = userThirdpartMapper.findUsernameByThirdTypeAndOpenId(
                    thirdPartyUser.getThirdType(), thirdPartyUser.getOpenId());
            if (existingUsername != null) {
                return existingUsername;
            }
            log.warn(
                    "三方用户自动注册冲突，用户名: {}, thirdType: {}, openId: {}",
                    generatedUsername,
                    thirdPartyUser.getThirdType(),
                    thirdPartyUser.getOpenId());
            throw AuthorityBusinessException.userNameExists(generatedUsername);
        }

        return generatedUsername;
    }

    private String generateUsername(AuthorityConstants.ThirdType thirdType, String openId) {
        String prefix = thirdType.getUsernamePrefix();
        String candidate = prefix + "_" + openId;
        if (candidate.length() <= 32) {
            return candidate;
        }
        String hash = DigestUtils.md5Hex(openId);
        int hashLength = 32 - prefix.length() - 1;
        return prefix + "_" + hash.substring(0, hashLength);
    }
}
