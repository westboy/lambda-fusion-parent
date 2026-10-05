package com.lambda.fusion.form.runtime.model;

import com.lambda.fusion.form.runtime.support.FormLayout;
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

    @Schema(description = "草稿预览的修订号，数据请求必须回传以避免使用已变更的配置")
    private Long draftVersion;

    @Schema(description = "字段渲染描述")
    private List<FieldSchema> fields = new ArrayList<>();

    @Schema(description = "TREE 类型运行时树配置（字段标识视角，物理列名不出服务端）")
    private TreeSchema tree;

    @Schema(description = "MASTER_SUB 主从关系（子表 schema 由前端按 subFormKey 单独拉取）")
    private List<RelationSchema> relations = new ArrayList<>();

    /** MASTER_SUB 主从关系描述 */
    @Data
    public static class RelationSchema {

        @Schema(description = "子表单标识")
        private String subFormKey;

        @Schema(description = "子表外键列")
        private String foreignKey;

        @Schema(description = "CASCADE_DELETE / FORBID_DELETE")
        private String cascadeStrategy;

        @Schema(description = "呈现方式：INLINE 内联编辑 / LIST 台账式（详情页分页展示、子行独立增删改）")
        private String mode;

        @Schema(description = "子表列表呈现配置 JSON：{columns:[{field,width}]}，field 为子表单字段标识（未配置=全量可见字段）")
        private String subTableConfig;
    }

    /** TREE 运行时树配置：节点展示与父列均为字段标识 */
    @Data
    public static class TreeSchema {

        @Schema(description = "树来源：SELF 自关联 / FORM 关联树表单 / DICT 字典树")
        private String sourceType;

        @Schema(description = "节点展示字段标识（SELF 自关联时为本表字段）")
        private String labelField;

        @Schema(description = "父列字段标识（仅 SELF）")
        private String parentField;

        @Schema(description = "关联树表单 formKey（仅 FORM，左树读它的树端点）")
        private String treeFormKey;

        @Schema(description = "关联树表单的节点展示字段标识（仅 FORM，左树行取此字段的值作标签）")
        private String formLabelField;

        @Schema(description = "本表存储树节点 id 的字段标识（FORM/DICT）")
        private String nodeIdField;

        @Schema(description = "字典编码（仅 DICT）")
        private String dictType;

        @Schema(description = "呈现方式：LEFT_TREE 左树右表（默认）/ TREE_TABLE 整页缩进树表格（仅 SELF）")
        private String viewMode;
    }

    @Schema(description = "逻辑删除列名（空=物理删除）")
    private String logicDeleteColumn;

    @Schema(description = "列表级配置：分页/按钮等")
    private ListConfig listConfig = new ListConfig();

    @Schema(description = "新增、编辑、详情共用布局")
    private FormLayout formLayout;

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

        @Schema(description = "字典编码")
        private String dictType;

        @Schema(description = "查询项显示")
        private Boolean queryShow;

        @Schema(description = "查询生效操作符")
        private String queryOp;

        @Schema(description = "查询栏默认值")
        private String queryDefaultValue;

        @Schema(description = "列表显示")
        private Boolean listShow;

        @Schema(description = "列宽（px，可空）")
        private Integer width;

        @Schema(description = "列对齐（left/center/right，可空）")
        private String align;

        @Schema(description = "列格式化（date/datetime/dict，可空按类型推断）")
        private String format;

        @Schema(description = "表单/详情显示")
        private Boolean formShow;

        @Schema(description = "前端组件名（可空按类型取默认）")
        private String component;

        @Schema(description = "组件属性")
        private Map<String, Object> props;

        @Schema(description = "校验规则")
        private Map<String, Object> rules;

        @Schema(description = "表单中只读（详情天然只读）")
        private Boolean readonly;

        @Schema(description = "是否落库（false=不入库/纯展示或计算字段）")
        private Boolean persisted;

        @Schema(description = "顺序")
        private Integer sort;
    }

    /**
     * 列表级配置
     */
    @Data
    @Schema(description = "列表级配置")
    public static class ListConfig {

        @Schema(description = "分页条数选项")
        private List<Integer> pageSizes;

        @Schema(description = "显示序号列")
        private Boolean showSeq;

        @Schema(description = "操作按钮显隐与文案")
        private List<ButtonConfig> buttons;
    }

    /**
     * 列表按钮配置
     */
    @Data
    @Schema(description = "列表按钮配置")
    public static class ButtonConfig {

        @Schema(description = "动作 add/edit/delete/export/import/batchDelete")
        private String action;

        @Schema(description = "默认显示（仍需叠加用户权限）")
        private Boolean show;

        @Schema(description = "文案")
        private String label;
    }
}
