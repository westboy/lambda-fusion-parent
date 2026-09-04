package com.lambda.fusion.message.dispatch.resolver.impl;

import com.lambda.fusion.authority.api.RemoteUser;
import com.lambda.fusion.authority.api.RemoteUserService;
import com.lambda.fusion.message.dispatch.resolver.MessageContactResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 经 authority-api 远程用户服务解析联系方式
 * <p>
 * 仅在 lambda-fusion-authority-api 存在时由 {@code MessageConfigure.AuthorityApiConfigure} 装配；
 * 远程服务不可用或用户缺失时返回 null，由渠道记录投递失败。
 *
 * @author jin
 */
@Slf4j
public class RemoteContactResolver implements MessageContactResolver {

    private final String type;
    private final ObjectProvider<RemoteUserService> remoteUserService;

    public RemoteContactResolver(String type, ObjectProvider<RemoteUserService> remoteUserService) {
        this.type = type;
        this.remoteUserService = remoteUserService;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public String resolve(String username) {
        RemoteUserService service = remoteUserService.getIfAvailable();
        if (service == null || username == null) {
            return null;
        }
        try {
            RemoteUser user = service.getByUsername(username);
            if (user == null) {
                return null;
            }
            return "email".equals(type) ? user.getEmail() : user.getMobile();
        } catch (Exception e) {
            log.warn("远程解析用户联系方式失败，username={}: {}", username, e.getMessage());
            return null;
        }
    }
}
