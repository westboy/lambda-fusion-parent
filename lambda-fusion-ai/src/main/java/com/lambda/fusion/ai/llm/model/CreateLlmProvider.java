package com.lambda.fusion.ai.llm.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "创建 LLM 提供方")
public class CreateLlmProvider {

    @Schema(description = "提供方名称")
    @NotBlank(message = "提供方名称不能为空")
    private String name;

    @Schema(description = "AgentScope 提供方类型")
    @NotBlank(message = "提供方类型不能为空")
    private String providerType;

    @Schema(description = "服务地址")
    private String baseUrl;

    @Schema(description = "API Key 明文（入库前加密）")
    private String apiKey;

    @Schema(description = "是否启用")
    private Boolean enabled = Boolean.TRUE;

    @Schema(description = "备注")
    private String remark;
}
