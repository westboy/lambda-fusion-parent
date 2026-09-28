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

    /** 校验规则 JSON（正则/范围/必填提示） */
    @TableField("VALIDATION")
    private String validation;

    /** DICT 类型的字典编码 */
    @TableField("DICT_TYPE")
    private String dictType;

    /** 前端组件名（Input/Select/DatePicker…） */
    @TableField("UI_COMPONENT")
    private String uiComponent;

    /** 组件属性 JSON */
    @TableField("UI_PROPS")
    private String uiProps;

    @TableField("LIST_SHOW")
    private Boolean listShow;

    @TableField("QUERY_SHOW")
    private Boolean queryShow;

    /** 查询默认操作符（空则按字段类型取默认） */
    @TableField("QUERY_OP")
    private String queryOp;

    @TableField("FORM_SHOW")
    private Boolean formShow;

    @TableField("SORT")
    private Integer sort;

    @TableField("TENANT_ID")
    private String tenantId;
}
