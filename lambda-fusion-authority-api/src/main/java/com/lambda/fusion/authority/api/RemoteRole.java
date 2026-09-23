package com.lambda.fusion.authority.api;

import java.io.Serial;
import java.io.Serializable;
import lombok.Getter;
import lombok.Setter;

/**
 * 远程角色摘要（跨服务/Dubbo 传输对象）。
 *
 * <p>供工作流设计器等场景列举可选办理角色，字段对齐 {@code SimpleRole}。
 *
 * @author jin
 */
@Getter
@Setter
public class RemoteRole implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 角色权限码（如 ROLE_ADMIN），对应权限标识 {@code role:{authority}} */
    private String authority;

    /** 角色显示名 */
    private String alias;

    public RemoteRole() {}

    public RemoteRole(String authority, String alias) {
        this.authority = authority;
        this.alias = alias;
    }
}
