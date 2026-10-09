package com.lambda.fusion.authority.api;

import java.io.Serial;
import java.io.Serializable;
import lombok.Getter;
import lombok.Setter;

/**
 * 远程组织摘要（跨服务/Dubbo 传输对象）。
 *
 * <p>供工作流设计器等场景列举可选办理部门，字段对齐 {@code Organization}。
 *
 * @author jin
 */
@Getter
@Setter
public class RemoteOrg implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 组织 ID，对应权限标识 {@code org:{id}} */
    private String id;

    /** 组织显示名（别名优先） */
    private String name;

    /** 组织全称（含层级路径） */
    private String fullName;

    /** 父组织 ID（根节点为空串/null）；消费方按此自行嵌套成树 */
    private String parentId;

    public RemoteOrg() {}

    public RemoteOrg(String id, String name, String fullName) {
        this.id = id;
        this.name = name;
        this.fullName = fullName;
    }

    public RemoteOrg(String id, String name, String fullName, String parentId) {
        this.id = id;
        this.name = name;
        this.fullName = fullName;
        this.parentId = parentId;
    }
}
