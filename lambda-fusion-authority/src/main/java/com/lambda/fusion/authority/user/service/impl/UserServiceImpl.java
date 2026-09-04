package com.lambda.fusion.authority.user.service.impl;

import static com.lambda.fusion.core.FusionConstants.ROLE_DEV;

import cn.dev33.satoken.stp.StpLogic;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateField;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.lambda.cloud.core.utils.ConvertUtils;
import com.lambda.cloud.core.utils.StpLogicUtils;
import com.lambda.fusion.authority.AuthorityProperties;
import com.lambda.fusion.authority.exception.AuthorityBusinessException;
import com.lambda.fusion.authority.organization.model.SimpleOrganization;
import com.lambda.fusion.authority.organization.model.entity.UserOrganizationEntity;
import com.lambda.fusion.authority.organization.service.OrganizationService;
import com.lambda.fusion.authority.role.mapper.RoleMapper;
import com.lambda.fusion.authority.role.model.SimpleRole;
import com.lambda.fusion.authority.user.assembler.UserDetailAssembler;
import com.lambda.fusion.authority.user.mapper.*;
import com.lambda.fusion.authority.user.model.*;
import com.lambda.fusion.authority.user.model.entity.*;
import com.lambda.fusion.authority.user.service.UserService;
import com.lambda.fusion.authority.utils.AuthorityHelper;
import com.lambda.fusion.authority.utils.PasswordGenerator;
import com.lambda.fusion.authority.utils.UserInfoConverter;
import com.lambda.fusion.core.FusionConstants;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.security.web.form.FormLockingStrategy;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@SuppressFBWarnings("EI_EXPOSE_REP2")
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthorityProperties properties;
    private final UserInfoMapper userInfoMapper;
    private final UserFieldsMapper userFieldsMapper;
    private final UserOrganizationMapper userOrganizationMapper;
    private final UserPasswordMapper userUpdatePwdLogMapper;
    private final OrganizationService organizationService;
    private final FormLockingStrategy formLockingStrategy;
    private final UserDetailAssembler userDetailAssembler;

    /***
     * @param username 用户账号
     * @return {@link boolean}
     **/
    @Override
    public boolean checkUserName(String username) {
        return userMapper.hasExists(username);
    }

    @Override
    public List<String> getUserNamesByAuthority(String orgId, @NotBlank String authority) {
        if (!authority.equals(FusionConstants.ROLE_MANAGER)) {
            orgId = null;
        }
        return userMapper.selectUsernamesByAuthority(orgId, authority);
    }

    @Override
    public List<String> getUserNamesByOrgId(String orgId, Integer type) {
        return userMapper.selectUsernameByOrgId(orgId, type);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<User> getAllUsers() {
        return userMapper.selectAllUsers();
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public User getByUsername(String username) {
        if (username == null) {
            throw AuthorityBusinessException.invalidParameter("username不能为空");
        }
        User user = userMapper.selectUserByUsername(username);
        if (user != null) {
            user.setOnline(userDetailAssembler.isOnline(username));
            user.setLocked(user.isLocked());
            UserInfoEntity userInfoEntity = userInfoMapper.getProps(username);
            if (userInfoEntity != null) {
                UserInfo userInfo = ConvertUtils.convert(userInfoEntity);
                user.setProps(userInfo);
            }
            List<UserFieldsEntity> fields = userFieldsMapper.getListByUsername(username);
            Map<String, Map<String, Object>> allPersonUserMap;
            if (CollectionUtils.isNotEmpty(fields)) {
                allPersonUserMap = UserInfoConverter.buildUserFieldsMap(fields);
                user.setPersonal(allPersonUserMap.get(username));
            }
        }
        return user;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Override
    public User getCurrentUser(UserDetails userDetails) {
        User user = this.getByUsername(userDetails.getName());
        UserInfo props = user.getProps();
        // 已带强制改密标记的用户无需再计算密码时效
        if (props != null && Boolean.FALSE.equals(props.getPasswordResetRequired())) {
            PasswordExpiry expiry = evaluatePasswordExpiry(userDetails);
            if (expiry.isResetRequired()) {
                props.setPasswordResetRequired(true);
                props.setPasswordModifyDays(expiry.getUsedDays());
                user.setProps(props);
            }
        }
        return user;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Override
    public PasswordExpiry evaluatePasswordExpiry(UserDetails userDetails) {
        PasswordExpiry expiry = new PasswordExpiry();
        // 未启用密码有效期策略
        if (!Boolean.TRUE.equals(properties.getPasswordStrategy().getEnablePeriodChange())) {
            return expiry;
        }
        // 豁免角色：dev / admin / tenantManager 不参与密码有效期
        boolean exempted = userDetails.isDev() || userDetails.isAdmin() || userDetails.isTenantManager();
        if (exempted) {
            return expiry;
        }
        List<UserPasswordEntity> userUpdatePwdLogEntities =
                userUpdatePwdLogMapper.selectList(new LambdaQueryWrapper<UserPasswordEntity>()
                        .select(UserPasswordEntity::getUpdateTime)
                        .eq(UserPasswordEntity::getUsername, userDetails.getName())
                        .isNotNull(UserPasswordEntity::getUpdateTime)
                        .orderByDesc(UserPasswordEntity::getUpdateTime));
        // 无密码变更记录（存量用户）视为未过期，自下一次密码变更起计时
        if (CollectionUtils.isEmpty(userUpdatePwdLogEntities)) {
            return expiry;
        }
        UserPasswordEntity userUpdatePwdLogEntity = userUpdatePwdLogEntities.getFirst();
        Integer passwordModifyDays = properties.getPasswordStrategy().getPeriodChangeDays();
        LocalDateTime nowTime = LocalDateTime.now();
        LocalDateTime lastUpdateTime = DateUtil.toLocalDateTime(userUpdatePwdLogEntity.getUpdateTime());
        long days = ChronoUnit.DAYS.between(lastUpdateTime, nowTime);
        expiry.setUsedDays((int) days);
        if (days >= passwordModifyDays) {
            expiry.setResetRequired(true);
            expiry.setRemainDays(0);
            // 落库保持 /authority/users/current 与 /auth/userinfo 各出口一致
            userInfoMapper.updateStatus(userDetails.getName(), true);
        } else {
            // 仅在提醒窗口内返回剩余天数（窗口外为 -1，前端据此不提醒）
            int remain = (int) (passwordModifyDays - days);
            Integer remindDays = properties.getPasswordStrategy().getRemindDays();
            if (remindDays == null || remain > remindDays) {
                expiry.setRemainDays(-1);
            } else {
                expiry.setRemainDays(remain);
            }
        }
        return expiry;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<User> getUsers(List<User> users) {
        if (CollectionUtils.isEmpty(users)) {
            return users;
        }
        return userMapper.selectUsers(users);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Page<User> getUsers(Page<User> pagination, UserQueryContext userQueryContext) {
        String tenantId = userQueryContext.getTenantId();

        // 执行分页查询
        pagination = userMapper.selectUserPage(pagination, userQueryContext);
        List<User> users = pagination.getRecords();

        if (CollectionUtils.isNotEmpty(users)) {
            // 补充用户详细信息
            List<User> enrichedUsers = userDetailAssembler.populateUserDetails(users, tenantId);
            pagination.setRecords(enrichedUsers);
        }

        return pagination;
    }

    @Override
    public List<String> getUsernamesByOrgId(String orgId, String roleId) {
        return userMapper.selectUsernamesByOrg(orgId, roleId);
    }

    @Override
    public Set<String> getPermissions(UserDetails operator, String source) {
        return userMapper.selectUserPermissions(source);
    }

    @Override
    public void batchSavePermissions(UserDetails operator, String username, Set<String> permissions) {
        List<UserRoleEntity> userRoleEntities = permissions.stream()
                .map(permission -> {
                    UserRoleEntity userRoleEntity = new UserRoleEntity();
                    userRoleEntity.setUsername(username);
                    userRoleEntity.setTenantId(AuthUtils.getTenantId());
                    userRoleEntity.setAuthority(permission);
                    return userRoleEntity;
                })
                .collect(Collectors.toList());
        userRoleMapper.insert(userRoleEntities);
    }

    @Override
    public List<Permission> getUserPermissions(String username, String mode) {
        if (username == null) {
            throw AuthorityBusinessException.invalidParameter("username不能为空");
        }
        User user = userMapper.selectUserByUsername(username);
        if (user == null) {
            throw AuthorityBusinessException.userNotFound(username);
        }
        List<String> ids = Lists.newArrayList(username);
        List<SimpleRole> authorities = user.getAuthorities();
        if (CollectionUtils.isNotEmpty(authorities)) {
            boolean admin = authorities.stream().anyMatch(e -> ROLE_DEV.equals(e.getAuthority()));
            if (!admin) {
                for (SimpleRole role : authorities) {
                    ids.add(role.getAuthority());
                }
            } else {
                ids = Collections.emptyList();
            }
        }
        return userMapper.selectUserPermissionsByIdsAndMode(ids, mode);
    }

    @CacheEvict(value = "LAResourceOwners", allEntries = true)
    @Override
    public void addUser(CreateUser createUser, UserDetails userDetails) {
        UserEntity userEntity = createUser.toEntity();
        if (userEntity == null) {
            throw AuthorityBusinessException.invalidParameter("用户信息不能为空");
        }

        boolean hasExists = userMapper.hasExists(userEntity.getUsername());
        if (hasExists) {
            throw AuthorityBusinessException.userNameExists(userEntity.getUsername());
        }

        AuthorityProperties.PasswordStrategy strategy = properties.getPasswordStrategy();
        String originPassword = userEntity.getPassword();
        Password encodePassword = PasswordGenerator.obtainPassword(strategy, originPassword);
        userEntity.setPassword(passwordEncoder.encode(encodePassword.getEncrypted()));

        if (AuthorityHelper.isTenant(createUser.getAuthorities()) && createUser.getOrganization() == null) {
            String orgId = createUser.getTenantId();
            createUser.setOrganization(new SimpleOrganization(orgId));
        }
        Date now = new Date();
        if (userEntity.getExpiredTime() == null) {
            userEntity.setExpiredTime(DateUtil.date(now).offset(DateField.YEAR, 99));
        }
        userEntity.setCreatedAt(now);

        String tenantId = getTenantId(createUser, userDetails);
        userEntity.setTenantId(tenantId);
        userEntity.setCreatedBy(userDetails.getName());
        userMapper.insert(userEntity);
        recordPasswordLog(userEntity.getUsername(), userEntity.getPassword());

        List<SimpleRole> roles = createUser.getAuthorities();
        assignRolesToUser(tenantId, userEntity.getUsername(), roles);

        if (Objects.isNull(createUser.getProps())) {
            createUser.setProps(new UserInfo());
        }

        UserInfoEntity userInfoEntity = createUser.getProps().toEntity();
        userInfoEntity.setUsername(userEntity.getUsername());
        userInfoMapper.insert(userInfoEntity);

        SimpleOrganization simpleOrganization = createUser.getOrganization();
        if (simpleOrganization != null && StringUtils.isNotBlank(simpleOrganization.getId())) {
            userOrganizationMapper.insert(
                    new UserOrganizationEntity(userEntity.getUsername(), simpleOrganization.getId(), tenantId));
        }
    }

    private static String getTenantId(CreateUser createUser, UserDetails userDetails) {
        return (userDetails.isAnyManager() && StrUtil.isNotEmpty(createUser.getTenantId()))
                ? createUser.getTenantId()
                : AuthUtils.getTenantId();
    }

    private void assignRolesToUser(String tenantId, String username, List<SimpleRole> roles) {
        if (CollectionUtils.isNotEmpty(roles)) {
            List<UserRoleEntity> userRoleEntities = roles.stream()
                    .map(SimpleRole::getAuthority)
                    .filter(StrUtil::isNotEmpty)
                    .map(authority -> {
                        if (authority.startsWith(FusionConstants.ROLE_TENANT)) {
                            authority = FusionConstants.ROLE_TENANT;
                        } else if (!roleMapper.hasExists(authority)) {
                            throw AuthorityBusinessException.roleNotFound(authority);
                        }
                        UserRoleEntity userRoleEntity = new UserRoleEntity();
                        userRoleEntity.setAuthority(authority);
                        userRoleEntity.setTenantId(tenantId);
                        userRoleEntity.setUsername(username);
                        return userRoleEntity;
                    })
                    .collect(Collectors.toList());
            userRoleMapper.insert(userRoleEntities);
        }
    }

    @CacheEvict(value = "LAResourceOwners", allEntries = true)
    @Override
    public void updateUser(UpdateUser updateUser, UserDetails userDetails) {
        UserEntity userEntity = updateUser.toEntity();
        int updated = userMapper.updateById(userEntity);
        if (updated != 1) {
            throw AuthorityBusinessException.systemError("用户更新失败");
        }
        UserInfo updateUserProps = updateUser.getProps();
        if (updateUserProps != null) {
            UserInfoEntity userPropsEntity = updateUserProps.toEntity();
            userPropsEntity.setUsername(userEntity.getUsername());
            userInfoMapper.insertOrUpdate(userPropsEntity);
        }

        if (MapUtils.isNotEmpty(updateUser.getPersonal())) {
            List<UserFieldsEntity> fields =
                    UserInfoConverter.buildUserFieldsFromMap(updateUser.getPersonal(), userEntity.getUsername());
            this.userFieldsMapper.deleteByUsername(userEntity.getUsername());
            this.userFieldsMapper.insert(fields);
        }

        String tenantId = AuthUtils.getTenantId();

        if (!AuthorityHelper.isTenant(updateUser.getAuthorities())) {
            SimpleOrganization simpleOrganization = updateUser.getOrganization();
            if (simpleOrganization != null) {
                UserOrganizationEntity organizationEntity =
                        userOrganizationMapper.selectUserOrganization(userEntity.getUsername());
                if (organizationEntity != null) {
                    if (!StrUtil.equals(organizationEntity.getTenantId(), simpleOrganization.getId())) {
                        organizationEntity.setOrganizationId(simpleOrganization.getId());
                        userOrganizationMapper.update(
                                organizationEntity,
                                new LambdaUpdateWrapper<UserOrganizationEntity>()
                                        .eq(UserOrganizationEntity::getUsername, userEntity.getUsername()));
                    }
                } else {
                    userOrganizationMapper.insert(
                            new UserOrganizationEntity(userEntity.getUsername(), simpleOrganization.getId(), tenantId));
                }
            }
        }

        userRoleMapper.deleteUserRoles(userEntity.getUsername());
        this.assignRolesToUser(tenantId, userEntity.getUsername(), updateUser.getAuthorities());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(UserDetails operator, String username) {
        if (username == null) {
            throw AuthorityBusinessException.invalidParameter("username不能为空");
        }
        if (username.equals(operator.getName())) {
            throw AuthorityBusinessException.operationNotSupported(
                    "操作失败：用户名 " + username + " 不能等于当前登录用户 " + operator.getName());
        }
        boolean exists = userMapper.hasExists(username);
        if (!exists) {
            throw AuthorityBusinessException.userNotFound(username);
        }
        userMapper.deleteUser(username);
        userRoleMapper.deleteUserRoles(username);
        roleMapper.deleteResourceRoleByAuthority(username);
        userOrganizationMapper.deleteUserOrganizationByUser(username);
        userFieldsMapper.deleteByUsername(username);
        // todo 删除用户数据权限

    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<User> getUsersByKey(String key) {
        if (key == null) {
            throw AuthorityBusinessException.invalidParameter("key不能为空");
        }
        return userMapper.selectUsersByKey(key);
    }

    @Override
    public void updateUserPassword(String username, String oldPassword, String newPassword) {
        UserEntity userEntity = userMapper.selectById(username);
        if (userEntity == null) {
            throw AuthorityBusinessException.userNotFound(username);
        }
        boolean isChecked = passwordEncoder.matches(oldPassword, userEntity.getPassword());
        if (!isChecked) {
            throw AuthorityBusinessException.originalPasswordError();
        }
        String encoded = passwordEncoder.encode(newPassword);
        recordPasswordLog(username, encoded);
        userMapper.updatePassword(username, encoded);
        userInfoMapper.updateStatus(username, false);
        StpLogic activeStpLogic = StpLogicUtils.getActiveStpLogic();
        activeStpLogic.logout(username);
    }

    @Override
    public String resetUserPassword(ResetPassword resetPassword) {
        AuthorityProperties.PasswordStrategy strategy = properties.getPasswordStrategy();
        Password password = PasswordGenerator.obtainPassword(strategy, resetPassword.getNewPassword());
        String encoded = passwordEncoder.encode(password.getEncrypted());
        userMapper.updatePassword(resetPassword.getUsername(), encoded);
        recordPasswordLog(resetPassword.getUsername(), encoded);
        UserInfoEntity userInfoEntity = new UserInfoEntity();
        userInfoEntity.setUsername(resetPassword.getUsername());
        userInfoEntity.setUpdatePwd(true);
        int count = userInfoMapper.updateStatus(resetPassword.getUsername(), true);
        if (0 == count) {
            userInfoMapper.insert(userInfoEntity);
        }
        StpLogic activeStpLogic = StpLogicUtils.getActiveStpLogic();
        activeStpLogic.logout(resetPassword.getUsername());
        return password.getEncrypted();
    }

    /**
     * 记录密码变更日志（密码有效期的计时依据：取最近一条 UPDATE_TIME）
     * @param username 用户账号
     * @param encodedPassword 编码后的密码
     */
    private void recordPasswordLog(String username, String encodedPassword) {
        UserPasswordEntity passwordLog = new UserPasswordEntity();
        passwordLog.setId(IdUtil.fastSimpleUUID());
        passwordLog.setUsername(username);
        passwordLog.setPassword(encodedPassword);
        userUpdatePwdLogMapper.insertLog(passwordLog);
    }

    @Override
    public void deactivateUser(UserDetails operator, Integer type, String username) {
        if (username == null) {
            throw AuthorityBusinessException.invalidParameter("username不能为空");
        }
        User user = userMapper.selectUserByUsername(username);
        if (user == null) {
            throw AuthorityBusinessException.userNotFound(username);
        }
        if (operator.getName().equals(username)) {
            throw AuthorityBusinessException.operationNotSupported(
                    "操作失败：用户名 " + username + " 不能等于当前登录用户 " + operator.getName());
        }
        userMapper.deactivateUser(type, username);
    }

    @Override
    public void unlockUser(String username, UserDetails operator) {
        formLockingStrategy.unlock(username);
    }

    @Override
    public List<UserProfile> getUserProfiles(UserDetails operator, List<String> orgIds) {
        return userMapper.selectUserProfiles(AuthUtils.getTenantId(), orgIds);
    }

    @Override
    public Set<String> getSubOrganizationIds(String orgId, UserDetails operator) {
        Set<String> orgIds = new HashSet<>();
        if (StringUtils.isNotBlank(orgId)) {
            orgIds.add(orgId);
            orgIds.addAll(organizationService.getChildrenById(orgId));
        } else {
            orgIds.addAll(
                    operator.isAdmin() ? Collections.emptyList() : organizationService.getSubOrganizations(operator));
        }
        return orgIds;
    }

    @Override
    public Map<String, UserInfoEntity> getUserProps(Set<String> names) {
        List<UserInfoEntity> userInfoEntity = userInfoMapper.selectByIds(names);
        Map<String, UserInfoEntity> userInfoMap = Maps.newHashMap();
        if (CollectionUtils.isNotEmpty(userInfoEntity)) {
            userInfoEntity.forEach(item -> userInfoMap.put(item.getUsername(), item));
        }
        return userInfoMap;
    }

    @Override
    public List<String> getSuperiors(String uid, Integer rank) {
        return List.of();
    }

    /**
     * 增加用户 新增字段信息
     *
     * @param personal 字段map
     * @param username 用户id
     */
    @Override
    public void addUserFields(Map<String, Object> personal, String username) {
        if (personal == null) {
            throw AuthorityBusinessException.invalidParameter("用户扩展信息不能为空");
        }
        List<UserFieldsEntity> fieldsEntities = UserInfoConverter.buildUserFieldsFromMap(personal, username);
        userFieldsMapper.insert(fieldsEntities);
    }

    @Override
    public List<User> queryTenantAdmins(String tenantId) {
        if (tenantId == null) {
            throw AuthorityBusinessException.invalidParameter("租户id不能为空");
        }
        return userMapper.selectUsersByTenantId(tenantId);
    }

    @Override
    public void updateTenantUser(User user, UserDetails operator) {
        String username = user.getUsername();
        boolean hasExists = userMapper.hasExists(username);
        if (!hasExists) {
            throw AuthorityBusinessException.userNotFound(username);
        }
        userMapper.updateUser(user);
        if (CollUtil.isNotEmpty(user.getAuthorities())) {
            userRoleMapper.deleteUserRoles(username);
            this.assignRolesToUser(user.getTenantId(), username, user.getAuthorities());
        }
    }

    @Override
    public void exportUsers(Page<User> pageable, UserQueryContext parameters) {}
}
