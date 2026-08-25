package com.lambda.fusion.ai.apps.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
@Schema(description = "创建智能应用")
public class CreateApp {

    @Schema(description = "应用名称")
    @NotBlank(message = "应用名称不能为空")
    private String name;

    @Schema(description = "应用头像(图标名;空=按应用类型显示默认图标)")
    private String avatar;

    @Schema(description = "应用描述")
    private String description;

    @Schema(description = "系统提示词")
    private String systemPrompt;

    @Schema(description = "绑定模型ID")
    @NotBlank(message = "模型ID不能为空")
    private String modelId;

    @Schema(description = "图像生成模型ID(IMAGE_GEN 型;空=无生图能力)")
    private String imageModelId;

    @Schema(description = "视频生成模型ID(VIDEO_GEN 型;空=无视频生成)")
    private String videoModelId;

    @Schema(description = "音频生成模型ID(AUDIO_GEN 型;空=无语音生成)")
    private String audioModelId;

    @Schema(description = "ReAct 最大迭代数")
    private Integer maxIters;

    @Schema(description = "温度")
    private BigDecimal temperature;

    @Schema(description = "应用类型: CHAT|WORKSPACE")
    private String appType = "CHAT";

    @Schema(description = "是否自演化(WORKSPACE 型)")
    private Boolean selfEvolve = Boolean.FALSE;

    @Schema(description = "沙箱后端(WORKSPACE 型): HOST|DOCKER|KUBERNETES|E2B|DAYTONA|AGENTRUN")
    private String sandboxBackend = "HOST";

    @Schema(description = "受众: B|C|ALL")
    private String audience = "ALL";

    @Schema(description = "工具白名单")
    private List<String> toolsAllow;

    @Schema(description = "工具黑名单")
    private List<String> toolsDeny;

    @Schema(description = "MCP 服务ID列表")
    private List<String> mcpServerIds;

    @Schema(description = "知识库ID列表(对话时自动检索注入)")
    private List<String> knowledgeBaseIds;

    @Schema(description = "知识库检索模式: GENERIC(自动注入)|AGENTIC(工具自主检索)|BOTH;空=GENERIC")
    private String ragMode;

    @Schema(description = "子代理ID列表(WORKSPACE 型生效;主 agent 可调度)")
    private List<String> subAgentIds;

    @Schema(description = "技能白名单(技能名;仅 WORKSPACE 型生效)")
    private List<String> skillsAllow;

    @Schema(description = "技能黑名单(技能名;仅 WORKSPACE 型生效)")
    private List<String> skillsDeny;

    @Schema(description = "是否启用")
    private Boolean enabled = Boolean.TRUE;
}
