package com.lambda.fusion.ai.llm.model;

import com.lambda.fusion.ai.AiConstants.MediaGenerationOperation;
import com.lambda.fusion.ai.AiConstants.ModelType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import lombok.Data;

@Data
@Schema(description = "更新 LLM 模型")
public class UpdateLlmModel {

    @Schema(description = "所属提供方ID")
    private String providerId;

    @Schema(description = "模型显示名称")
    private String name;

    @Schema(description = "模型实际名称")
    private String modelName;

    @Schema(description = "模型类型: CHAT/EMBEDDING/IMAGE_GEN/VIDEO_GEN/AUDIO_GEN")
    private ModelType modelType;

    @Schema(description = "媒体创作操作；切换为非媒体模型时由服务端清空")
    private MediaGenerationOperation mediaOperation;

    @Schema(description = "默认温度")
    private BigDecimal defaultTemperature;

    @Schema(description = "默认最大输出 token 数")
    @Min(value = 1, message = "默认最大输出 token 数必须大于0")
    private Integer defaultMaxTokens;

    @Schema(description = "上下文窗口 token 数（输入与输出总量）")
    @Min(value = 1, message = "上下文窗口 token 数必须大于0")
    private Integer contextWindowTokens;

    @Schema(description = "是否支持视觉(图片): CHAT=可接受图片输入, EMBEDDING=可向量化图片")
    private Boolean supportsVision;

    @Schema(description = "是否启用")
    private Boolean enabled;
}
