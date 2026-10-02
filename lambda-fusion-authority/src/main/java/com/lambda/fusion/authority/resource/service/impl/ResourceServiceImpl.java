package com.lambda.fusion.authority.resource.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.lambda.fusion.authority.AuthorityConstants;
import com.lambda.fusion.authority.authentication.model.MenuQuery;
import com.lambda.fusion.authority.exception.AuthorityBusinessException;
import com.lambda.fusion.authority.exception.AuthorityErrorCode;
import com.lambda.fusion.authority.resource.mapper.ResourceMapper;
import com.lambda.fusion.authority.resource.model.CreateResource;
import com.lambda.fusion.authority.resource.model.MoveResource;
import com.lambda.fusion.authority.resource.model.Resource;
import com.lambda.fusion.authority.resource.model.ResourceTree;
import com.lambda.fusion.authority.resource.model.entity.ResourceEntity;
import com.lambda.fusion.authority.resource.service.ResourceService;
import com.lambda.fusion.authority.resource.support.AuthorizationCache;
import com.lambda.fusion.authority.role.mapper.RoleMapper;
import com.lambda.fusion.authority.role.model.UserAuthority;
import com.lambda.fusion.core.FusionConstants;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.core.tree.builder.TreeBuilder;
import com.lambda.fusion.core.tree.filter.TreeDataFilter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class ResourceServiceImpl implements ResourceService {
    private final RoleMapper roleMapper;
    private final ResourceMapper resourceMapper;
    protected final TreeDataFilter treeDataFilter;
    private final AuthorizationCache authorizationCache;

    @Override
    public List<Resource> getAllResources() {
        return resourceMapper.getAllResourcesByOrderNo();
    }

    @Override
    public List<ResourceTree> getResourceTree(MenuQuery menuQuery) {
        List<Resource> resources = resourceMapper.queryAvailableResources(menuQuery);
        if (CollectionUtils.isEmpty(resources)) {
            return new ArrayList<>();
        }
        List<ResourceTree> list = new ArrayList<>(resources.size());
        resources.forEach(v -> {
            ResourceTree tree = new ResourceTree();
            BeanUtils.copyProperties(v, tree);
            list.add(tree);
        });
        final List<ResourceTree> resourceTreeList = treeDataFilter.filter(
                list,
                menuQuery.getName(),
                ResourceTree::getResName,
                ResourceTree::getId,
                ResourceTree::getParentKeys,
                target -> target.stream()
                        .sorted(Comparator.comparing(ResourceTree::getResLevel).thenComparing(ResourceTree::getOrderNo))
                        .collect(Collectors.toList()));
        return TreeBuilder.build(resourceTreeList);
    }

    @Override
    public List<ResourceTree> getResourceTree(String id) {
        List<ResourceTree> resourceTrees = resourceMapper.getDirectChildren(id);
        if (CollectionUtils.isNotEmpty(resourceTrees)) {
            for (ResourceTree resourceTree : resourceTrees) {
                List<ResourceTree> children = getResourceTree(resourceTree.getId());
                if (CollectionUtils.isNotEmpty(children)) {
                    resourceTree.setChildren(children);
                }
            }
        }
        return resourceTrees;
    }

    @Override
    public List<Resource> getParentResources(String id) {
        Resource resource = resourceMapper.getResourceById(id);
        if (resource != null) {
            List<Resource> list = new ArrayList<>();
            list.add(resource);
            if (resource.getParentId() != null) {
                List<Resource> parents = getParentResources(resource.getParentId());
                if (parents != null) {
                    list.addAll(parents);
                }
            }
            return list;
        }
        return new ArrayList<>();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Resource addResource(CreateResource createResource) {
        lockResourceTree();
        Resource resource = createResource.toEntity();
        requireFreePath(resource);
        int type = createResource.getResType();
        String id = IdUtil.getSnowflakeNextIdStr();
        if (type == 0) {
            if (StringUtils.isNotBlank(createResource.getMethod())) {
                id = createResource.getMethod();
            } else {
                id = IdWorker.getIdStr();
            }
        }
        resource.setId(id);
        Resource parent;
        String parentKeys = StringUtils.EMPTY;
        int resLevel = 0;
        if (StringUtils.isNotBlank(resource.getParentId())) {
            parent = getResourceById(resource.getParentId());
            if (parent == null) {
                throw AuthorityBusinessException.resourceNotFound(resource.getParentId());
            }
            if (!parent.getResMode().equals(resource.getResMode())) {
                throw AuthorityBusinessException.resourceTypeNotSupported();
            }
            resLevel = parent.getResLevel() + 1;
            parentKeys = parent.getParentKeys();
            if (StringUtils.isNotBlank(parentKeys)) {
                parentKeys += (FusionConstants.SEPARATOR0 + parent.getId());
            } else {
                parentKeys = parent.getId();
            }
        }
        if (Objects.equals(resource.getResType(), AuthorityConstants.MenuType.BUTTON.getCode())) {
            resLevel = Integer.MAX_VALUE;
        }
        resource.setResLevel(resLevel);
        resource.setParentKeys(parentKeys);

        if (StringUtils.isBlank(resource.getResPath())) {
            resource.setResPath(null);
        }
        if (StringUtils.isBlank(resource.getIcon())) {
            resource.setIcon(null);
        }
        List<ResourceTree> directChildren = resourceMapper.getDirectChildren(resource.getParentId());
        Objects.requireNonNull(directChildren);
        resource.setOrderNo(directChildren.size() + 1);
        resourceMapper.addResource(resource);
        List<CreateResource.Button> buttons = createResource.getButtons();
        if (CollectionUtils.isNotEmpty(buttons)) {
            for (int i = 0; i < buttons.size(); i++) {
                Resource button = new Resource();
                BeanUtils.copyProperties(buttons.get(i), button);
                button.setId(UUID.fastUUID().toString());
                button.setParentId(resource.getId());
                button.setParentKeys(resource.getParentKeys() + FusionConstants.SEPARATOR0 + resource.getId());
                button.setOrderNo(i + 1);
                button.setResType(AuthorityConstants.MenuType.BUTTON.getCode());
                button.setResLevel(resLevel + 1);
                resourceMapper.addResource(button);
            }
        }

        changeResourceOrdered(directChildren);
        authorizationCache.evictAfterCommit();
        return resourceMapper.getResourceById(resource.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteResource(String id) {
        lockResourceTree();
        if (id == null) {
            throw AuthorityBusinessException.invalidParameter("资源ID不能为空");
        }
        Resource resource = getResourceById(id);
        if (resource == null) {
            throw AuthorityBusinessException.resourceNotFound(id);
        }

        List<Resource> children = queryAvailableChildren(resource);
        children.addFirst(resource);
        Set<String> ids = children.stream().map(Resource::getId).collect(Collectors.toSet());
        resourceMapper.deleteApiBindings(ids);
        resourceMapper.deleteRolesResource(ids);
        resourceMapper.deleteResource(ids);

        List<ResourceTree> children2 = resourceMapper.getDirectChildren(resource.getParentId());
        changeResourceOrdered(children2);
        authorizationCache.evictAfterCommit();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Resource updateResource(Resource updateResource) {
        lockResourceTree();
        if (updateResource == null) {
            throw AuthorityBusinessException.invalidParameter("资源信息不能为空");
        }
        if (updateResource.getId() == null) {
            throw AuthorityBusinessException.invalidParameter("资源ID不能为空");
        }
        Resource source = getResourceById(updateResource.getId());
        if (source == null) {
            throw AuthorityBusinessException.resourceNotFound(updateResource.getId());
        }
        if (updateResource.getResMode() == null) updateResource.setResMode(source.getResMode());
        if (updateResource.getResType() == null) updateResource.setResType(source.getResType());
        requireFreePath(updateResource);
        // 更新时只更新属性，不改变上下级关系，因此parentKeys也无须变化
        updateResource.setResLevel(source.getResLevel());
        updateResource.setParentKeys(source.getParentKeys());
        // 更新时如果没有传顺序号,则不修改顺序值
        if (updateResource.getOrderNo() == 0) {
            updateResource.setOrderNo(source.getOrderNo());
        }
        boolean orderChanged = !source.getOrderNo().equals(updateResource.getOrderNo());
        boolean typeChanged = !source.getResType().equals(updateResource.getResType());
        boolean hiddenChanged = !Objects.equals(source.getHidden(), updateResource.getHidden());
        if (typeChanged) {
            if (Objects.equals(updateResource.getResType(), AuthorityConstants.MenuType.BUTTON.getCode())) {
                updateResource.setResLevel(Integer.MAX_VALUE);
            }
            String parentId = source.getParentId();
            Resource parent;
            if (StringUtils.isNotBlank(parentId)) {
                parent = getResourceById(parentId);
                if (parent == null) {
                    throw AuthorityBusinessException.resourceNotFound(parentId);
                }
            }
        }
        BeanUtil.copyProperties(updateResource, source);
        resourceMapper.updateResource(source);

        if (orderChanged) {
            List<Resource> directChildren = new ArrayList<>(resourceMapper.getDirectChildren(source.getParentId()));
            directChildren.removeIf(item -> source.getId().equals(item.getId()));
            directChildren.add(Math.max(0, Math.min(updateResource.getOrderNo() - 1, directChildren.size())), source);
            changeResourceOrdered(directChildren);
        }
        if (hiddenChanged) {
            List<ResourceTree> directChildren = resourceMapper.getDirectChildren(source.getId());
            if (CollectionUtils.isNotEmpty(directChildren)) {
                resourceMapper.updateResourceIsHidden(directChildren, updateResource.getHidden());
            }
        }
        authorizationCache.evictAfterCommit();
        return source;
    }

    @Override
    public Resource getResourceById(String id) {
        if (id == null) {
            throw AuthorityBusinessException.invalidParameter("资源ID不能为空");
        }
        return resourceMapper.getResourceById(id);
    }

    @Override
    public void getAllChildren(String id, List<Resource> results) {
        List<ResourceTree> children = resourceMapper.getDirectChildren(id);
        if (CollectionUtils.isNotEmpty(children)) {
            results.addAll(children);
            for (ResourceTree child : children) {
                getAllChildren(child.getId(), results);
            }
        }
    }

    @Override
    public void getAllParents(String id, List<Resource> results) {
        if (StringUtils.isBlank(id)) {
            return;
        }
        Resource resource = resourceMapper.getResourceById(id);
        if (resource != null) {
            results.addFirst(resource);
            if (StringUtils.isNotBlank(resource.getParentId())) {
                getAllParents(resource.getParentId(), results);
            }
        }
    }

    @Override
    public List<Resource> queryAvailableChildren(@NotNull Resource target) {
        if (target.getId() == null) {
            throw AuthorityBusinessException.invalidParameter("资源ID不能为空");
        }
        String parentKeys = generateParentKeys(target.getParentKeys(), target.getId());
        List<ResourceTree> resourceTrees = resourceMapper.queryAllChildren(parentKeys);
        if (CollectionUtils.isNotEmpty(resourceTrees)) {
            int size = resourceTrees.size();
            List<Resource> list = new ArrayList<>(size);
            list.addAll(resourceTrees);
            return list;
        }
        return new ArrayList<>();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void move(@NotNull MoveResource parameter) {
        lockResourceTree();
        String id = parameter.getId();
        String tid = parameter.getTid();
        int type = parameter.getType();
        if (id == null) {
            throw AuthorityBusinessException.invalidParameter("资源ID不能为空");
        }
        if (tid == null) {
            throw AuthorityBusinessException.invalidParameter("目标资源ID不能为空");
        }
        Resource resource = resourceMapper.getResourceById(id);
        Resource target = resourceMapper.getResourceById(tid);
        String pid0 = resource.getParentId();
        String pid1 = target.getParentId();
        boolean peer = isPeer(pid0, pid1, type);

        // 需要改变排序号的资源列表
        List<Resource> changed = new ArrayList<>();
        // 需要改变 parentKeys 的资源列表
        List<Resource> changed2 = new ArrayList<>();
        String parentKeys = generateParentKeys(resource.getParentKeys(), resource.getId());
        switch (parameter.getType()) {
            case 0:
                handler0(resource, target, changed, changed2, parentKeys);
                break;
            case 1:
                handler1(resource, target, changed, changed2, pid1, parentKeys, peer);
                break;
            case 2:
                handler2(resource, target, changed, changed2, pid1, parentKeys, peer);
                break;
            default:
                break;
        }
        resourceMapper.updateMovedResource(resource);
        if (CollectionUtils.isNotEmpty(changed)) {
            resourceMapper.updateResourceOrdered(changed);
        }
        if (CollectionUtils.isNotEmpty(changed2)) {
            resourceMapper.updateResourceParentKeys(changed2);
            resourceMapper.updateResourceLevel(changed2);
        }
        if (!peer) {
            List<ResourceTree> children = resourceMapper.getDirectChildren(pid0);
            changeResourceOrdered(children);
        }
        authorizationCache.evictAfterCommit();
    }

    private void requireFreePath(Resource resource) {
        if (Objects.equals(resource.getResType(), AuthorityConstants.MenuType.BUTTON.getCode())
                || StringUtils.isBlank(resource.getResPath())) return;
        String path = resource.getResPath().trim().replaceAll("/+", "/");
        if (path.length() > 1) path = StringUtils.removeEnd(path, "/");
        final String normalized = path;
        boolean occupied = resourceMapper
                .selectList(new LambdaQueryWrapper<ResourceEntity>()
                        .eq(ResourceEntity::getResMode, resource.getResMode())
                        .ne(ResourceEntity::getResType, AuthorityConstants.MenuType.BUTTON.getCode())
                        .ne(resource.getId() != null, ResourceEntity::getId, resource.getId())
                        .isNotNull(ResourceEntity::getResPath))
                .stream()
                .anyMatch(
                        other -> StringUtils.removeEnd(other.getResPath().trim().replaceAll("/+", "/"), "/")
                                .equalsIgnoreCase(StringUtils.removeEnd(normalized, "/")));
        if (occupied) throw new AuthorityBusinessException(AuthorityErrorCode.RESOURCE_PATH_CONFLICT, path);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockResourceTree() {
        if (resourceMapper.lockResourceTree() == null) {
            throw AuthorityBusinessException.operationNotSupported("资源管理尚未初始化");
        }
    }

    private void handler2(
            Resource resource,
            Resource target,
            List<Resource> changed,
            List<Resource> changed2,
            String pid1,
            String parentKeys,
            boolean peer) {
        int n = 1;
        resource.setParentId(pid1);
        resource.setResLevel(target.getResLevel());
        List<ResourceTree> children = resourceMapper.getDirectChildren(pid1);
        for (Resource item : children) {
            item.setOrderNo(n);
            if (item.getId().equals(target.getId())) {
                resource.setOrderNo(n + 1);
                n++;
            }
            if (!item.getId().equals(resource.getId())) {
                changed.add(item);
                n++;
            }
        }
        if (!peer) {
            List<ResourceTree> children2 = resourceMapper.queryAllChildren(parentKeys);
            String replacement = target.getParentKeys();
            parentKeysAndRankHandler(resource, target, replacement, children2, changed2);
        }
    }

    private void handler1(
            Resource resource,
            Resource target,
            List<Resource> changed,
            List<Resource> changed2,
            String pid1,
            String parentKeys,
            boolean peer) {
        int n = 1;
        resource.setParentId(pid1);
        resource.setResLevel(target.getResLevel());
        List<ResourceTree> children = resourceMapper.getDirectChildren(pid1);
        for (Resource item : children) {
            if (item.getId().equals(target.getId())) {
                resource.setOrderNo(n);
                n++;
            }
            if (!item.getId().equals(resource.getId())) {
                item.setOrderNo(n);
                changed.add(item);
                n++;
            }
        }
        if (!peer) {
            List<ResourceTree> children2 = resourceMapper.queryAllChildren(parentKeys);
            String replacement = target.getParentKeys();
            parentKeysAndRankHandler(resource, target, replacement, children2, changed2);
        }
    }

    private void handler0(
            Resource resource, Resource target, List<Resource> changed, List<Resource> changed2, String parentKeys) {
        resource.setParentId(target.getId());
        resource.setOrderNo(1);
        resource.setResLevel(target.getResLevel() + 1);
        List<ResourceTree> children = resourceMapper.getDirectChildren(target.getId());
        if (CollectionUtils.isNotEmpty(children)) {
            for (int i = 0; i < children.size(); i++) {
                children.get(i).setOrderNo(2 + i);
            }
            changed.addAll(children);
        }
        List<ResourceTree> children2 = resourceMapper.queryAllChildren(parentKeys);
        String replacement = generateParentKeys(target.getParentKeys(), target.getId());
        parentKeysAndRankHandler(resource, target, replacement, children2, changed2);
    }

    /***
     * 处理需要更新parentKeys属性的对象
     */
    private void parentKeysAndRankHandler(
            Resource resource,
            Resource target,
            String replacement,
            List<ResourceTree> children2,
            List<Resource> changed2) {
        log.trace("target：{}", target);
        if (CollectionUtils.isNotEmpty(children2)) {
            String searchString = resource.getParentKeys();
            String result;
            for (ResourceTree item : children2) {
                if (StringUtils.isNotBlank(searchString)) {
                    if (StringUtils.isNotBlank(replacement)) {
                        result = StringUtils.replace(item.getParentKeys(), searchString, replacement);
                    } else {
                        result = StringUtils.removeStart(
                                item.getParentKeys(), searchString + FusionConstants.SEPARATOR0);
                    }
                } else if (StringUtils.isNotBlank(replacement)) {
                    result = replacement + FusionConstants.SEPARATOR0 + item.getParentKeys();
                } else {
                    result = item.getParentKeys();
                }
                item.setParentKeys(result);
                item.setResLevel(StringUtils.split(result, FusionConstants.SEPARATOR0).length);
                changed2.add(item);
            }
        }
        resource.setParentKeys(replacement);
    }

    @Override
    public List<Resource> getAllChildrenByOperator(@NonNull UserDetails userDetails, @NonNull Resource resource) {
        String parentKeys = resource.getParentKeys();
        if (StringUtils.isNotBlank(parentKeys)) {
            parentKeys = resource.getParentKeys() + FusionConstants.SEPARATOR0 + resource.getId();
        } else {
            parentKeys = resource.getId();
        }
        Map<String, Object> parameters = getParameters(userDetails, parentKeys);
        return resourceMapper.getAllChildren(parameters);
    }

    private Map<String, Object> getParameters(UserDetails userDetails, Object parentKeys) {
        Map<String, Object> parameters = Maps.newHashMap();
        parameters.put("parentKeys", parentKeys);
        if (!userDetails.isDev()) {
            Set<String> authorities = getAuthoritiesByUsername(userDetails.getUsername());
            authorities.add(userDetails.getUsername());
            parameters.put("authorities", authorities);
        }
        return parameters;
    }

    @Override
    public List<Resource> getAllParentsByOperator(@NonNull UserDetails userDetails, @NonNull Resource resource) {
        String parentKeys = resource.getParentKeys();
        if (StringUtils.isNotBlank(parentKeys)) {
            List<String> ids = Arrays.asList(parentKeys.split(FusionConstants.SEPARATOR0));
            Map<String, Object> parameters = getParameters(userDetails, ids);
            return resourceMapper.getAllParents(parameters);
        }
        return new ArrayList<>();
    }

    /**
     * 改变资源列表顺序
     */
    private void changeResourceOrdered(List<? extends Resource> treeList) {
        List<Resource> changed = new ArrayList<>(treeList.size());
        int n = 1;
        for (Resource item : treeList) {
            if (item.getOrderNo() != n) {
                item.setOrderNo(n);
                changed.add(item);
            }
            n++;
        }
        if (CollectionUtils.isNotEmpty(changed)) {
            resourceMapper.updateResourceOrdered(changed);
        }
    }

    /***
     * 是否要移动的对象与目标对象是否为同级别
     */
    private boolean isPeer(String pid0, String pid1, int type) {
        if (type == 0) {
            return false;
        }
        if (StringUtils.isBlank(pid0) && StringUtils.isBlank(pid1)) {
            return true;
        } else if (StringUtils.isNotBlank(pid0) && StringUtils.isBlank(pid1)) {
            return false;
        } else if (StringUtils.isNotBlank(pid1) && StringUtils.isBlank(pid0)) {
            return false;
        } else {
            return pid0.equals(pid1);
        }
    }

    @Nonnull
    public Set<String> getAuthoritiesByUsername(String username) {
        List<UserAuthority> results = roleMapper.getUserAuthorityByUsername(username);
        if (CollectionUtils.isEmpty(results)) {
            return Sets.newHashSet();
        }
        Set<String> authorities = Sets.newHashSetWithExpectedSize(results.size());
        results.forEach(item -> {
            String authority = item.getAuthority();
            String orgId = item.getOrgId();
            if (FusionConstants.ROLE_TENANT.equals(item.getAuthority())) {
                authority = FusionConstants.ROLE_TENANT + FusionConstants.AT + orgId;
            }
            authorities.add(authority);
        });
        return authorities;
    }

    /***
     * 生成parentKeys
     */
    private String generateParentKeys(String parentKeys, String id) {
        return StringUtils.isNotBlank(parentKeys) ? parentKeys + FusionConstants.SEPARATOR0 + id : id;
    }
}
