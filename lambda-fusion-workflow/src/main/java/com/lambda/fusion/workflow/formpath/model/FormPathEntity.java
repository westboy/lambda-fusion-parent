package com.lambda.fusion.workflow.formpath.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lambda.fusion.core.entity.BaseEntity;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 流程自定义表单注册表（设计器 form_path 下拉数据源，§10.5）
 *
 * @author jin
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("la_workflow_form_path")
@SuppressFBWarnings("EI_EXPOSE_REP")
public class FormPathEntity extends BaseEntity {

    @TableId(value = "ID", type = IdType.ASSIGN_ID)
    private String id;

    @TableField("FORM_NAME")
    private String formName;

    /** 表单路径/前端路由，落 flow_definition.form_path */
    @TableField("FORM_PATH")
    private String formPath;

    @TableField("REMARKS")
    private String remarks;

    /** 启停：1 启用 0 停用 */
    @TableField("ENABLED")
    private Integer enabled;

    @TableField("TENANT_ID")
    private String tenantId;
}
