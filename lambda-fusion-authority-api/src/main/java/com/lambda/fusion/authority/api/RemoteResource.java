package com.lambda.fusion.authority.api;

import java.io.Serial;
import java.io.Serializable;
import lombok.Getter;
import lombok.Setter;

/**
 * 远程资源（跨服务/Dubbo 传输对象）。
 *
 * <p>承载外部功能挂载菜单所需的最小字段视图（菜单/按钮元数据 + 归属标记），
 * 字段对齐 {@code Resource}。仅承载挂载流程读写的字段，未携带字段由 authority 侧保护。
 *
 * @author zx
 */
@Getter
@Setter
public class RemoteResource implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 资源编号 */
    private String id;

    /** 上级资源编号 */
    private String parentId;

    /** 资源名称 */
    private String resName;

    /** 访问路径 */
    private String resPath;

    /** 资源类型（1：菜单，2：外链，3：按钮） */
    private Integer resType;

    /** 资源模式（0：后台资源，1：app 资源） */
    private Integer resMode;

    /** 资源图标 */
    private String icon;

    /** 资源顺序 */
    private Integer orderNo;

    /** 内置扩展字段（路由组件等元数据 JSON） */
    private String expand;

    /** 是否缓存 */
    private Boolean keepAlive;

    /** 所属应用 ID */
    private String application;

    /** 祖先资源链（分隔符拼接，用于防环校验） */
    private String parentKeys;

    /** 业务扩展字段（外部功能归属标记 JSON） */
    private String businessExpand;
}
