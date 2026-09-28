package com.lambda.fusion.form.runtime.support;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;

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
     * @param validation 校验规则 JSON
     * @param dictType 字典编码
     * @param uiComponent 前端组件名
     * @param uiProps 组件属性 JSON
     * @param listShow 列表显示
     * @param queryShow 查询项显示
     * @param queryOp 查询默认操作符
     * @param formShow 表单显示
     * @param sort 顺序
     */
    public record FormField(
            String name,
            String column,
            String type,
            boolean nullable,
            String defaultValue,
            String validation,
            String dictType,
            String uiComponent,
            String uiProps,
            boolean listShow,
            boolean queryShow,
            String queryOp,
            boolean formShow,
            int sort) {

        /** 是否允许出现在查询白名单 */
        public boolean queryable() {
            return queryShow;
        }
    }

    /**
     * 主从关系
     *
     * @param subFormKey 子表单 formKey
     * @param foreignKey 子表外键列
     * @param cascadeStrategy CASCADE_DELETE / FORBID_DELETE
     */
    public record FormRelation(String subFormKey, String foreignKey, String cascadeStrategy) {}

    /**
     * TREE 类型树配置
     *
     * @param sourceType 数据源类型：SELF 自关联 / DICT 字典
     * @param parentColumn 自关联父列
     * @param nodeColumn 自关联节点列（展示列）
     * @param dictType 字典编码（DICT 类型）
     * @param filterColumn 树过滤作用于业务表的列（默认取 parentColumn 同名列）
     */
    public record TreeConfig(
            String sourceType, String parentColumn, String nodeColumn, String dictType, String filterColumn) {

        public boolean selfRelation() {
            return "SELF".equalsIgnoreCase(sourceType);
        }
    }
}
