package com.lambda.fusion.form.runtime.support;

import com.lambda.fusion.form.FormConstants;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;

/**
 * 编译后的表单模型（不可变，运行时的唯一事实来源）
 *
 * @param formKey 表单标识
 * @param formName 显示名
 * @param version 生效快照版本号
 * @param formType SINGLE / TREE / MASTER_SUB
 * @param tableName 目标物理表（服务端私有，绝不出参）
 * @param fields 字段定义（按 sort 升序）
 * @param relations 主从关系
 * @param tree TREE 类型树配置（可空）
 * @param treeConfig TREE 类型原始配置 JSON（透传设计端，可空）
 */
@SuppressFBWarnings("EI_EXPOSE_REP")
public record FormModel(
        String formKey,
        String formName,
        long version,
        String formType,
        String tableName,
        String logicDeleteColumn,
        ListConfig listConfig,
        FormLayout formLayout,
        List<FormField> fields,
        List<FormRelation> relations,
        TreeConfig tree,
        String treeConfig) {

    /**
     * 字段定义
     *
     * @param name 字段标识（前端 vModel）
     * @param column 物理列名
     * @param type 字段类型
     * @param nullable 可空
     * @param defaultValue 默认值
     * @param dictType 字典编码
     * @param sort 顺序
     * @param persisted 是否落库
     * @param query 查询区配置
     * @param list 列表列配置
     * @param form 表单与详情配置
     * @param subFormKey 一对一扁平模式的从表归属（null=主表字段；从表字段不支持查询条件与排序）
     */
    public record FormField(
            String name,
            String label,
            String column,
            String type,
            boolean nullable,
            String defaultValue,
            String dictType,
            int sort,
            boolean persisted,
            String subFormKey,
            QueryConf query,
            ListConf list,
            FormConf form) {

        /** 是否允许出现在查询白名单（从表字段不参与：查询 SQL 只在主表上执行） */
        public boolean queryable() {
            return query.show() && subFormKey == null;
        }

        /** 是否允许出现在列表与排序白名单 */
        public boolean listVisible() {
            return list.show();
        }

        /** 是否允许出现在排序白名单（从表字段不参与：排序 SQL 只在主表上执行） */
        public boolean sortable() {
            return list.show() && subFormKey == null;
        }
    }

    /**
     * 查询区配置（op 为编译期归一后的生效操作符）
     *
     * @param show 查询项显示
     * @param op 生效操作符
     * @param defaultValue 查询栏默认值
     */
    public record QueryConf(boolean show, String op, String defaultValue) {}

    /**
     * 列表列配置
     *
     * @param show 列显示
     * @param width 列宽（px，可空取组件默认）
     * @param align 对齐（left/center/right，可空取默认）
     * @param format 展示格式化（date/datetime/dict，可空按字段类型推断）
     * @param frozen 冻结（left/right，可空）
     * @param summary 参与合计
     * @param defaultSort 默认排序方向（asc/desc）
     * @param sortOrder 默认排序优先级
     */
    public record ListConf(
            boolean show,
            Integer width,
            String align,
            String format,
            String frozen,
            boolean summary,
            String defaultSort,
            int sortOrder) {}

    /**
     * 列表级配置
     *
     * @param layout 布局 NORMAL / TREE_LEFT / TREE_GRID
     * @param pageSizes 分页条数选项
     * @param advancedQuery 高级查询开关
     * @param fullQuery 全量查询开关
     * @param showSeq 显示序号列
     * @param buttons 按钮显隐与文案
     */
    public record ListConfig(
            String layout,
            List<Integer> pageSizes,
            boolean advancedQuery,
            boolean fullQuery,
            boolean showSeq,
            List<ButtonConfig> buttons) {

        public List<Integer> pageSizes() {
            return pageSizes == null || pageSizes.isEmpty() ? List.of(20, 50, 100, 500) : pageSizes;
        }
    }

    /**
     * 列表按钮配置
     *
     * @param action 动作标识 add/edit/delete/export/import/batchDelete
     * @param show 默认显示（仍需叠加用户权限）
     * @param label 文案
     */
    public record ButtonConfig(String action, boolean show, String label) {}

    /**
     * 表单与详情配置
     *
     * @param show 表单/详情显示
     * @param component 前端组件名（可空按类型取默认）
     * @param props 组件属性
     * @param rules 校验规则
     * @param readonly 表单中只读（详情天然只读）
     */
    public record FormConf(
            boolean show, String component, Map<String, Object> props, Map<String, Object> rules, boolean readonly) {}

    /**
     * 主从关系
     *
     * @param subFormKey 子表单 formKey
     * @param foreignKey 子表外键列
     * @param cascadeStrategy CASCADE_DELETE / FORBID_DELETE
     * @param mode 呈现方式：INLINE 内联编辑 / LIST 台账式
     * @param subTableConfig 子表列表呈现配置 JSON（{columns:[{field,width}]}，field 为子表单字段标识）
     */
    public record FormRelation(
            String subFormKey, String foreignKey, String cascadeStrategy, String mode, String subTableConfig) {

        /** 台账式子表：详情页分页展示、子行独立增删改，不随主表提交 */
        public boolean listMode() {
            return FormConstants.RELATION_MODE_LIST.equals(mode);
        }

        /** 一对一扁平：从表字段并入主表呈现，单条 upsert（存在改/不存在增） */
        public boolean flatMode() {
            return FormConstants.RELATION_MODE_FLAT.equals(mode);
        }
    }

    /**
     * TREE 类型树配置
     *
     * @param sourceType 数据源类型：SELF 自关联 / FORM 关联树表单 / DICT 字典
     * @param parentColumn 自关联父列（仅 SELF）
     * @param nodeColumn 自关联节点列（展示列，仅 SELF）
     * @param dictType 字典编码（仅 DICT）
     * @param filterColumn 树过滤作用于业务表的列（默认取 parentColumn 同名列，仅 SELF）
     * @param treeFormKey 关联的树表单 formKey（仅 FORM）
     * @param nodeIdField 本表存储树节点 id 的字段标识（FORM/DICT）
     * @param viewMode 呈现方式：LEFT_TREE 左树右表（默认）/ TREE_TABLE 整页缩进树表格（仅 SELF）
     */
    public record TreeConfig(
            String sourceType,
            String parentColumn,
            String nodeColumn,
            String dictType,
            String filterColumn,
            String treeFormKey,
            String nodeIdField,
            String viewMode) {

        public static final String VIEW_MODE_LEFT_TREE = "LEFT_TREE";

        public static final String VIEW_MODE_TREE_TABLE = "TREE_TABLE";

        /** SELF 自关联：树行即本表业务行 */
        public boolean selfRelation() {
            return FormConstants.TREE_SOURCE_SELF.equalsIgnoreCase(sourceType);
        }

        /** FORM 关联树表单：左树读另一 TREE 表单的数据 */
        public boolean formSource() {
            return FormConstants.TREE_SOURCE_FORM.equalsIgnoreCase(sourceType);
        }

        /** DICT 字典树：左树读字典层级数据，本表字段存字典项代码 */
        public boolean dictSource() {
            return FormConstants.TREE_SOURCE_DICT.equalsIgnoreCase(sourceType);
        }

        /** 呈现方式：整页缩进树表格（仅 SELF 自关联支持，默认左树右表） */
        public boolean treeTableView() {
            return VIEW_MODE_TREE_TABLE.equalsIgnoreCase(viewMode);
        }
    }
}
