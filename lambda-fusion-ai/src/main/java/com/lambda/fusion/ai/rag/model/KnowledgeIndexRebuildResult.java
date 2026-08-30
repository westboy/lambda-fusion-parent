package com.lambda.fusion.ai.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "知识库索引重建提交结果")
public record KnowledgeIndexRebuildResult(
        @Schema(description = "当前检索方式") String retrievalMode,
        @Schema(description = "已提交异步重建的文档数") int queuedDocumentCount,
        @Schema(description = "重建是否会调用嵌入模型并消耗 Embedding Token") boolean embeddingTokensRequired) {}
