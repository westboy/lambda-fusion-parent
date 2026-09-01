package com.lambda.fusion.ai.chat.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 用户通过创作入口显式选择的媒体类型；工具和参数仍由 AgentScope ToolSchema 决定。 */
@Data
@Schema(description = "媒体生成意图")
public class MediaGenerationRequest {

    @NotBlank(message = "媒体生成类型不能为空")
    @Pattern(regexp = "image|video|audio", message = "媒体生成类型不合法")
    @Schema(description = "生成类型: image|video|audio")
    private String kind;
}
