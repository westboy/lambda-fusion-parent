package com.lambda.fusion.ai.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import lombok.Data;

@Data
@Schema(description = "知识库检索测试参数")
public class TestKnowledgeRetrieval {

    @Schema(description = "检索问题")
    @NotBlank(message = "检索问题不能为空")
    private String query;

    @Schema(description = "临时检索条数(空取知识库或全局配置)")
    @Min(value = 1, message = "检索条数必须大于0")
    private Integer limit;

    @Schema(description = "临时向量余弦阈值(FULL_TEXT 模式忽略；空取知识库或全局配置)")
    @DecimalMin(value = "0.0", message = "分数阈值不能小于0")
    @DecimalMax(value = "1.0", message = "分数阈值不能大于1")
    private BigDecimal scoreThreshold;
}
