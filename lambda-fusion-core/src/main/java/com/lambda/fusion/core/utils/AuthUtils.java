package com.lambda.fusion.core.utils;

import cn.hutool.core.util.StrUtil;
import com.lambda.cloud.core.utils.OperatorUtils;
import com.lambda.fusion.core.identity.UserDetails;
import lombok.experimental.UtilityClass;

@UtilityClass
public class AuthUtils {

    public static UserDetails getUser() {
        return OperatorUtils.getLoginUser(UserDetails.class);
    }

    public static String getTenantId() {
        return getUser().getTenantId();
    }

    public static String getTenantIdNullToDefault() {
        return StrUtil.nullToDefault(AuthUtils.getTenantId(), "default");
    }

    public static String getUsername() {
        return getUser().getUsername();
    }

    /**
     * 安全获取当前登录用户名：无登录上下文（如 Quartz 后台线程、应用启动装配）时降级为匿名用户、
     * 不抛异常。适用于审计字段填充等「有则取之、无则降级」的场景；需要强制登录的鉴权场景仍应使用
     * {@link #getUsername()}。
     */
    public static String getSafeUsername() {
        return OperatorUtils.getSafeOperator().getName();
    }
}
