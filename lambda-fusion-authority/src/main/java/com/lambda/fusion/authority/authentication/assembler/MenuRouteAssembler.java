package com.lambda.fusion.authority.authentication.assembler;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.lambda.fusion.authority.AuthorityConstants;
import com.lambda.fusion.authority.authentication.model.MenuRoute;
import com.lambda.fusion.authority.authentication.model.NavigationRouteMeta;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 菜单路由组装器
 * <p>
 * 负责将数据库查询的原始菜单数据组装为前端路由结构，
 * 包括元信息填充、组件解析、重定向设置等
 */
@Component
@RequiredArgsConstructor
@Slf4j
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "共享 Spring ObjectMapper，仅用于读取路由元数据")
public class MenuRouteAssembler {
    private final ObjectMapper json;

    /**
     * 丰富菜单路由信息
     *
     * @param menuRouteTree 菜单路由树
     */
    public void enrich(List<MenuRoute> menuRouteTree) {
        if (CollUtil.isEmpty(menuRouteTree)) {
            return;
        }
        for (MenuRoute root : menuRouteTree) {
            enrichNode(root);
        }
    }

    private void enrichNode(MenuRoute node) {
        if (node == null) {
            return;
        }
        NavigationRouteMeta meta = node.getMeta();
        if (meta == null) {
            meta = new NavigationRouteMeta();
            node.setMeta(meta);
        }
        meta.putIfAbsent("title", node.getName());
        meta.putIfAbsent("icon", node.getIcon());
        meta.putIfAbsent("order", node.getOrderNo());
        meta.putIfAbsent("hideInMenu", node.isHidden());
        meta.putIfAbsent("keepAlive", node.isKeepAlive());
        applyExplicitRoute(node, meta);
        if (StrUtil.isBlank(node.getComponent())) {
            String component = resolveComponent(node, meta);
            if (StrUtil.isNotBlank(component)) {
                node.setComponent(component);
            } else {
                String path = StrUtil.removePrefix(node.getPath(), "/");
                if (StrUtil.endWith(path, "/")) {
                    path = StrUtil.removeSuffix(path, "/");
                    path = path + "/index";
                }
                node.setComponent(path);
            }
        }
        if (StrUtil.isBlank(node.getRedirect()) && CollUtil.isNotEmpty(node.getChildren())) {
            MenuRoute firstChild = node.getChildren().getFirst();
            if (firstChild != null && StrUtil.isNotBlank(firstChild.path)) {
                node.setRedirect(firstChild.getPath());
            }
        }
        if (CollUtil.isNotEmpty(node.getChildren())) {
            for (MenuRoute child : node.getChildren()) {
                enrichNode(child);
            }
        }
    }

    private void applyExplicitRoute(MenuRoute node, NavigationRouteMeta meta) {
        if (StrUtil.isBlank(node.getExpand())) return;
        try {
            var expand = json.readTree(node.getExpand());
            if (!expand.isObject() || !expand.path("component").isTextual()) return;
            String component = expand.path("component").asText();
            if (component.isBlank()) return;
            node.setComponent(component);
            // 菜单名称可编辑；显式组件路由以资源 ID 保持稳定且唯一的路由名称。
            node.setName("resource:" + node.getId());
            var configuredMeta = expand.path("meta");
            if (configuredMeta.isObject()) {
                configuredMeta
                        .properties()
                        .forEach(entry ->
                                meta.putIfAbsent(entry.getKey(), json.convertValue(entry.getValue(), Object.class)));
            }
        } catch (JacksonException error) {
            log.warn("忽略无效的菜单路由扩展，resourceId={}", node.getId());
        }
    }

    private String resolveComponent(MenuRoute node, NavigationRouteMeta meta) {
        if (CollUtil.isNotEmpty(node.getChildren())) {
            return "BasicLayout";
        }
        Integer type = node.getType();
        if (type == null) {
            return null;
        }
        String url = node.getUrl();
        if (type.equals(AuthorityConstants.MenuType.EXTERNAL_LINK.getCode())) {
            if (StrUtil.isNotBlank(url)) {
                meta.putIfAbsent("link", url);
                return "IFrameView";
            }
            return null;
        }
        if (type.equals(AuthorityConstants.MenuType.EMBEDDED_PAGE.getCode())) {
            if (StrUtil.isNotBlank(url)) {
                meta.putIfAbsent("iframeSrc", url);
                return "IFrameView";
            }
            return null;
        }
        return null;
    }
}
