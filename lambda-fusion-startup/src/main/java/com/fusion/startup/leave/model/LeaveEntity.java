package com.fusion.startup.leave.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lambda.fusion.core.entity.BaseEntity;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 请假单（工作流演示业务，docs/design/workflow.md §10.2 五步范式样板）
 *
 * @author jin
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("la_leave")
@SuppressFBWarnings("EI_EXPOSE_REP")
public class LeaveEntity extends BaseEntity {

    /** 草稿（可编辑/可提交/可删除） */
    public static final int STATUS_DRAFT = 0;

    /** 审批中（提交后进入流程） */
    public static final int STATUS_APPROVING = 1;

    /** 审批通过 */
    public static final int STATUS_APPROVED = 2;

    /** 已驳回（终止/作废等终态） */
    public static final int STATUS_REJECTED = 3;

    @TableId(value = "ID", type = IdType.ASSIGN_ID)
    private String id;

    @TableField("APPLICANT")
    private String applicant;

    /** 请假天数（流程变量 days，供 skip_condition 条件分支） */
    @TableField("DAYS")
    private Integer days;

    @TableField("REASON")
    private String reason;

    /** 状态：0 草稿 1 审批中 2 通过 3 驳回 */
    @TableField("STATUS")
    private Integer status;

    @TableField("TENANT_ID")
    private String tenantId;
}
