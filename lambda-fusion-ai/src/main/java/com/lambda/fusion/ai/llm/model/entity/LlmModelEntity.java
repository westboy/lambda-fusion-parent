package com.lambda.fusion.ai.llm.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lambda.fusion.ai.AiConstants.MediaGenerationOperation;
import com.lambda.fusion.ai.AiConstants.ModelType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("ai_llm_model")
@Schema(description = "LLM 模型配置")
public class LlmModelEntity {

    @TableId("id")
    @Schema(description = "主键")
    private String id;

    @TableField("provider_id")
    @Schema(description = "所属提供方ID")
    private String providerId;

    @TableField("name")
    @Schema(description = "模型显示名称")
    private String name;

    @TableField("model_name")
    @Schema(description = "模型实际名称")
    private String modelName;

    @TableField("model_type")
    @Schema(description = "模型类型: CHAT|EMBEDDING|IMAGE_GEN|VIDEO_GEN|AUDIO_GEN")
    private ModelType modelType;

    @TableField("media_operation")
    @Schema(description = "媒体创作操作；非媒体模型为空")
    private MediaGenerationOperation mediaOperation;

    @TableField("default_temperature")
    @Schema(description = "默认温度")
    private BigDecimal defaultTemperature;

    @TableField("default_max_tokens")
    @Schema(description = "默认最大输出 token 数")
    private Integer defaultMaxTokens;

    @TableField("context_window_tokens")
    @Schema(description = "上下文窗口 token 数（输入与输出总量）")
    private Integer contextWindowTokens;

    @TableField("supports_vision")
    @Schema(description = "是否支持视觉(图片): CHAT=可接受图片输入, EMBEDDING=可向量化图片")
    private Boolean supportsVision;

    @TableField("enabled")
    @Schema(description = "是否启用")
    private Boolean enabled;

    @TableField("created_at")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
