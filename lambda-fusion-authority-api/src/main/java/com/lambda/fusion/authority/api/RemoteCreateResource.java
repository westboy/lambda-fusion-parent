package com.lambda.fusion.authority.api;

import java.io.Serial;
import java.io.Serializable;
import lombok.Getter;
import lombok.Setter;

/**
 * 远程资源创建参数（跨服务/Dubbo 传输对象）。
 *
 * <p>承载外部功能挂载时创建菜单/按钮资源的字段，字段对齐 {@code CreateResource}。
 *
 * @author zx
 */
@Getter
@Setter
public class RemoteCreateResource implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

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

    /** 所属应用 ID */
    private String application;

    /** 资源图标 */
    private String icon;

    /** 内置扩展字段（路由组件等元数据 JSON） */
    private String expand;

    /** 业务扩展字段（外部功能归属标记 JSON） */
    private String businessExpand;
}
