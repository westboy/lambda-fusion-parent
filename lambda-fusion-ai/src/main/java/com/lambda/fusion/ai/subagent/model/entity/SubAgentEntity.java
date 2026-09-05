package com.lambda.fusion.ai.subagent.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
@TableName(value = "ai_sub_agent", autoResultMap = true)
@Schema(description = "子代理定义")
public class SubAgentEntity {

    @TableId("id")
    @Schema(description = "主键")
    private String id;

    @TableField("tenant_id")
    @Schema(description = "租户ID")
    private String tenantId;

    @TableField("name")
    @Schema(description = "子代理名(运行时 key/agent_id)")
    private String name;

    @TableField("description")
    @Schema(description = "能力描述(主 agent 路由唯一依据)")
    private String description;

    @TableField("prompt")
    @Schema(description = "子代理系统提示词")
    private String prompt;

    @TableField("model_id")
    @Schema(description = "绑定模型ID(空=继承主 agent 模型)")
    private String modelId;

    @TableField("steps")
    @Schema(description = "最大 ReAct 迭代数(空=harness 默认)")
    private Integer steps;

    @TableField("temperature")
    @Schema(description = "温度")
    private BigDecimal temperature;

    @TableField("top_p")
    @Schema(description = "Top-P")
    private BigDecimal topP;

    @TableField(value = "tools_allow", typeHandler = JacksonTypeHandler.class)
    @Schema(description = "工具白名单(空=全继承父工具)")
    private List<String> toolsAllow;

    @TableField(value = "skills_allow", typeHandler = JacksonTypeHandler.class)
    @Schema(description = "技能白名单(空=全继承)")
    private List<String> skillsAllow;

    @TableField("workspace_mode")
    @Schema(description = "工作区模式: ISOLATED|SHARED")
    private String workspaceMode;

    @TableField("category")
    @Schema(description = "分类: SUB_AGENT(主Agent路由的子代理)|SCHEDULED_TASK(定时任务)")
    private String category;

    @TableField("schedule_mode")
    @Schema(description = "调度模式: NONE|CRON|FIXED_RATE|FIXED_DELAY(仅 SCHEDULED_TASK)")
    private String scheduleMode;

    @TableField("cron_expression")
    @Schema(description = "cron 表达式(CRON 模式)")
    private String cronExpression;

    @TableField("fixed_rate")
    @Schema(description = "固定频率毫秒(FIXED_RATE 模式)")
    private Long fixedRate;

    @TableField("fixed_delay")
    @Schema(description = "固定延迟毫秒(FIXED_DELAY 模式)")
    private Long fixedDelay;

    @TableField("initial_delay")
    @Schema(description = "首次执行前初始延迟毫秒")
    private Long initialDelay;

    @TableField("zone_id")
    @Schema(description = "cron 时区(如 Asia/Shanghai)")
    private String zoneId;

    @TableField("input_msg")
    @Schema(description = "定时触发初始输入(可选)")
    private String inputMsg;

    @TableField("schedule_enabled")
    @Schema(description = "调度启用态(仅 SCHEDULED_TASK;区别于 enabled 路由态)")
    private Boolean scheduleEnabled;

    @TableField("enabled")
    @Schema(description = "是否启用")
    private Boolean enabled;

    @TableField("remark")
    @Schema(description = "备注")
    private String remark;

    @TableField("created_by")
    @Schema(description = "创建人用户名(定时任务完成通知的默认接收人)")
    private String createdBy;

    @TableField("created_at")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
