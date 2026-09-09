package com.lambda.fusion.authority.role.model;

import com.lambda.fusion.core.tree.TreeNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

@Data
public class AccessPermission implements TreeNode<AccessPermission> {

    @Schema(description = "权限编号")
    private String id;

    @Schema(description = "权限名称")
    private String name;

    @Schema(description = "上级编号")
    private String parentId;

    @Schema(description = "是否选中")
    private Boolean checked;

    @Schema(description = "ICON")
    private String icon;

    private int type;

    @Schema(description = "下级权限")
    private List<AccessPermission> children;

    @Schema(description = "按钮权限")
    private List<AccessPermission> buttons;

    private Integer status;

    @Schema(description = "所属应用ID")
    private String application;

    @Override
    public String id() {
        return getId();
    }

    @Override
    public String pid() {
        return getParentId();
    }

    @Override
    public void children(List<AccessPermission> children) {
        this.children = children;
    }
}
