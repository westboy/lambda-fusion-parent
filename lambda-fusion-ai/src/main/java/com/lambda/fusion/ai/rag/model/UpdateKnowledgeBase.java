package com.lambda.fusion.ai.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.Data;

@Data
@Schema(description = "更新知识库")
public class UpdateKnowledgeBase {

    @Schema(description = "知识库名称")
    private String name;

    @Schema(description = "知识库描述")
    private String description;

    @Schema(description = "检索策略: FULL_TEXT/VECTOR/HYBRID")
    private String retrievalMode;

    @Schema(description = "嵌入模型ID(VECTOR/HYBRID 必填，需为已启用的 EMBEDDING 类型模型)")
    private String embeddingModelId;

    @Schema(description = "检索条数(空走全局默认)")
    private Integer retrieveLimit;

    @Schema(description = "分数阈值(空走全局默认)")
    private BigDecimal scoreThreshold;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "是否启用")
    private Boolean enabled;
}
