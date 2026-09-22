package com.lambda.fusion.workflow.task.model;

import com.lambda.fusion.workflow.integration.BizInfo;
import com.lambda.fusion.workflow.integration.BizInfoAware;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Date;
import lombok.Data;

/**
 * 待办任务视图（flow_task + 关联实例的业务信息）
 *
 * @author jin
 */
@Data
@Schema(description = "待办任务视图")
public class TaskView implements BizInfoAware {

    @Schema(description = "任务 ID")
    private Long id;

    @Schema(description = "实例 ID")
    private Long instanceId;

    @Schema(description = "定义 ID")
    private Long definitionId;

    @Schema(description = "业务单据 ID（服务层从实例补齐）")
    private String businessId;

    @Schema(description = "业务类型（服务层从实例补齐）")
    private String bizType;

    @Schema(description = "节点编码")
    private String nodeCode;

    @Schema(description = "节点名称")
    private String nodeName;

    @Schema(description = "节点类型（0 开始 1 中间 2 结束 3 互斥网关 4 并行网关）")
    private Integer nodeType;

    @Schema(description = "流程状态")
    private String flowStatus;

    @Schema(description = "到达时间")
    private Date createTime;

    @Schema(description = "业务展示信息（SPI 回填，可为空）")
    private BizInfo bizInfo;
}
