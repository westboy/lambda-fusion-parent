package com.lambda.fusion.ai.apps.model.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
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
@TableName(value = "ai_app", autoResultMap = true)
@Schema(description = "智能应用")
public class AppEntity {

    @TableId("id")
    @Schema(description = "主键")
    private String id;

    @TableField("tenant_id")
    @Schema(description = "租户ID(由租户插件填充/过滤;发布代码解析获得真实租户)")
    private String tenantId;

    @TableField("name")
    @Schema(description = "应用名称")
    private String name;

    @TableField("avatar")
    @Schema(description = "应用头像(图标名,如 lucide:bot;空=按应用类型显示默认图标)")
    private String avatar;

    @TableField("description")
    @Schema(description = "应用描述")
    private String description;

    @TableField("system_prompt")
    @Schema(description = "系统提示词")
    private String systemPrompt;

    @TableField("model_id")
    @Schema(description = "绑定模型ID")
    private String modelId;

    @TableField(value = "image_model_id", updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "图像生成模型ID(IMAGE_GEN 型;空=无生图能力)")
    private String imageModelId;

    @TableField(value = "video_model_id", updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "视频生成模型ID(VIDEO_GEN 型;空=无视频生成能力)")
    private String videoModelId;

    @TableField(value = "audio_model_id", updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "音频生成模型ID(AUDIO_GEN 型;空=无语音生成能力)")
    private String audioModelId;

    @TableField("max_iters")
    @Schema(description = "ReAct 最大迭代数")
    private Integer maxIters;

    @TableField("temperature")
    @Schema(description = "温度")
    private BigDecimal temperature;

    @TableField("app_type")
    @Schema(description = "应用类型: CHAT|WORKSPACE")
    private String appType;

    @TableField("self_evolve")
    @Schema(description = "是否自演化(WORKSPACE 型): agent 可写 workspace 并自维护记忆/技能")
    private Boolean selfEvolve;

    @TableField("sandbox_backend")
    @Schema(description = "沙箱后端(WORKSPACE 型): HOST|DOCKER|KUBERNETES|E2B|DAYTONA|AGENTRUN")
    private String sandboxBackend;

    @TableField("owner_id")
    @Schema(description = "所有者ID(空=平台预置能力;用户ID=独立应用,预留)")
    private String ownerId;

    @TableField("audience")
    @Schema(description = "受众: B|C|ALL")
    private String audience;

    @TableField("enable_image_upload")
    @Schema(description = "允许终端用户上传图片(需绑定模型支持视觉输入)")
    private Boolean enableImageUpload;

    @TableField("enable_file_upload")
    @Schema(description = "允许终端用户上传文档类附件")
    private Boolean enableFileUpload;

    @TableField(value = "tools_allow", typeHandler = JacksonTypeHandler.class)
    @Schema(description = "工具白名单")
    private List<String> toolsAllow;

    @TableField(value = "tools_deny", typeHandler = JacksonTypeHandler.class)
    @Schema(description = "工具黑名单")
    private List<String> toolsDeny;

    @TableField(value = "mcp_server_ids", typeHandler = JacksonTypeHandler.class)
    @Schema(description = "MCP 服务ID列表")
    private List<String> mcpServerIds;

    @TableField(value = "knowledge_base_ids", typeHandler = JacksonTypeHandler.class)
    @Schema(description = "知识库ID列表(对话时自动检索注入)")
    private List<String> knowledgeBaseIds;

    @TableField("rag_mode")
    @Schema(description = "知识库检索模式: GENERIC(自动注入)|AGENTIC(工具自主检索)|BOTH;空=GENERIC")
    private String ragMode;

    @TableField(value = "sub_agent_ids", typeHandler = JacksonTypeHandler.class)
    @Schema(description = "子代理ID列表(WORKSPACE 型生效;主 agent 可调度)")
    private List<String> subAgentIds;

    @TableField(value = "skills_allow", typeHandler = JacksonTypeHandler.class)
    @Schema(description = "技能白名单(技能名;仅 WORKSPACE 型生效)")
    private List<String> skillsAllow;

    @TableField(value = "skills_deny", typeHandler = JacksonTypeHandler.class)
    @Schema(description = "技能黑名单(技能名;仅 WORKSPACE 型生效)")
    private List<String> skillsDeny;

    @TableField("enabled")
    @Schema(description = "是否启用")
    private Boolean enabled;

    @TableField("publish_code")
    @Schema(description = "发布代码(32位无横线随机UUID;可空,全局唯一,非权限凭据)")
    private String publishCode;

    @TableField("publish_status")
    @Schema(description = "发布状态: UNPUBLISHED|PUBLISHED")
    private String publishStatus;

    @TableField("published_at")
    @Schema(description = "最近一次成功发布时间")
    private LocalDateTime publishedAt;

    @TableField("created_by")
    @Schema(description = "创建人")
    private String createdBy;

    @TableField("created_at")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @TableField(exist = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Schema(description = "绑定模型是否支持视觉(图片输入); 非表字段, 由服务层回填")
    private Boolean supportsVision;
}
