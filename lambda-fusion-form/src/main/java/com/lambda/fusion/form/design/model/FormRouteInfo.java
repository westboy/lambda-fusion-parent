package com.lambda.fusion.form.design.model;

import java.util.List;

/** 挂载状态以资源绑定是否真实存在为准，与生效版本状态分开。 */
public record FormRouteInfo(
        boolean available,
        boolean mounted,
        String resourceId,
        String parentId,
        String name,
        String path,
        String icon,
        Integer orderNo,
        List<Action> actions) {
    public FormRouteInfo {
        actions = List.copyOf(actions);
    }

    public record Action(String action, String label, String permission, String resourceId) {}
}
