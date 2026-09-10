package com.lambda.fusion.message.dispatch.resolver.impl;

import com.lambda.fusion.authority.api.RemoteUser;
import com.lambda.fusion.authority.api.RemoteUserService;
import com.lambda.fusion.message.dispatch.resolver.MessageContactResolver;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 经 authority-api 远程用户服务解析三方账号标识（微信/钉钉/支付宝等渠道投递目标）
 * <p>
 * type() 形如 {@code thirdId:wxMa} / {@code thirdId:wxOpen}，后缀即三方类型 thirdType；
 * 仅在 lambda-fusion-authority-api 存在时由 {@code MessageConfigure.AuthorityApiConfigure} 装配，
 * 远程服务不可用或用户未绑定对应三方账号时返回 null，由渠道记录投递失败。
 *
 * @author jin
 */
@Slf4j
public class ThirdIdContactResolver implements MessageContactResolver {

    /** type 前缀：{@code thirdId:<thirdType>} */
    public static final String TYPE_PREFIX = "thirdId:";

    private final String thirdType;
    private final ObjectProvider<RemoteUserService> remoteUserService;

    public ThirdIdContactResolver(String thirdType, ObjectProvider<RemoteUserService> remoteUserService) {
        this.thirdType = thirdType;
        this.remoteUserService = remoteUserService;
    }

    @Override
    public String type() {
        return TYPE_PREFIX + thirdType;
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
            Map<String, String> thirdIds = user.getThirdPartIds();
            return thirdIds == null ? null : thirdIds.get(thirdType);
        } catch (Exception e) {
            log.warn("远程解析用户三方标识失败，username={} thirdType={}: {}", username, thirdType, e.getMessage());
            return null;
        }
    }
}
