package com.lambda.fusion.ai.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import lombok.Data;

@Data
@Schema(description = "创建知识库")
public class CreateKnowledgeBase {

    @Schema(description = "知识库名称")
    @NotBlank(message = "知识库名称不能为空")
    private String name;

    @Schema(description = "知识库描述")
    private String description;

    @Schema(description = "嵌入模型ID(需为已启用的 EMBEDDING 类型模型)")
    @NotBlank(message = "嵌入模型ID不能为空")
    private String embeddingModelId;

    @Schema(description = "向量维度(空取嵌入模型默认维度;建表后不可变)")
    @Min(value = 1, message = "向量维度必须大于0")
    private Integer dimensions;

    @Schema(description = "检索条数(空走全局默认)")
    private Integer retrieveLimit;

    @Schema(description = "分数阈值(空走全局默认)")
    private BigDecimal scoreThreshold;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "是否启用")
    private Boolean enabled = Boolean.TRUE;
}
