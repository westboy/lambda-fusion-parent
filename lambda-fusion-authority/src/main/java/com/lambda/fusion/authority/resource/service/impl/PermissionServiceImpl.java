package com.lambda.fusion.authority.resource.service.impl;

import com.lambda.fusion.authority.exception.AuthorityBusinessException;
import com.lambda.fusion.authority.resource.mapper.ApiResourceMapper;
import com.lambda.fusion.authority.resource.model.ApiPermissionTreeNode;
import com.lambda.fusion.authority.resource.model.Resource;
import com.lambda.fusion.authority.resource.service.PermissionService;
import com.lambda.fusion.authority.resource.service.ResourceService;
import com.lambda.fusion.permission.model.ApiPermissionMetadata;
import com.lambda.fusion.permission.service.ApiPermissionRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@SuppressFBWarnings("EI_EXPOSE_REP2")
@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {
    private static final String DEFAULT_GROUP = "未分组";

    private final ResourceService resourceService;
    private final ApiResourceMapper apiResourceMapper;
    private final ApiPermissionRegistry apiPermissionRegistry;

    @Override
    public List<ApiPermissionTreeNode> listPermissionTree(String resourceId, String application, String keyword) {
        ensureResourceExists(resourceId);
        Set<String> boundPermissions = new LinkedHashSet<>(apiResourceMapper.getBoundPermissionIds(resourceId));
        List<String> applications = resolveApplications(application);
        if (CollectionUtils.isEmpty(applications)) {
            return List.of();
        }

        String normalizedKeyword = normalize(keyword);
        List<ApiPermissionTreeNode> roots = new ArrayList<>();
        for (String app : applications) {
            List<ApiPermissionMetadata> apis = apiPermissionRegistry.getReportedApis(app);
            List<ApiPermissionTreeNode> grouped = buildGroupNodes(app, apis, boundPermissions, normalizedKeyword);
            if (CollectionUtils.isEmpty(grouped)) {
                continue;
            }
            ApiPermissionTreeNode appNode = new ApiPermissionTreeNode();
            appNode.setId("app:" + app);
            appNode.setName(app);
            appNode.setType("application");
            appNode.setApplication(app);
            appNode.setChecked(grouped.stream().anyMatch(v -> Boolean.TRUE.equals(v.getChecked())));
            appNode.setChildren(grouped);
            roots.add(appNode);
        }
        return roots;
    }

    private List<ApiPermissionTreeNode> buildGroupNodes(
            String application, List<ApiPermissionMetadata> apis, Set<String> boundPermissions, String keyword) {
        if (CollectionUtils.isEmpty(apis)) {
            return List.of();
        }
        Map<String, Map<String, ApiPermissionTreeNode>> grouped = new LinkedHashMap<>();
        for (ApiPermissionMetadata api : apis) {
            if (api == null || CollectionUtils.isEmpty(api.getPermissions())) {
                continue;
            }
            if (!matchKeyword(api, keyword)) {
                continue;
            }
            String groupName = StringUtils.defaultIfBlank(api.getGroup(), DEFAULT_GROUP);
            Map<String, ApiPermissionTreeNode> permissions =
                    grouped.computeIfAbsent(groupName, k -> new LinkedHashMap<>());
            for (String permissionId : api.getPermissions()) {
                if (StringUtils.isBlank(permissionId) || permissions.containsKey(permissionId)) {
                    continue;
                }
                ApiPermissionTreeNode node = new ApiPermissionTreeNode();
                node.setId(permissionId);
                node.setParentId("app:" + application + ":group:" + groupName);
                node.setName(resolveNodeName(api, permissionId));
                node.setType("permission");
                node.setApplication(application);
                node.setGroup(groupName);
                node.setMethod(api.getMethod());
                node.setPath(api.getPath());
                node.setDescription(api.getDescription());
                node.setPermissionId(permissionId);
                node.setChecked(boundPermissions.contains(permissionId));
                permissions.put(permissionId, node);
            }
        }
        List<ApiPermissionTreeNode> groups = new ArrayList<>();
        grouped.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            List<ApiPermissionTreeNode> children = entry.getValue().values().stream()
                    .sorted(Comparator.comparing(ApiPermissionTreeNode::getName))
                    .toList();
            if (children.isEmpty()) {
                return;
            }
            ApiPermissionTreeNode groupNode = new ApiPermissionTreeNode();
            groupNode.setId("app:" + application + ":group:" + entry.getKey());
            groupNode.setParentId("app:" + application);
            groupNode.setName(entry.getKey());
            groupNode.setType("group");
            groupNode.setApplication(application);
            groupNode.setGroup(entry.getKey());
            groupNode.setChecked(children.stream().anyMatch(v -> Boolean.TRUE.equals(v.getChecked())));
            groupNode.setChildren(children);
            groups.add(groupNode);
        });
        return groups;
    }

    @Caching(
            evict = {
                    @CacheEvict(value = "ResourceOwners", allEntries = true),
                    @CacheEvict(value = "auth:role", allEntries = true),
                    @CacheEvict(value = "auth:permission", allEntries = true)
            })
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bind(String resourceId, String permissionId) {
        ensureResourceExists(resourceId);
        if (StringUtils.isBlank(permissionId)) {
            throw AuthorityBusinessException.invalidParameter("接口权限ID不能为空");
        }
        if (!apiResourceMapper.hasBound(resourceId, permissionId)) {
            apiResourceMapper.bind(resourceId, permissionId);
        }
    }

    @Caching(
            evict = {
                    @CacheEvict(value = "ResourceOwners", allEntries = true),
                    @CacheEvict(value = "auth:role", allEntries = true),
                    @CacheEvict(value = "auth:permission", allEntries = true)
            })
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unbind(String resourceId, String permissionId) {
        ensureResourceExists(resourceId);
        if (StringUtils.isBlank(permissionId)) {
            throw AuthorityBusinessException.invalidParameter("接口权限ID不能为空");
        }
        apiResourceMapper.unbind(resourceId, permissionId);
    }

    private String resolveNodeName(ApiPermissionMetadata api, String permissionId) {
        if (StringUtils.isNotBlank(api.getDescription())) {
            return api.getDescription();
        }
        String method = StringUtils.defaultIfBlank(api.getMethod(), "UNKNOWN");
        String path = StringUtils.defaultIfBlank(api.getPath(), "");
        if (StringUtils.isNotBlank(path)) {
            return method + " " + path;
        }
        return permissionId;
    }

    private void ensureResourceExists(String resourceId) {
        if (StringUtils.isBlank(resourceId)) {
            throw AuthorityBusinessException.invalidParameter("资源ID不能为空");
        }
        Resource resource = resourceService.getResourceById(resourceId);
        if (resource == null) {
            throw AuthorityBusinessException.resourceNotFound(resourceId);
        }
    }

    private List<String> resolveApplications(String application) {
        if (StringUtils.isNotBlank(application)) {
            List<String> applications = apiPermissionRegistry.getApplications();
            if (applications.contains(application)) {
                return List.of(application);
            }
            return List.of();
        }
        return apiPermissionRegistry.getApplications();
    }

    private boolean matchKeyword(ApiPermissionMetadata api, String keyword) {
        if (StringUtils.isBlank(keyword)) {
            return true;
        }
        return contains(api.getDescription(), keyword)
                || contains(api.getPath(), keyword)
                || contains(api.getMethod(), keyword)
                || contains(api.getController(), keyword)
                || contains(api.getMethodName(), keyword)
                || contains(api.getGroup(), keyword)
                || containsPermissions(api.getPermissions(), keyword);
    }

    private boolean containsPermissions(List<String> permissions, String keyword) {
        if (CollectionUtils.isEmpty(permissions)) {
            return false;
        }
        return permissions.stream().anyMatch(v -> contains(v, keyword));
    }

    private boolean contains(String value, String keyword) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        return normalize(value).contains(keyword);
    }

    private String normalize(String value) {
        return StringUtils.defaultString(value).toLowerCase(Locale.ROOT).trim();
    }
}
