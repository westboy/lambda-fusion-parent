package com.lambda.fusion.ai.rag.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("ai_knowledge_base")
@Schema(description = "知识库")
public class KnowledgeBaseEntity {

    @TableId("id")
    @Schema(description = "主键")
    private String id;

    @TableField("tenant_id")
    @Schema(description = "租户ID")
    private String tenantId;

    @TableField("name")
    @Schema(description = "知识库名称")
    private String name;

    @TableField("description")
    @Schema(description = "知识库描述")
    private String description;

    @TableField("retrieval_mode")
    @Schema(description = "检索策略: FULL_TEXT/VECTOR/HYBRID")
    private String retrievalMode;

    @TableField("embedding_model_id")
    @Schema(description = "嵌入模型ID(FULL_TEXT 可空)")
    private String embeddingModelId;

    @TableField("dimensions")
    @Schema(description = "向量维度(建表后不可变)")
    private Integer dimensions;

    @TableField("vector_table")
    @Schema(description = "向量表名(ai_kb_{id}, 每知识库一张)")
    private String vectorTable;

    @TableField("retrieve_limit")
    @Schema(description = "检索条数(空走全局默认)")
    private Integer retrieveLimit;

    @TableField("score_threshold")
    @Schema(description = "分数阈值(空走全局默认)")
    private BigDecimal scoreThreshold;

    @TableField("enabled")
    @Schema(description = "是否启用")
    private Boolean enabled;

    @TableField("remark")
    @Schema(description = "备注")
    private String remark;

    @TableField("created_at")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
