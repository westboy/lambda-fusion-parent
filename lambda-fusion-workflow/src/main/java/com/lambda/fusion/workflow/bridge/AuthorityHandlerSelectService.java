package com.lambda.fusion.workflow.bridge;

import cn.hutool.core.util.StrUtil;
import com.lambda.fusion.authority.api.RemoteOrg;
import com.lambda.fusion.authority.api.RemoteOrganizationService;
import com.lambda.fusion.authority.api.RemotePage;
import com.lambda.fusion.authority.api.RemoteRole;
import com.lambda.fusion.authority.api.RemoteRoleService;
import com.lambda.fusion.authority.api.RemoteUser;
import com.lambda.fusion.authority.api.RemoteUserService;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.workflow.WorkflowConstants;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.dromara.warm.flow.core.dto.FlowPage;
import org.dromara.warm.flow.ui.dto.HandlerQuery;
import org.dromara.warm.flow.ui.service.HandlerSelectService;
import org.dromara.warm.flow.ui.vo.HandlerAuth;
import org.dromara.warm.flow.ui.vo.HandlerFeedBackVo;
import org.dromara.warm.flow.ui.vo.HandlerSelectVo;

/**
 * 设计器办理人选择：authority 远程用户/角色/组织 → 办理人弹窗。
 *
 * <p>storageId 原样落节点 permission_flag（多个 @@ 拼接），与 FusionPermissionHandler 产出等值匹配，
 * 因此必须带 {@link WorkflowConstants} 前缀：user: / role: / org:。
 *
 * @author jin
 */
@Slf4j
public class AuthorityHandlerSelectService implements HandlerSelectService {

    private static final String TYPE_USER = "用户";
    private static final String TYPE_ROLE = "角色";
    private static final String TYPE_ORG = "部门";

    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 200;

    private final RemoteUserService remoteUserService;
    private final RemoteRoleService remoteRoleService;
    private final RemoteOrganizationService remoteOrganizationService;

    public AuthorityHandlerSelectService(
            RemoteUserService remoteUserService,
            RemoteRoleService remoteRoleService,
            RemoteOrganizationService remoteOrganizationService) {
        this.remoteUserService = remoteUserService;
        this.remoteRoleService = remoteRoleService;
        this.remoteOrganizationService = remoteOrganizationService;
    }

    @Override
    public List<String> getHandlerType() {
        return List.of(TYPE_USER, TYPE_ROLE, TYPE_ORG);
    }

    @Override
    public HandlerSelectVo getHandlerSelect(HandlerQuery query) {
        HandlerSelectVo vo = new HandlerSelectVo();
        if (query == null || StrUtil.isBlank(query.getHandlerType())) {
            vo.setHandlerAuths(new FlowPage<>(Collections.emptyList(), 0));
            return vo;
        }
        String keyword = StrUtil.blankToDefault(query.getHandlerName(), query.getHandlerCode());
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? DEFAULT_PAGE_NUM : query.getPageNum();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1
                ? DEFAULT_PAGE_SIZE
                : Math.min(query.getPageSize(), MAX_PAGE_SIZE);

        List<HandlerAuth> rows = Collections.emptyList();
        long total = 0;
        switch (query.getHandlerType()) {
            case TYPE_USER -> {
                if (remoteUserService != null) {
                    RemotePage<RemoteUser> page = safe(
                            () -> remoteUserService.pageUsers(currentTenant(), keyword, pageNum, pageSize),
                            () -> new RemotePage<RemoteUser>(0, Collections.emptyList()));
                    total = page.getTotal();
                    rows = page.getRows().stream().map(this::toUserAuth).toList();
                }
            }
            case TYPE_ROLE -> {
                if (remoteRoleService != null) {
                    List<RemoteRole> roles =
                            safe(() -> remoteRoleService.listRoles(currentTenant(), keyword), Collections::emptyList);
                    total = roles.size();
                    rows = pageInMemory(roles.stream().map(this::toRoleAuth).toList(), pageNum, pageSize);
                }
            }
            case TYPE_ORG -> {
                if (remoteOrganizationService != null) {
                    List<RemoteOrg> orgs = safe(
                            () -> remoteOrganizationService.listOrganizations(currentTenant(), keyword),
                            Collections::emptyList);
                    total = orgs.size();
                    rows = pageInMemory(orgs.stream().map(this::toOrgAuth).toList(), pageNum, pageSize);
                }
            }
            default -> log.warn("未知办理人类型：{}", query.getHandlerType());
        }
        vo.setHandlerAuths(new FlowPage<>(rows, (int) total));
        return vo;
    }

