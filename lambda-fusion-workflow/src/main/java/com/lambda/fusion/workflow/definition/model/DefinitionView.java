package com.lambda.fusion.workflow.definition.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Date;
import lombok.Data;

/**
 * 流程定义视图
 *
 * @author jin
 */
@Data
@Schema(description = "流程定义视图")
public class DefinitionView {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "流程编码")
    private String flowCode;

    @Schema(description = "流程名称")
    private String flowName;

    @Schema(description = "设计器模型（CLASSICS 经典 / MIMIC 仿钉钉）")
    private String modelValue;

    @Schema(description = "流程类别")
    private String category;

    @Schema(description = "流程版本")
    private String version;

    @Schema(description = "是否发布（0 未发布 1 已发布 9 失效）")
    private Integer isPublish;

    @Schema(description = "激活状态（0 挂起 1 激活）")
    private Integer activityStatus;

    @Schema(description = "审批表单是否自定义（Y/N）")
    private String formCustom;

    @Schema(description = "审批表单路径")
    private String formPath;

    @Schema(description = "监听器类型")
    private String listenerType;

    @Schema(description = "监听器路径")
    private String listenerPath;

    @Schema(description = "扩展字段（约定：存 bizType）")
    private String ext;

    @Schema(description = "创建时间")
    private Date createTime;

    @Schema(description = "更新时间")
    private Date updateTime;
}
