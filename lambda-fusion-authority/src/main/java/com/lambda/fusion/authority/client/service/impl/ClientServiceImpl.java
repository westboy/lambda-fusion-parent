package com.lambda.fusion.authority.client.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lambda.cloud.core.principal.LoginUser;
import com.lambda.fusion.authority.client.mapper.ClientMapper;
import com.lambda.fusion.authority.client.model.ClientEntity;
import com.lambda.fusion.authority.client.service.ClientService;
import com.lambda.fusion.authority.exception.AuthorityBusinessException;
import com.lambda.fusion.authority.resource.model.ApiPermissionTreeNode;
import com.lambda.fusion.authority.resource.model.UserPermission;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.permission.model.ApiPermissionMetadata;
import com.lambda.fusion.permission.service.ApiPermissionRegistry;
import com.lambda.security.exception.AuthenticationException;
import com.lambda.security.exception.UsernameNotFoundException;
import com.lambda.security.service.HmacClientService;
import com.lambda.security.web.hmac.model.HmacClient;
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
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings("EI_EXPOSE_REP2")
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class ClientServiceImpl extends ServiceImpl<ClientMapper, ClientEntity>
        implements ClientService, HmacClientService {
    private static final String DEFAULT_GROUP = "未分组";

    private final ApiPermissionRegistry apiPermissionRegistry;

    @Override
    @CacheEvict(value = "Clients", key = "#entity.id")
    public boolean updateById(ClientEntity entity) {
        return super.updateById(entity);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Cacheable(value = "Clients", key = "#appid")
    public LoginUser loadClientByAppid(String appid) {
        HmacClient client = this.baseMapper.getClientById(appid);
        if (client == null) {
            throw new UsernameNotFoundException("Client " + appid + " not found.");
        }
        return client;
    }

    @Override
    public LoginUser loginByUsername(String username, String loginType) throws AuthenticationException {
        return null;
    }

    @Override
    public List<UserPermission> getClientPermissions(List<String> permissionIds) {
        return baseMapper.getClientPermissions(permissionIds);
    }

    @Override
    public List<ApiPermissionTreeNode> listApiPermissions(String clientId, String application, String keyword) {
        ensureClientExists(clientId);
        Set<String> boundPermissions = new LinkedHashSet<>(baseMapper.getBoundPermissionIds(clientId));
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

    @Caching(
            evict = {
                @CacheEvict(value = "ResourceOwners", allEntries = true),
                @CacheEvict(value = "auth:role", allEntries = true),
                @CacheEvict(value = "auth:permission", allEntries = true)
            })
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindApiPermission(UserDetails userDetails, String clientId, String permissionId) {
        ensureClientExists(clientId);
        if (StringUtils.isBlank(permissionId)) {
            throw AuthorityBusinessException.invalidParameter("接口权限ID不能为空");
        }
        if (!baseMapper.hasBound(clientId, permissionId)) {
            baseMapper.bind(clientId, permissionId);
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
    public void unbindApiPermission(UserDetails userDetails, String clientId, String permissionId) {
        ensureClientExists(clientId);
        if (StringUtils.isBlank(permissionId)) {
            throw AuthorityBusinessException.invalidParameter("接口权限ID不能为空");
        }
        baseMapper.unbind(clientId, permissionId);
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

    private void ensureClientExists(String clientId) {
        if (StringUtils.isBlank(clientId)) {
            throw AuthorityBusinessException.invalidParameter("客户端标识不能为空");
        }
        if (getById(clientId) == null) {
            throw AuthorityBusinessException.clientNotFound(clientId);
        }
    }
}
