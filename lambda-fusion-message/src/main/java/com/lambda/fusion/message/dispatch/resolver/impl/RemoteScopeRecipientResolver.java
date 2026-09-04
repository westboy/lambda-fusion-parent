package com.lambda.fusion.message.dispatch.resolver.impl;

import com.lambda.fusion.authority.api.RemoteUser;
import com.lambda.fusion.authority.api.RemoteUserService;
import com.lambda.fusion.message.dispatch.resolver.MessageRecipientResolver;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 经 authority-api 远程用户服务解析公告可见范围（org / role）
 * <p>
 * 仅在 lambda-fusion-authority-api 存在时由 {@code MessageConfigure.AuthorityApiConfigure} 装配；
 * 远程服务不可用或用户缺失时按不命中处理。
 *
 * @author jin
 */
@Slf4j
public class RemoteScopeRecipientResolver implements MessageRecipientResolver {

    /** org：范围目标为组织ID集合，按用户所属组织命中 */
    public static final String SCOPE_ORG = "org";

    /** role：范围目标为角色权限码集合，按用户角色交集命中 */
    public static final String SCOPE_ROLE = "role";

    private final String scopeType;
    private final ObjectProvider<RemoteUserService> remoteUserService;
    private final ObjectMapper objectMapper;

    private RemoteScopeRecipientResolver(
            String scopeType, ObjectProvider<RemoteUserService> remoteUserService, ObjectMapper objectMapper) {
        this.scopeType = scopeType;
        this.remoteUserService = remoteUserService;
        this.objectMapper = objectMapper;
    }

    public static RemoteScopeRecipientResolver orgResolver(
            ObjectProvider<RemoteUserService> remoteUserService, ObjectMapper objectMapper) {
        return new RemoteScopeRecipientResolver(SCOPE_ORG, remoteUserService, objectMapper);
    }

    public static RemoteScopeRecipientResolver roleResolver(
            ObjectProvider<RemoteUserService> remoteUserService, ObjectMapper objectMapper) {
        return new RemoteScopeRecipientResolver(SCOPE_ROLE, remoteUserService, objectMapper);
    }

    @Override
    public String scopeType() {
        return scopeType;
    }

    @Override
    public boolean contains(String scopeTargets, String username) {
        if (scopeTargets == null || scopeTargets.isBlank() || username == null) {
            return false;
        }
        List<String> targets = parseTargets(scopeTargets);
        if (targets.isEmpty()) {
            return false;
        }
        RemoteUserService service = remoteUserService.getIfAvailable();
        if (service == null) {
            log.warn("远程用户服务不可用，公告范围 {} 按不命中处理", scopeType);
            return false;
        }
        try {
            RemoteUser user = service.getByUsername(username);
            if (user == null) {
                return false;
            }
            if (SCOPE_ORG.equals(scopeType)) {
                return user.getOrgId() != null && targets.contains(user.getOrgId());
            }
            List<String> roles = user.getRoles();
            return roles != null && roles.stream().anyMatch(targets::contains);
        } catch (Exception e) {
            log.warn("公告范围解析失败，scopeType={} username={}: {}", scopeType, username, e.getMessage());
            return false;
        }
    }

    private List<String> parseTargets(String scopeTargets) {
        try {
            List<String> targets = objectMapper.readValue(scopeTargets, new TypeReference<>() {});
            return targets == null ? List.of() : targets;
        } catch (Exception e) {
            log.warn("scopeTargets 解析失败，按不命中处理: {}", e.getMessage());
            return List.of();
        }
    }
}
