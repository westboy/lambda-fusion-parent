package com.lambda.fusion.form.integration.biz;

import com.lambda.fusion.authority.api.RemoteOrg;
import com.lambda.fusion.authority.api.RemoteOrganizationService;
import com.lambda.fusion.authority.api.RemotePage;
import com.lambda.fusion.authority.api.RemoteRole;
import com.lambda.fusion.authority.api.RemoteRoleService;
import com.lambda.fusion.authority.api.RemoteUser;
import com.lambda.fusion.authority.api.RemoteUserService;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.runtime.biz.BizDataProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * authority 远程契约的 biz 数据适配：经 {@code lambda-fusion-authority-api} 的三个 Remote 服务
 * 取机构/用户/角色（工程契约 §7.3，不引用 authority 实现类）。
 *
 * <p>部署形态：单体中注入本地适配器 Bean；authority 独立部署时由装配方传入 Dubbo 引用；
 * 服务缺席（null）时各方法降级为空结果，表单不报错。
 *
 * @author Zx
 */
@Slf4j
@RequiredArgsConstructor
public class RemoteBizDataProvider implements BizDataProvider {

    /** 用户检索默认条数（下拉按需，不做深分页） */
    private static final int USER_SEARCH_LIMIT = 20;

    private final RemoteOrganizationService organizationService;
    private final RemoteUserService userService;
    private final RemoteRoleService roleService;

    @Override
    public List<Map<String, Object>> organizationTree() {
        if (organizationService == null) {
            return List.of();
        }
        List<RemoteOrg> organizations = organizationService.listOrganizations(currentTenant(), null);
        if (organizations == null || organizations.isEmpty()) {
            return List.of();
        }
        // 扁平列表按 parentId 客户端嵌套：父不在结果集内的视为根
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        for (RemoteOrg organization : organizations) {
            nodes.put(organization.getId(), organizationNode(organization));
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (RemoteOrg organization : organizations) {
            Map<String, Object> node = nodes.get(organization.getId());
            String parentId = organization.getParentId();
            Map<String, Object> parent = parentId == null || parentId.isBlank() ? null : nodes.get(parentId);
            if (parent == null || parent == node) {
                roots.add(node);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get("children");
                children.add(node);
            }
        }
        return roots;
    }

    private Map<String, Object> organizationNode(RemoteOrg organization) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", organization.getId());
        node.put("name", organization.getName());
        node.put("parentId", organization.getParentId());
        node.put("children", new ArrayList<Map<String, Object>>());
        return node;
    }

    @Override
    public List<Map<String, Object>> searchUsers(String keyword, int pageSize, String orgId) {
        if (userService == null) {
            return List.of();
        }
        int limit = Math.clamp(pageSize, 1, USER_SEARCH_LIMIT);
        RemotePage<RemoteUser> page = userService.pageUsers(currentTenant(), keyword, orgId, 1, limit);
        List<Map<String, Object>> result = new ArrayList<>();
        for (RemoteUser user : page == null ? List.<RemoteUser>of() : page.getRows()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("username", user.getUsername());
            node.put("nickName", user.getNickname());
            result.add(node);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> roles() {
        if (roleService == null) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (RemoteRole role : roleService.listRoles(currentTenant(), null)) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("authority", role.getAuthority());
            node.put("name", role.getAlias());
            result.add(node);
        }
        return result;
    }

    @Override
    public Map<String, String> labels(String type, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return switch (type) {
            case "ORG" -> labelMap(organizationTree(), ids);
            case "ROLE" -> {
                if (roleService == null) {
                    yield Map.of();
                }
                Map<String, String> flat = new LinkedHashMap<>();
                for (RemoteRole role : roleService.listRoles(currentTenant(), null)) {
                    flat.put(role.getAuthority(), role.getAlias());
                }
                yield filterIds(flat, ids);
            }
            case "USER" -> userService == null ? Map.of() : userService.mapNicknames(ids);
            default -> Map.of();
        };
    }

    /** 机构树打平后按 id 过滤（机构数量可控，一次取整树） */
    private Map<String, String> labelMap(List<Map<String, Object>> tree, Collection<String> ids) {
        Map<String, String> flat = new LinkedHashMap<>();
        List<Map<String, Object>> pending = new ArrayList<>(tree);
        while (!pending.isEmpty()) {
            Map<String, Object> node = pending.removeFirst();
            Object name = node.get("name");
            flat.put(String.valueOf(node.get("id")), name == null ? null : String.valueOf(name));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
            if (children != null) {
                pending.addAll(children);
            }
        }
        return filterIds(flat, ids);
    }

    private Map<String, String> filterIds(Map<String, String> flat, Collection<String> ids) {
        return flat.entrySet().stream()
                .filter(entry -> ids.contains(entry.getKey()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** 当前租户（选择器场景必有登录态）；无租户上下文时降级 null（按框架默认租户口径） */
    private String currentTenant() {
        try {
            return AuthUtils.getTenantId();
        } catch (Exception e) {
            log.debug("无租户上下文，biz 数据按默认租户口径查询");
            return null;
        }
    }
}
