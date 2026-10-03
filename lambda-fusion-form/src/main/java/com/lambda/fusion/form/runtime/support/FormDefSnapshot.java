package com.lambda.fusion.form.runtime.support;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 发布快照 JSON 结构（不可变，仅用于编译读取）
 *
 * @author Zx
 */
@Data
@SuppressFBWarnings("EI_EXPOSE_REP")
public class FormDefSnapshot {

    /** 快照格式版本，用于未来结构演进 */
    private Integer snapshotVersion = 1;

    private String formKey;

    private String formName;

    private String formType;

    private String tableName;

    private String treeConfig;

    private String flowCode;

    private String titleTemplate;

    private String interceptor;

    /** 保存时同步发布（默认 false） */
    private Boolean publishOnSave = Boolean.FALSE;

    /** 逻辑删除列名（空=物理删除） */
    private String logicDeleteColumn;

    /** 列表级配置：布局/分页/按钮等 */
    private ListConfig listConfig = new ListConfig();

    private FormLayout formLayout;

    private List<Field> fields = new ArrayList<>();

    private List<Relation> relations = new ArrayList<>();

    /**
     * 字段快照
     */
    @Data
    public static class Field {
        private String fieldName;
        private String label;
        private String columnName;
        private String fieldType;
        private Boolean nullable = Boolean.TRUE;
        private String defaultValue;
        private String dictType;
        private Integer sort = 0;
        /** 是否落库（false=不入库/纯展示或计算字段） */
        private Boolean persisted = Boolean.TRUE;

        private QueryConf query = new QueryConf();
        private ListConf list = new ListConf();
        private FormConf form = new FormConf();
    }

    /** 查询区配置 */
    @Data
    public static class QueryConf {
        private Boolean show = Boolean.FALSE;
        private String op;
        private String defaultValue;
    }

    /** 列表列配置 */
    @Data
    public static class ListConf {
        private Boolean show = Boolean.TRUE;
        private Integer width;
        private String align;
        private String format;
        /** 冻结列（left/right） */
        private String frozen;
        /** 参与合计 */
        private Boolean summary = Boolean.FALSE;
        /** 默认排序方向（asc/desc） */
        private String defaultSort;
        /** 默认排序优先级（越小越优先） */
        private Integer sortOrder = 0;
    }

    /** 列表级配置：布局、分页、按钮等 */
    @Data
    public static class ListConfig {
        /** 布局：NORMAL / TREE_LEFT / TREE_GRID */
        private String layout = "NORMAL";
        /** 分页条数选项 */
        private List<Integer> pageSizes = List.of(20, 50, 100, 500);
        /** 是否显示高级查询 */
        private Boolean advancedQuery = Boolean.FALSE;
        /** 是否显示全量查询 */
        private Boolean fullQuery = Boolean.FALSE;
        /** 是否显示序号列 */
        private Boolean showSeq = Boolean.TRUE;
        /** 操作按钮显隐与文案 */
        private List<ButtonConfig> buttons = new ArrayList<>();
    }

    /** 列表按钮配置 */
    @Data
    public static class ButtonConfig {
        private String action;
        private Boolean show = Boolean.TRUE;
        private String label;
    }

    /** 表单与详情配置 */
    @Data
    public static class FormConf {
        private Boolean show = Boolean.TRUE;
        private String component;
        private Map<String, Object> props;
        private Map<String, Object> rules;
        private Boolean readonly = Boolean.FALSE;
    }

    /**
     * 主从关系快照
     */
    @Data
    public static class Relation {
        private String subFormKey;
        private String foreignKey;
        private String cascadeStrategy;

        /** 呈现方式：INLINE 内联编辑 / LIST 台账式（null 视为 INLINE，兼容历史快照） */
        private String mode;

        /** 子表列表呈现配置 JSON：{columns:[{field,width}]}，field 为子表单字段标识 */
        private String subTableConfig;
    }
}
