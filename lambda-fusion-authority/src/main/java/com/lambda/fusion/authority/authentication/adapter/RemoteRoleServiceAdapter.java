package com.lambda.fusion.authority.authentication.adapter;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.fusion.authority.AuthorityConstants;
import com.lambda.fusion.authority.api.RemoteRole;
import com.lambda.fusion.authority.api.RemoteRoleService;
import com.lambda.fusion.authority.role.model.Role;
import com.lambda.fusion.authority.role.service.RoleService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link RemoteRoleService} 实现：基于本地 {@link RoleService} 列举启用角色，经 Dubbo 暴露给其他服务。
 *
 * @author jin
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class RemoteRoleServiceAdapter implements RemoteRoleService {

    private final RoleService roleService;

    @Override
    public List<RemoteRole> listRoles(String tenantId, String keyword) {
        Map<String, Object> parameters = new java.util.HashMap<>();
        if (tenantId != null) {
            parameters.put(AuthorityConstants.TENANT_ID, tenantId);
        }
        // 角色量小，一次取足后在内存过滤；分页由消费方自行截取
        return roleService.queryRoles(new Page<>(1, 500), parameters).getRecords().stream()
                .filter(role -> matchKeyword(role, keyword))
                .map(role -> new RemoteRole(role.getAuthority(), role.getAlias()))
                .toList();
    }

    private boolean matchKeyword(Role role, String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return true;
        }
        return StrUtil.containsIgnoreCase(role.getAuthority(), keyword)
                || StrUtil.containsIgnoreCase(role.getAlias(), keyword);
    }
}
