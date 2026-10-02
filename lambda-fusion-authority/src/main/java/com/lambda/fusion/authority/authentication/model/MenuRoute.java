package com.lambda.fusion.authority.authentication.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lambda.fusion.core.tree.TreeNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class MenuRoute implements TreeNode<MenuRoute>, Serializable {

    @JsonIgnore
    @Schema(description = "id")
    private String id;

    @JsonIgnore
    @Schema(description = "pid")
    private String pid;

    @Schema(description = "路由组件")
    private String component;

    @JsonIgnore
    private String expand;

    @Schema(description = "路由重定向")
    private String redirect;

    @Schema(description = "路由元信息")
    private NavigationRouteMeta meta;

    @Schema(description = "路由名称")
    public String name;

    @JsonIgnore
    @Schema(description = "类型")
    public Integer type;

    @Schema(description = "路由名称")
    public String url;

    @Schema(description = "图标")
    public String icon;

    @JsonIgnore
    @Schema(description = "排序")
    public Integer orderNo;

    @JsonIgnore
    @Schema(description = "是否缓存")
    private boolean keepAlive;

    @JsonIgnore
    @Schema(description = "是否隐藏")
    private boolean hidden;

    @Schema(description = "路由路径")
    public String path;

    @Schema(description = "子资源集合")
    private List<MenuRoute> children;

    public List<MenuRoute> getChildren() {
        if (this.children == null) {
            this.children = new ArrayList<>();
        }
        return this.children;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String pid() {
        return pid;
    }

    @Override
    public void children(List<MenuRoute> children) {
        this.children = children;
    }
}
