package com.lambda.fusion.workflow.launch.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 发起中心分组视图（已发布流程定义按分类分组）
 *
 * @author Zx
 */
@Data
@Schema(description = "发起中心分组视图")
public class LaunchGroupView {

    @Schema(description = "流程分类（空归「未分类」）")
    private String category;

    @Schema(description = "分类下的可发起流程")
    private List<Item> items = new ArrayList<>();

    /**
     * 可发起流程项
     */
    @Data
    @Schema(description = "可发起流程项")
    public static class Item {

        @Schema(description = "流程编码（发起提交用）")
        private String flowCode;

        @Schema(description = "流程名称")
        private String flowName;

        @Schema(description = "绑定的动态表单标识（flow_definition.form_path；空=未绑定表单）")
        private String formKey;

        @Schema(description = "当前用户是否可发起（已绑定表单且具备 form:{formKey}:add 权限；不可发起的卡片前端置灰）")
        private boolean launchable;
    }
}
