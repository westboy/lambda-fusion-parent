package com.lambda.fusion.ai.chat.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("ai_chat_run_step")
@Schema(description = "对话运行执行步骤")
public class ChatRunStepEntity {

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @TableField("tenant_id")
    @Schema(description = "租户ID")
    private String tenantId;

    @TableField("session_id")
    @Schema(description = "会话ID")
    private String sessionId;

    @TableField("run_id")
    @Schema(description = "运行ID")
    private String runId;

    @TableField("phase_no")
    @Schema(description = "执行阶段号")
    private Integer phaseNo;

    @TableField("step_no")
    @Schema(description = "Run内步骤序号，从1开始")
    private Integer stepNo;

    @TableField("kind")
    @Schema(description = "步骤类型: reasoning/text/tool_call")
    private String kind;

    @TableField("content")
    @Schema(description = "步骤正文(reasoning/text)")
    private String content;

    @TableField("tool_call_id")
    @Schema(description = "工具调用ID")
    private String toolCallId;

    @TableField("tool_name")
    @Schema(description = "工具名称")
    private String toolName;

    @TableField("tool_args")
    @Schema(description = "工具参数JSON(已脱敏截断)")
    private String toolArgs;

    @TableField("tool_result")
    @Schema(description = "工具结果JSON(已脱敏截断)")
    private String toolResult;

    @TableField("status")
    @Schema(description = "工具调用状态")
    private String status;

    @TableField("started_at")
    @Schema(description = "步骤开始时间")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    @Schema(description = "步骤结束时间")
    private LocalDateTime finishedAt;

    @TableField("created_at")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
