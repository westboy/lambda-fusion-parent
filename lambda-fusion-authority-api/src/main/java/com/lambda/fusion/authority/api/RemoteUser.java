package com.lambda.fusion.authority.api;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 远程用户详情（跨服务/Dubbo 传输对象）。
 *
 * <p>字段对齐 {@code lambda-fusion-core} 的 {@code UserDetails}，供其他服务（如 ai）在无法直接访问
 * authority 库时获取用户身份全貌。authority-api 仅承载契约，不含实现；密码等敏感凭据不进入本对象。
 *
 * @author zx
 */
@Getter
@Setter
public class RemoteUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户名（登录名，唯一）。 */
    private String username;

    /** 用户昵称。 */
    private String nickname;

    /** 手机号。 */
    private String mobile;

    /** 邮箱。 */
    private String email;

    /** 组织ID。 */
    private String orgId;

    /** 组织名称（别名）。 */
    private String orgName;

    /** 组织全称（含层级路径）。 */
    private String orgFullName;

    /** 租户ID。 */
    private String tenantId;

    /** 角色权限码集合（如 ROLE_ADMIN）。 */
    private List<String> roles;

    /** 账户是否启用。 */
    private boolean enabled;

    /** 账户是否被锁定。 */
    private boolean locked;

    /** 账户过期时间。 */
    private Date expiredTime;

    /** 三方账号标识映射（key=三方类型 thirdType，value=三方账号ID），用于消息等渠道按需获取。 */
    private Map<String, String> thirdPartIds;

    public Map<String, String> getThirdPartIds() {
        return thirdPartIds == null ? null : Map.copyOf(thirdPartIds);
    }

    public void setThirdPartIds(Map<String, String> thirdPartIds) {
        this.thirdPartIds = thirdPartIds == null ? null : Map.copyOf(thirdPartIds);
    }

    public List<String> getRoles() {
        return roles == null ? null : List.copyOf(roles);
    }

    public void setRoles(List<String> roles) {
        this.roles = roles == null ? null : List.copyOf(roles);
    }

    public Date getExpiredTime() {
        return expiredTime == null ? null : new Date(expiredTime.getTime());
    }

    public void setExpiredTime(Date expiredTime) {
        this.expiredTime = expiredTime == null ? null : new Date(expiredTime.getTime());
    }
}
