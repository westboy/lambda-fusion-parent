package com.lambda.fusion.workflow.instance.model;

import com.lambda.fusion.workflow.integration.BizInfo;
import com.lambda.fusion.workflow.integration.BizInfoAware;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Date;
import lombok.Data;

/**
 * 审批历史视图（flow_his_task）
 *
 * <p>业务关联字段（businessId/bizType）与展示信息由服务层从所属实例补齐，
 * 「我已办」列表与实例审批历史共用本模型。
 *
 * @author jin
 */
@Data
@Schema(description = "审批历史视图")
public class HistoryView implements BizInfoAware {

    @Schema(description = "历史记录 ID")
    private Long id;

    @Schema(description = "对应待办任务 ID")
    private Long taskId;

    @Schema(description = "所属实例 ID")
    private Long instanceId;

    @Schema(description = "业务单据 ID（服务层从实例补齐）")
    private String businessId;

    @Schema(description = "业务类型（服务层从实例补齐）")
    private String bizType;

    @Schema(description = "开始节点编码")
    private String nodeCode;

    @Schema(description = "开始节点名称")
    private String nodeName;

    @Schema(description = "目标节点编码")
    private String targetNodeCode;

    @Schema(description = "目标节点名称")
    private String targetNodeName;

    @Schema(description = "审批人")
    private String approver;

    @Schema(description = "协作方式（1 审批 2 转办 3 委派 4 会签 5 票签 6 加签 7 减签）")
    private Integer cooperateType;

    @Schema(description = "协作人")
    private String collaborator;

    @Schema(description = "流转类型（PASS 通过 REJECT 退回 NONE 无动作）")
    private String skipType;

    @Schema(description = "流程状态")
    private String flowStatus;

    @Schema(description = "审批意见")
    private String message;

    @Schema(description = "任务开始时间")
    private Date createTime;

    @Schema(description = "审批完成时间")
    private Date updateTime;

    @Schema(description = "业务展示信息（SPI 回填，可为空）")
    private BizInfo bizInfo;
}
