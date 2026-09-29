package com.lambda.fusion.form.design.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lambda.fusion.core.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 动态表单字段定义
 *
 * @author Zx
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("la_form_field")
public class FormFieldEntity extends BaseEntity {

    @TableId(value = "ID", type = IdType.ASSIGN_ID)
    private String id;

    @TableField("FORM_ID")
    private String formId;

    /** 字段标识（前端 vModel） */
    @TableField("FIELD_NAME")
    private String fieldName;

    /** 物理列映射 */
    @TableField("COLUMN_NAME")
    private String columnName;

    /** STRING/TEXT/INT/DECIMAL/DATE/DATETIME/DICT/BOOL/FILE */
    @TableField("FIELD_TYPE")
    private String fieldType;

    /** 可空 */
    @TableField("NULLABLE")
    private Boolean nullable;

    @TableField("DEFAULT_VALUE")
    private String defaultValue;

    /** DICT 类型的字典编码 */
    @TableField("DICT_TYPE")
    private String dictType;

    /** 查询区配置 JSON：{show, op, defaultValue} */
    @TableField("QUERY_CONFIG")
    private String queryConfig;

    /** 列表列配置 JSON：{show, width, align, format} */
    @TableField("LIST_CONFIG")
    private String listConfig;

    /** 表单与详情配置 JSON：{show, component, props, rules, readonly} */
    @TableField("FORM_CONFIG")
    private String formConfig;

    /** 是否落库（0=不入库/纯展示或计算字段；写入时剔除，展示时保留） */
    @TableField("PERSISTED")
    private Boolean persisted;

    @TableField("SORT")
    private Integer sort;

    @TableField("TENANT_ID")
    private String tenantId;
}