    @Override
    public List<HandlerFeedBackVo> handlerFeedback(List<String> storageIds) {
        if (storageIds == null || storageIds.isEmpty()) {
            return Collections.emptyList();
        }
        String tenant = currentTenant();
        Map<String, RemoteUser> userMap = remoteUserService == null
                ? Collections.emptyMap()
                : storageIds.stream()
                        .filter(id -> id != null && id.startsWith(WorkflowConstants.PERMISSION_PREFIX_USER))
                        .map(id -> id.substring(WorkflowConstants.PERMISSION_PREFIX_USER.length()))
                        .distinct()
                        .map(username -> safe(() -> remoteUserService.getByUsername(username), () -> null))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toMap(RemoteUser::getUsername, Function.identity()));
        Map<String, RemoteRole> roleMap = remoteRoleService == null
                ? Collections.emptyMap()
                : this.<List<RemoteRole>>safe(() -> remoteRoleService.listRoles(tenant, null), Collections::emptyList)
                        .stream()
                        .collect(Collectors.toMap(RemoteRole::getAuthority, Function.identity(), (a, b) -> a));
        Map<String, RemoteOrg> orgMap = remoteOrganizationService == null
                ? Collections.emptyMap()
                : this.<List<RemoteOrg>>safe(
                                () -> remoteOrganizationService.listOrganizations(tenant, null), Collections::emptyList)
                        .stream()
                        .collect(Collectors.toMap(RemoteOrg::getId, Function.identity(), (a, b) -> a));

        List<HandlerFeedBackVo> result = new ArrayList<>();
        for (String storageId : storageIds) {
            if (storageId == null) {
                continue;
            }
            String name = null;
            if (storageId.startsWith(WorkflowConstants.PERMISSION_PREFIX_USER)) {
                RemoteUser user = userMap.get(storageId.substring(WorkflowConstants.PERMISSION_PREFIX_USER.length()));
                name = user == null ? null : StrUtil.blankToDefault(user.getNickname(), user.getUsername());
            } else if (storageId.startsWith(WorkflowConstants.PERMISSION_PREFIX_ROLE)) {
                RemoteRole role = roleMap.get(storageId.substring(WorkflowConstants.PERMISSION_PREFIX_ROLE.length()));
                name = role == null ? null : StrUtil.blankToDefault(role.getAlias(), role.getAuthority());
            } else if (storageId.startsWith(WorkflowConstants.PERMISSION_PREFIX_ORG)) {
                RemoteOrg org = orgMap.get(storageId.substring(WorkflowConstants.PERMISSION_PREFIX_ORG.length()));
                name = org == null ? null : org.getName();
            }
            result.add(new HandlerFeedBackVo(storageId, name));
        }
        return result;
    }

    private HandlerAuth toUserAuth(RemoteUser user) {
        return new HandlerAuth()
                .setStorageId(WorkflowConstants.PERMISSION_PREFIX_USER + user.getUsername())
                .setHandlerCode(user.getUsername())
                .setHandlerName(StrUtil.blankToDefault(user.getNickname(), user.getUsername()))
                .setGroupName(user.getOrgName())
                .setCreateTime(null);
    }

    private HandlerAuth toRoleAuth(RemoteRole role) {
        return new HandlerAuth()
                .setStorageId(WorkflowConstants.PERMISSION_PREFIX_ROLE + role.getAuthority())
                .setHandlerCode(role.getAuthority())
                .setHandlerName(StrUtil.blankToDefault(role.getAlias(), role.getAuthority()))
                .setGroupName(TYPE_ROLE)
                .setCreateTime(null);
    }

    private HandlerAuth toOrgAuth(RemoteOrg org) {
        return new HandlerAuth()
                .setStorageId(WorkflowConstants.PERMISSION_PREFIX_ORG + org.getId())
                .setHandlerCode(org.getId())
                .setHandlerName(org.getName())
                .setGroupName(TYPE_ORG)
                .setCreateTime(null);
    }

    private List<HandlerAuth> pageInMemory(List<HandlerAuth> all, int pageNum, int pageSize) {
        int from = Math.min((pageNum - 1) * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        return all.subList(from, to);
    }

    private String currentTenant() {
        try {
            return AuthUtils.getTenantId();
        } catch (Exception e) {
            log.debug("无租户上下文，设计器办理人列表降级为空");
            return null;
        }
    }

    /** authority 未就绪（RpcException 等）时按次降级为空 */
    private <T> T safe(Supplier<T> call, Supplier<T> emptySupplier) {
        try {
            T value = call.get();
            return value == null ? emptySupplier.get() : value;
        } catch (Exception e) {
            log.warn("authority 远程查询不可用，设计器办理人列表降级为空：{}", e.getMessage());
            return emptySupplier.get();
        }
    }
}
