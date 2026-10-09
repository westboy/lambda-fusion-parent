package com.lambda.fusion.workflow.instance.model;

import com.lambda.fusion.workflow.integration.BizInfo;
import com.lambda.fusion.workflow.integration.BizInfoAware;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Date;
import lombok.Data;

/**
 * 流程实例视图
 *
 * @author jin
 */
@Data
@Schema(description = "流程实例视图")
public class InstanceView implements BizInfoAware {

    @Schema(description = "实例 ID")
    private Long id;

    @Schema(description = "流程定义 ID")
    private Long definitionId;

    @Schema(description = "业务单据 ID")
    private String businessId;

    @Schema(description = "业务类型（发起时传入，供展示信息解析）")
    private String bizType;

    /** 提交时表单生效版本（实例变量 __formVersion；存量实例无 → null，渲染端回退当前发布版） */
    private Long formVersion;

    @Schema(description = "当前节点编码")
    private String nodeCode;

    @Schema(description = "当前节点名称")
    private String nodeName;

    @Schema(description = "当前节点类型（0 开始 1 中间 2 结束 3 互斥网关 4 并行网关）")
    private Integer nodeType;

    @Schema(description = "流程状态（0 待提交 1 审批中 2 通过 4 终止 5 作废 6 撤销 8 完成 9 退回 10 失效 11 拿回）")
    private String flowStatus;

    @Schema(description = "激活状态（0 挂起 1 激活）")
    private Integer activityStatus;

    @Schema(description = "发起人")
    private String createBy;

    @Schema(description = "发起时间")
    private Date createTime;

    @Schema(description = "业务展示信息（SPI 回填，可为空）")
    private BizInfo bizInfo;
}
