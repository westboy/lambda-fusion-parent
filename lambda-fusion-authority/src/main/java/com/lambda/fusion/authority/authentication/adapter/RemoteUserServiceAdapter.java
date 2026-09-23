package com.lambda.fusion.authority.authentication.adapter;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.fusion.authority.api.RemotePage;
import com.lambda.fusion.authority.api.RemoteUser;
import com.lambda.fusion.authority.api.RemoteUserService;
import com.lambda.fusion.authority.organization.model.SimpleOrganization;
import com.lambda.fusion.authority.role.model.SimpleRole;
import com.lambda.fusion.authority.user.model.ThirdPartBinding;
import com.lambda.fusion.authority.user.model.User;
import com.lambda.fusion.authority.user.model.UserQueryContext;
import com.lambda.fusion.authority.user.service.UserService;
import com.lambda.fusion.authority.user.service.UserThirdPartService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link RemoteUserService} 实现：基于本地 {@link UserService} 装配用户详情，经 Dubbo 暴露给其他服务。
 *
 * <p>对齐 {@code AuthorityConfigure.DubboServiceConfiguration} 的 {@code RemoteAuthenticationService} 暴露模式。
 *
 * @author zx
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class RemoteUserServiceAdapter implements RemoteUserService {

    private final UserService userService;
    private final UserThirdPartService userThirdPartService;

    @Override
    public RemoteUser getByUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        User user = userService.getByUsername(username);
        if (user == null) {
            return null;
        }
        return toRemoteUser(user);
    }

    @Override
    public RemotePage<RemoteUser> pageUsers(String tenantId, String keyword, int pageNum, int pageSize) {
        UserQueryContext context = new UserQueryContext();
        if (tenantId != null) {
            context.setTenantId(tenantId);
        }
        // UserMapper 的 like 不自动包裹通配符，由调用方补 %
        context.setKeyword(keyword == null || keyword.isBlank() ? null : "%" + keyword + "%");
        Page<User> page = userService.getUsers(new Page<>(Math.max(pageNum, 1), Math.max(pageSize, 1)), context);
        List<RemoteUser> rows =
                page.getRecords().stream().map(this::toRemoteUser).toList();
        return new RemotePage<>(page.getTotal(), rows);
    }

    private RemoteUser toRemoteUser(User user) {
        RemoteUser remote = new RemoteUser();
        remote.setUsername(user.getUsername());
        remote.setNickname(user.getNickname());
        remote.setMobile(user.getMobile());
        remote.setEmail(user.getEmail());
        remote.setTenantId(user.getTenantId());
        remote.setEnabled(user.isEnabled());
        remote.setLocked(user.isLocked());
        remote.setExpiredTime(user.getExpiredTime());

        SimpleOrganization org = user.getOrganization();
        if (org != null) {
            remote.setOrgId(org.getId());
            remote.setOrgName(org.getAlias());
            remote.setOrgFullName(org.getFullName());
        }

        List<SimpleRole> authorities = user.getAuthorities();
        List<String> roles = authorities == null
                ? Collections.emptyList()
                : authorities.stream().map(SimpleRole::getAuthority).toList();
        remote.setRoles(roles);
        remote.setThirdPartIds(resolveThirdPartIds(user.getUsername()));
        return remote;
    }

    /** 汇总用户三方账号标识（thirdType → 三方账号ID），供消息等渠道按需取用 */
    private Map<String, String> resolveThirdPartIds(String username) {
        List<ThirdPartBinding> bindings = userThirdPartService.listByUsername(username);
        if (bindings == null || bindings.isEmpty()) {
            return Collections.emptyMap();
        }
        return bindings.stream()
                .filter(b -> b.getLoginType() != null && b.getOpenId() != null)
                .collect(Collectors.toMap(ThirdPartBinding::getLoginType, ThirdPartBinding::getOpenId, (a, b) -> a));
    }
}
