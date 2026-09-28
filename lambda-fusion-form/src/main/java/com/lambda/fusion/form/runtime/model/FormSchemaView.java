package com.lambda.fusion.form.runtime.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 前端渲染元数据（剥离物理映射：column/table 不出服务端）
 *
 * @author Zx
 */
@Data
@Schema(description = "表单渲染元数据")
public class FormSchemaView {

    @Schema(description = "表单标识")
    private String formKey;

    @Schema(description = "显示名")
    private String formName;

    @Schema(description = "SINGLE / TREE / MASTER_SUB")
    private String formType;

    @Schema(description = "生效版本号")
    private Long version;

    @Schema(description = "字段渲染描述")
    private List<FieldSchema> fields = new ArrayList<>();

    @Schema(description = "TREE 类型树配置（原样透传设计端 treeConfig）")
    private String treeConfig;

    @Schema(description = "当前用户按钮权限：add/edit/delete")
    private Map<String, Boolean> permissions = new LinkedHashMap<>();

    /**
     * 字段渲染描述
     */
    @Data
    @Schema(description = "字段渲染描述")
    public static class FieldSchema {

        @Schema(description = "字段标识")
        private String name;

        @Schema(description = "显示名（取列注释或字段标识）")
        private String label;

        @Schema(description = "字段类型")
        private String type;

        @Schema(description = "可空")
        private Boolean nullable;

        @Schema(description = "默认值")
        private String defaultValue;

        @Schema(description = "校验规则 JSON")
        private String validation;

        @Schema(description = "字典编码")
        private String dictType;

        @Schema(description = "前端组件名")
        private String component;

        @Schema(description = "组件属性 JSON")
        private String props;

        @Schema(description = "列表显示")
        private Boolean listShow;

        @Schema(description = "查询项显示")
        private Boolean queryShow;

        @Schema(description = "查询默认操作符")
        private String queryOp;

        @Schema(description = "表单显示")
        private Boolean formShow;

        @Schema(description = "顺序")
        private Integer sort;
    }
}
