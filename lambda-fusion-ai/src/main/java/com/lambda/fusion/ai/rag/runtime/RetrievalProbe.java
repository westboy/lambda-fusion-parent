package com.lambda.fusion.ai.rag.runtime;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 单知识库检索诊断结果；命中内容沿用生产检索的防腐层模型。 */
@Schema(description = "知识库检索测试结果")
public record RetrievalProbe(
        @Schema(description = "本次实际使用的检索条数") int effectiveLimit,
        @Schema(description = "本次实际使用的分数阈值") double effectiveScoreThreshold,
        @Schema(description = "检索耗时(毫秒)") long durationMs,
        @Schema(description = "按相似度降序排列的命中片段") List<RetrievedChunk> hits) {

    public RetrievalProbe {
        hits = List.copyOf(hits);
    }
}
