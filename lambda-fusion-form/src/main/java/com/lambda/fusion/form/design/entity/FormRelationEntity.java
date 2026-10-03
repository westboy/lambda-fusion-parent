package com.lambda.fusion.form.design.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lambda.fusion.core.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 动态表单主从关系（MASTER_SUB）
 *
 * @author Zx
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("la_form_relation")
public class FormRelationEntity extends BaseEntity {

    @TableId(value = "ID", type = IdType.ASSIGN_ID)
    private String id;

    @TableField("FORM_ID")
    private String formId;

    /** 子表单 formKey（同租户内解析） */
    @TableField("SUB_FORM_KEY")
    private String subFormKey;

    /** 子表外键列 */
    @TableField("FOREIGN_KEY")
    private String foreignKey;

    /** CASCADE_DELETE 级联删除 / FORBID_DELETE 有子表数据禁止删除 */
    @TableField("CASCADE_STRATEGY")
    private String cascadeStrategy;

    /** 子表列表呈现配置 JSON：{columns:[{field,width}]}，field 为子表单字段标识（未配置=全量可见字段） */
    @TableField("SUB_TABLE_CONFIG")
    private String subTableConfig;

    @TableField("TENANT_ID")
    private String tenantId;
}
