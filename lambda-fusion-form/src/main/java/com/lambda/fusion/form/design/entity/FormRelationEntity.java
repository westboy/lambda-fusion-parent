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

    /** 主表单内的关系标识（子表字段经 la_form_field.SUB_FORM_KEY 同值归属） */
    @TableField("SUB_FORM_KEY")
    private String subFormKey;

    /** 子表物理表名（直连物理表；允许与主表重名=自关联） */
    @TableField("SUB_TABLE")
    private String subTable;

    /** 子表外键列 */
    @TableField("FOREIGN_KEY")
    private String foreignKey;

    /** CASCADE_DELETE 级联删除 / FORBID_DELETE 有子表数据禁止删除 */
    @TableField("CASCADE_STRATEGY")
    private String cascadeStrategy;

    /** 呈现方式：INLINE 内联编辑 / LIST 台账式（详情页分页展示、子行独立增删改） */
    @TableField("RELATION_MODE")
    private String mode;

    /** 子表列表呈现配置 JSON：{columns:[{field,width}]}，field 为子表单字段标识（未配置=全量可见字段） */
    @TableField("SUB_TABLE_CONFIG")
    private String subTableConfig;

    @TableField("TENANT_ID")
    private String tenantId;
}
