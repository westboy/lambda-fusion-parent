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

    @Schema(description = "提供方类型: dashscope/openai/ollama")
    @NotBlank(message = "提供方类型不能为空")
    private String providerType;

    @Schema(description = "服务地址")
    private String baseUrl;

    @Schema(description = "API Key 明文（入库前加密）")
    private String apiKey;

    @Schema(description = "是否启用")
    private Boolean enabled = Boolean.TRUE;

    @Schema(description = "该Key是否开通图像/视频等媒体生成能力")
    private Boolean enableMediaGen = Boolean.FALSE;

    @Schema(description = "备注")
    private String remark;
}
