package com.lambda.fusion.ai.apps.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 登录后的聊天应用安全视图。仅暴露展示与对话所需字段，剔除系统提示词、模型、工具、
 * MCP、知识库、技能、子代理等内部运行配置（修复 /available 直接返回 AppEntity 的配置泄漏）。
 * 管理端详情仍返回完整 AppEntity。
 *
 * @author Jin
 */
@Data
@Schema(description = "可用应用安全视图")
public class AvailableApp {

    @Schema(description = "应用ID")
    private String id;

    @Schema(description = "应用名称")
    private String name;

    @Schema(description = "应用头像")
    private String avatar;

    @Schema(description = "应用描述")
    private String description;

    @Schema(description = "应用类型: CHAT|WORKSPACE")
    private String appType;

    @Schema(description = "绑定模型是否支持视觉(图片输入)")
    private Boolean supportsVision;
}
