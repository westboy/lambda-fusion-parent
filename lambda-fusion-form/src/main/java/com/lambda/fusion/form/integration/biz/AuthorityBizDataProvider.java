package com.lambda.fusion.form.integration.biz;

import com.lambda.fusion.authority.organization.model.Organization;
import com.lambda.fusion.authority.organization.model.OrganizationQuery;
import com.lambda.fusion.authority.organization.service.OrganizationService;
import com.lambda.fusion.authority.user.model.entity.UserInfoEntity;
import com.lambda.fusion.authority.user.service.UserInfoService;
import com.lambda.fusion.form.runtime.biz.BizDataProvider;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

/**
 * authority 模块的 biz 数据适配：机构树取 OrganizationService，用户检索走 UserInfoService。
 * 仅暴露 id/名称最小视图——运行时表单页的调用方不应看到手机号等敏感字段。
 *
 * @author Zx
 */
@SuppressFBWarnings("EI_EXPOSE_REP2")
@RequiredArgsConstructor
public class AuthorityBizDataProvider implements BizDataProvider {

    /** 用户检索默认条数（下拉按需，不做深分页） */
    private static final int USER_SEARCH_LIMIT = 20;

    private final OrganizationService organizationService;
    private final UserInfoService userInfoService;
    private final com.lambda.fusion.authority.user.mapper.UserOrganizationMapper userOrganizationMapper;
    private final com.lambda.fusion.authority.role.service.RoleService roleService;

    @Override
    public List<Map<String, Object>> organizationTree() {
        List<Organization> organizations = organizationService.organizationTreeList(new OrganizationQuery());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Organization organization : organizations) {
            result.add(organizationNode(organization));
        }
        return result;
    }

    private Map<String, Object> organizationNode(Organization organization) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", organization.getId());
        node.put("name", organization.getName());
        node.put("parentId", organization.getParentId());
        List<Map<String, Object>> children = new ArrayList<>();
        if (organization.getChildren() != null) {
            for (Organization child : organization.getChildren()) {
                children.add(organizationNode(child));
            }
        }
        node.put("children", children);
        return node;
    }

    @Override
    public List<Map<String, Object>> searchUsers(String keyword, int pageSize, String orgId) {
        // 机构过滤：先取该机构下的用户名集合，空集合直接无结果
        java.util.Set<String> orgUsernames = null;
        if (StringUtils.hasText(orgId)) {
            orgUsernames = userOrganizationMapper
                    .selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                                    com.lambda.fusion.authority.organization.model.entity.UserOrganizationEntity>()
                            .eq(
                                    com.lambda.fusion.authority.organization.model.entity.UserOrganizationEntity
                                            ::getOrganizationId,
                                    orgId))
                    .stream()
                    .map(com.lambda.fusion.authority.organization.model.entity.UserOrganizationEntity::getUsername)
                    .collect(java.util.stream.Collectors.toSet());
            if (orgUsernames.isEmpty()) {
                return List.of();
            }
        }
        int limit = Math.min(Math.max(pageSize, 1), USER_SEARCH_LIMIT);
        final java.util.Set<String> scope = orgUsernames;
        final boolean byKeyword = StringUtils.hasText(keyword);
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserInfoEntity user : userInfoService
                .lambdaQuery()
                .and(wrapper -> {
                    // 空关键词=打开下拉时的初始列表（不下推 LIKE）；有关键词按昵称/账号模糊匹配
                    if (byKeyword) {
                        wrapper.like(UserInfoEntity::getDdNick, keyword)
                                .or()
                                .like(UserInfoEntity::getUsername, keyword);
                    }
                    if (scope != null) {
                        wrapper.and(w -> w.in(UserInfoEntity::getUsername, scope));
                    }
                })
                .last("LIMIT " + limit)
                .list()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("username", user.getUsername());
            node.put("nickName", user.getDdNick());
            result.add(node);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> roles() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (com.lambda.fusion.authority.role.model.Role role :
                roleService.queryRoles(com.lambda.fusion.core.utils.AuthUtils.getUser())) {
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
        if ("ORG".equals(type)) {
            // 机构树整棵打平后按 id 过滤（机构数量可控，一次查询）
            Map<String, String> flat = new LinkedHashMap<>();
            java.util.Deque<Organization> pending =
                    new java.util.ArrayDeque<>(organizationService.organizationTreeList(new OrganizationQuery()));
            while (!pending.isEmpty()) {
                Organization org = pending.poll();
                flat.put(org.getId(), org.getName());
                if (org.getChildren() != null) {
                    pending.addAll(org.getChildren());
                }
            }
            return flat.entrySet().stream()
                    .filter(entry -> ids.contains(entry.getKey()))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        if ("ROLE".equals(type)) {
            Map<String, String> flat = new LinkedHashMap<>();
            for (com.lambda.fusion.authority.role.model.Role role :
                    roleService.queryRoles(com.lambda.fusion.core.utils.AuthUtils.getUser())) {
                flat.put(role.getAuthority(), role.getAlias());
            }
            return flat.entrySet().stream()
                    .filter(entry -> ids.contains(entry.getKey()))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        if ("USER".equals(type)) {
            // 用户名即 id：批量 IN 查昵称，缺昵称回退账号
            return userInfoService.lambdaQuery().in(UserInfoEntity::getUsername, ids).list().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            UserInfoEntity::getUsername,
                            user -> user.getDdNick() == null || user.getDdNick().isBlank()
                                    ? user.getUsername()
                                    : user.getDdNick(),
                            (existing, replacement) -> existing));
        }
        return Map.of();
    }
}
