package com.lambda.fusion.ai.llm.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "更新 LLM 提供方")
public class UpdateLlmProvider {

    @Schema(description = "提供方名称")
    private String name;

    @Schema(description = "AgentScope 提供方类型")
    private String providerType;

    @Schema(description = "服务地址")
    private String baseUrl;

    @Schema(description = "API Key 明文（为空则保留原密文）")
    private String apiKey;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "备注")
    private String remark;
}
