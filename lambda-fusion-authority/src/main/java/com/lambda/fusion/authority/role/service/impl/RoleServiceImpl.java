package com.lambda.fusion.authority.role.service.impl;

import static com.lambda.fusion.authority.AuthorityConstants.DEFAULT_GROUP_NAME;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.lambda.fusion.authority.AuthorityConstants;
import com.lambda.fusion.authority.exception.AuthorityBusinessException;
import com.lambda.fusion.authority.resource.model.Resource;
import com.lambda.fusion.authority.resource.service.ResourceService;
import com.lambda.fusion.authority.role.mapper.AccessPermissionMapper;
import com.lambda.fusion.authority.role.mapper.RoleGroupMapper;
import com.lambda.fusion.authority.role.mapper.RoleMapper;
import com.lambda.fusion.authority.role.model.*;
import com.lambda.fusion.authority.role.model.entity.RoleEntity;
import com.lambda.fusion.authority.role.model.entity.RoleGroupEntity;
import com.lambda.fusion.authority.role.service.RoleService;
import com.lambda.fusion.authority.user.mapper.UserRoleMapper;
import com.lambda.fusion.authority.user.model.entity.UserRoleEntity;
import com.lambda.fusion.authority.utils.AuthorityHelper;
import com.lambda.fusion.core.FusionConstants;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.core.tree.builder.TreeBuilder;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@SuppressFBWarnings("EI_EXPOSE_REP2")
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class RoleServiceImpl implements RoleService {

    private final RoleMapper roleMapper;

    private final ResourceService resourceService;

    private final RoleGroupMapper roleGroupMapper;

    private final UserRoleMapper userRoleMapper;

    private final AccessPermissionMapper accessPermissionMapper;

    private @NonNull Set<String> getExcludes(UserDetails userDetails) {
        Set<String> excludes = Sets.newHashSet();
        excludes.add(FusionConstants.ROLE_USER);
        excludes.add(FusionConstants.ROLE_HMAC);
        excludes.add(FusionConstants.ROLE_SYSTEM);
        excludes.add(FusionConstants.ROLE_TENANT);
        if (!userDetails.isDev()) {
            excludes.add(FusionConstants.ROLE_DEV);
            if (!userDetails.isAdmin()) {
                excludes.add(FusionConstants.ROLE_ADMIN);
            }
        }
        return excludes;
    }

    @Override
    public List<Role> queryRoles(UserDetails userDetails) {
        Set<String> excludes = getExcludes(userDetails);
        Map<String, Object> parameters = Maps.newHashMapWithExpectedSize(2);
        parameters.put(FusionConstants.EXCLUDES, excludes);
        parameters.put(FusionConstants.TENANT_ID, userDetails.getTenantId());
        return roleMapper.queryRoles(parameters);
    }

    @Override
    public List<GroupRole> groupedRoles(UserDetails userDetails, String tenantId) {
        if (StringUtils.isBlank(tenantId) || StringUtils.isNotBlank(userDetails.getTenantId())) {
            tenantId = userDetails.getTenantId();
        }
        Set<String> excludes = getExcludes(userDetails);
        Map<String, Object> parameters = Maps.newHashMapWithExpectedSize(2);
        parameters.put(FusionConstants.EXCLUDES, excludes);
        parameters.put(FusionConstants.TENANT_ID, tenantId);
        List<Role> roles = roleMapper.queryRoles(parameters);
        List<RoleGroupEntity> groupEntities = roleGroupMapper.getAllGroup(parameters);
        RoleGroupEntity defaultRoleGroupEntity = newDefaultGroup(tenantId);
        if (notContains(groupEntities, defaultRoleGroupEntity)) {
            groupEntities.add(defaultRoleGroupEntity);
        }
        // Jin 如果当前用户的角色是开发工程师 只返回ROLE_DEV和ROLE_ADMIN
        final Map<String, List<Role>> map = roles.stream()
                .filter(mutableRole -> {
                    if (userDetails.isDev()) {
                        return (mutableRole.getAuthority().equals(FusionConstants.ROLE_DEV)
                                || mutableRole.getAuthority().equals(FusionConstants.ROLE_ADMIN));
                    } else {
                        return true;
                    }
                })
                .collect(Collectors.groupingBy(Role::getGroupId));

        List<GroupRole> result = new ArrayList<>();
        groupEntities.forEach(item -> {
            GroupRole group = GroupRole.fromEntity(GroupRole.class, item);
            List<Role> children = map.get(group.getGroupId());
            if (CollectionUtils.isNotEmpty(children)) {
                group.setRoles(children);
                group.setDisableAssignment(false);
                group.setDisableOperations(false);
            } else {
                group.setDisableAssignment(false);
                group.setDisableOperations(true);
            }
            result.add(group);
        });
        return result;
    }

    @Override
    public Page<Role> queryRoles(Page<Role> pageable, Map<String, Object> parameters) {
        pageable = roleMapper.pageRoles(pageable, parameters);
        List<Role> roles = pageable.getRecords();
        if (CollectionUtils.isNotEmpty(roles)) {
            for (Role item : roles) {
                String authority = item.getAuthority();
                item.setBuiltIn(AuthorityConstants.DEFAULT_ROLES.contains(authority));
            }
        }
        return pageable;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(
            cacheNames = {"auth:role", "auth:permission"},
            allEntries = true)
    public Role updateRole(UserDetails userDetails, UpdateRole updateRole) {
        if (updateRole == null || updateRole.getAlias() == null) {
            throw AuthorityBusinessException.invalidParameter("别名不能为空！");
        }
        RoleEntity roleEntity = updateRole.toEntity();
        roleMapper.updateById(roleEntity);
        return getRoleByAuthority(updateRole.getAuthority());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Role saveRole(UserDetails userDetails, CreateRole createRole) {
        if (createRole == null || createRole.getAlias() == null) {
            throw AuthorityBusinessException.invalidParameter("别名不能为空！");
        }
        String tenantId = userDetails.getTenantId();
        String authority = IdUtil.fastSimpleUUID();
        createRole.setAuthority(authority);
        createRole.setOwner(tenantId);
        createRole.setTenantId(tenantId);
        String groupId = Optional.ofNullable(createRole.getGroupId()).orElse(AuthorityConstants.DEFAULT);
        createRole.setGroupId(groupId);
        RoleEntity roleEntity = createRole.toEntity();
        roleEntity.setCreatedBy(userDetails.getName());
        roleEntity.setCreatedAt(LocalDateTime.now());
        roleMapper.insert(roleEntity);
        return getRoleByAuthority(authority);
    }

    @Override
    public Role getRoleByAuthority(String authority) {
        if (authority == null) {
            throw AuthorityBusinessException.invalidParameter("角色标识不能为空");
        }
        if (authority.startsWith(FusionConstants.ROLE_TENANT)) {
            authority = FusionConstants.ROLE_TENANT;
        }
        return roleMapper.getRoleByAuthority(authority);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(
            cacheNames = {"auth:role", "auth:permission"},
            allEntries = true)
    public void deleteRoleById(String authority) {
        if (authority == null) {
            throw AuthorityBusinessException.invalidParameter("角色标识不能为空");
        }
        Set<String> excludes = new HashSet<>(AuthorityConstants.DEFAULT_ROLES);
        if (excludes.contains(authority)) {
            throw AuthorityBusinessException.operationNotSupported("角色" + authority + "不能删除");
        }
        Role role = getRoleByAuthority(authority);
        if (role == null) {
            throw AuthorityBusinessException.roleNotFound(authority);
        }
        authority = role.getAuthority();
        boolean hasUsedAuthority = hasUsedAuthority(authority);
        if (hasUsedAuthority) {
            throw AuthorityBusinessException.roleAssignedToUser(authority);
        }
        roleMapper.deleteRoleByAuthority(authority);
        roleMapper.deleteResourceRoleByAuthority(authority);
        roleMapper.deleteUserRoleByAuthority(authority);
    }

    @Override
    public boolean hasExists(String authority) {
        if (authority == null) {
            throw AuthorityBusinessException.invalidParameter("角色标识不能为空");
        }
        return roleMapper.hasExists(authority);
    }

    @Override
    public List<AccessPermission> getAccessPermission(
            UserDetails userDetails, String authority, Integer mode, String application) {
        if (authority == null) {
            throw AuthorityBusinessException.invalidParameter("角色标识不能为空");
        }
        Map<String, Object> parameters = Maps.newHashMapWithExpectedSize(4);
        parameters.put("authority", authority);
        parameters.put("mode", Optional.ofNullable(mode).orElse(0));
        parameters.put("application", application);
        if (!userDetails.isDev()) {
            Set<String> authorities = userDetails.getRoles();
            authorities.add(userDetails.getUsername());
            parameters.put("authorities", authorities);
        }
        List<AccessPermission> permissions = roleMapper.getAccessPermission(parameters);
        if (CollectionUtils.isEmpty(permissions)) {
            return Collections.emptyList();
        }
        return TreeBuilder.build(permissions);
    }

    @Caching(
            evict = {
                @CacheEvict(value = "ResourceOwners", allEntries = true),
                @CacheEvict(value = "auth:permission", allEntries = true)
            })
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void grantRolePermission(String authority, String resourceId, int status, UserDetails userDetails) {
        Resource resource = getResource(authority, resourceId, userDetails);

        String tenantId = userDetails.getTenantId();
        if (StringUtils.isBlank(tenantId)) {
            tenantId = AuthorityHelper.getTenantId(authority);
        }

        List<Resource> resources = resourceService.getAllParentsByOperator(userDetails, resource);
        List<Resource> children = resourceService.getAllChildrenByOperator(userDetails, resource);
        resources.add(resource);
        resources.addAll(children);
        Set<String> ids = resources.stream().map(Resource::getId).collect(Collectors.toSet());
        List<String> has = roleMapper.hasAuthorizedWithIntersection(authority, ids);
        Set<String> authorized = Sets.newHashSet(has);

        Set<String> intersection = Sets.intersection(ids, authorized);
        if (CollectionUtils.isNotEmpty(intersection)) {
            AuthorityPermission authorityPermission = new AuthorityPermission();
            authorityPermission.setAuthority(authority);
            authorityPermission.setIds(intersection);
            authorityPermission.setTenantId(tenantId);
            authorityPermission.setStatus(status);
            roleMapper.batchUpdateAuthorization(authorityPermission);
        }
        Set<String> diff = Sets.difference(ids, authorized);
        if (!CollectionUtils.isEmpty(diff)) {
            List<AuthorityPermission> list = new ArrayList<>(diff.size());
            for (String id : diff) {
                AuthorityPermission authorityPermission = new AuthorityPermission();
                authorityPermission.setAuthority(authority);
                authorityPermission.setTenantId(tenantId);
                authorityPermission.setStatus(status);
                authorityPermission.setId(id);
                list.add(authorityPermission);
            }
            roleMapper.batchSaveAuthorization(list);
        }
    }

    private @NonNull Resource getResource(String authority, String resourceId, UserDetails userDetails) {
        validateAuthorityOperation(authority, resourceId, userDetails);
        Resource resource = resourceService.getResourceById(resourceId);
        if (resource == null) {
            throw AuthorityBusinessException.resourceNotFound(resourceId);
        }
        return resource;
    }

    private static void validateAuthorityOperation(String authority, String resourceId, UserDetails userDetails) {
        if (authority == null) {
            throw AuthorityBusinessException.invalidParameter("角色标识不能为空");
        }
        if (resourceId == null) {
            throw AuthorityBusinessException.invalidParameter("资源id不能为空");
        }

        if (userDetails.isDev()) {
            return;
        }

        if (StpUtil.hasPermission(authority)) {
            throw AuthorityBusinessException.operationNotSupported("禁止修改当前用户所属角色的访问权限!");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Caching(
            evict = {
                @CacheEvict(value = "ResourceOwners", allEntries = true),
                @CacheEvict(value = "auth:permission", allEntries = true)
            })
    public void revokeRolePermission(String authority, String resourceId, UserDetails userDetails) {
        Resource resource = getResource(authority, resourceId, userDetails);

        String tenantId = userDetails.getTenantId();
        if (StringUtils.isBlank(tenantId)) {
            tenantId = AuthorityHelper.getTenantId(authority);
        }

        List<Resource> resources = resourceService.getAllChildrenByOperator(userDetails, resource);
        resources.add(resource);
        List<String> ids = resources.stream().map(Resource::getId).collect(Collectors.toList());
        if (CollectionUtils.isNotEmpty(ids)) {
            roleMapper.batchDeleteAuthorization(authority, ids, tenantId);
        }
        AuthorityConstants.MenuType menuType = AuthorityConstants.MenuType.of(resource.getResType());
        if (menuType != null && !menuType.isButton()) {
            List<Resource> parents = resourceService.getAllParentsByOperator(userDetails, resource);
            for (Resource parent : parents) {
                AuthorityPermission parameters = new AuthorityPermission();
                parameters.setId(parent.getId());
                parameters.setTenantId(tenantId);
                parameters.setAuthority(authority);
                boolean state = accessPermissionMapper.noAnyChildrenPermission(parameters);
                if (state) {
                    accessPermissionMapper.deletePermission(parameters);
                }
            }
        }
    }

    @Override
    public boolean hasUsedAuthority(String authority) {
        return roleMapper.hasUsedAuthority(authority);
    }

    @Override
    @CacheEvict(
            cacheNames = {"auth:role", "auth:permission"},
            allEntries = true)
    public void prohibitRole(int type, String authority) {
        if (authority == null) {
            throw AuthorityBusinessException.invalidParameter("角色标识不能为空");
        }
        Role role = getRoleByAuthority(authority);
        if (role == null) {
            throw AuthorityBusinessException.roleNotFound(authority);
        }
        roleMapper.prohibitRole(type, role.getAuthority());
    }

    @Override
    public List<Role> getTenantRolesByOwner(String owner) {
        return roleMapper.getTenantRolesByOwner(owner);
    }

    @Override
    public Group addGroup(Group group) {
        group.setGroupId(IdWorker.getIdStr());
        roleGroupMapper.insert(BeanUtil.copyProperties(group, RoleGroupEntity.class));
        return group;
    }

    @Override
    public void deleteGroup(String groupId) {
        if (Objects.equals(groupId, AuthorityConstants.DEFAULT)) {
            throw AuthorityBusinessException.operationNotSupported("默认组不能删除");
        }
        roleGroupMapper.updateRoleGroupId(groupId, AuthorityConstants.DEFAULT);
        roleGroupMapper.deleteById(groupId);
    }

    @Override
    public Group updateGroup(Group group) {
        roleGroupMapper.updateById(BeanUtil.copyProperties(group, RoleGroupEntity.class));
        return group;
    }

    @Override
    public Group getGroupById(String id) {
        RoleGroupEntity roleGroupEntity = roleGroupMapper.selectById(id);
        if (roleGroupEntity != null) {
            Group target = new Group();
            BeanUtil.copyProperties(roleGroupEntity, target);
            return target;
        }
        return null;
    }

    @Override
    public List<Group> listGroups(UserDetails userDetails) {
        String tenantId = userDetails.getTenantId();
        Map<String, Object> parameters = Maps.newHashMapWithExpectedSize(1);
        parameters.put(FusionConstants.TENANT_ID, tenantId);
        RoleGroupEntity defaultRoleGroupEntity = newDefaultGroup(tenantId);
        List<RoleGroupEntity> groupEntities = roleGroupMapper.getAllGroup(parameters);
        if (notContains(groupEntities, defaultRoleGroupEntity)) {
            groupEntities.add(defaultRoleGroupEntity);
        }
        return BeanUtil.copyToList(groupEntities, Group.class);
    }

    private boolean notContains(List<RoleGroupEntity> groupEntities, RoleGroupEntity defaultRoleGroupEntity) {
        return CollectionUtils.isEmpty(groupEntities)
                || (CollectionUtils.isNotEmpty(groupEntities) && !groupEntities.contains(defaultRoleGroupEntity));
    }

    private RoleGroupEntity newDefaultGroup(String tenantId) {
        RoleGroupEntity defaultRoleGroupEntity = new RoleGroupEntity();
        defaultRoleGroupEntity.setGroupId(AuthorityConstants.DEFAULT);
        defaultRoleGroupEntity.setGroupName(DEFAULT_GROUP_NAME);
        defaultRoleGroupEntity.setTenantId(tenantId);
        return defaultRoleGroupEntity;
    }

    @Caching(
            evict = {
                @CacheEvict(value = "ResourceOwners", allEntries = true),
                @CacheEvict(value = "auth:role", allEntries = true),
                @CacheEvict(value = "auth:permission", allEntries = true)
            })
    @Override
    public void assignUsersToRole(UserDetails userDetails, BatchAssignUserRole req) {
        final String authority = req.getRoleId();
        final List<String> usernames = req.getUsername();
        final List<UserRoleEntity> dbResult = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRoleEntity>().eq(UserRoleEntity::getAuthority, req.getRoleId()));
        final Set<String> dbUsernames =
                dbResult.stream().map(UserRoleEntity::getUsername).collect(Collectors.toSet());
        if (CollectionUtils.isEmpty(usernames)) {
            // 如果传入为空，则删除所有
            if (CollectionUtils.isNotEmpty(dbUsernames)) {
                userRoleMapper.batchDelete(authority, new ArrayList<>(dbUsernames));
            }
            return;
        }

        // 1. 计算需要添加的用户 (usernames - dbUsernames)
        List<String> toAdd = new ArrayList<>(usernames);
        toAdd.removeAll(dbUsernames);

        // 2. 计算需要删除的用户 (dbUsernames - usernames)
        List<String> toDelete = new ArrayList<>(dbUsernames);
        toDelete.removeAll(usernames);

        // 3. 执行添加
        if (CollectionUtils.isNotEmpty(toAdd)) {
            final String tenantId = userDetails.getTenantId();
            final List<UserRoleEntity> saveList = new ArrayList<>(toAdd.size());
            toAdd.forEach(username -> saveList.add(new UserRoleEntity(username, authority, tenantId)));
            userRoleMapper.insert(saveList);
        }

        // 4. 执行删除
        if (CollectionUtils.isNotEmpty(toDelete)) {
            userRoleMapper.batchDelete(authority, toDelete);
        }
    }
}
