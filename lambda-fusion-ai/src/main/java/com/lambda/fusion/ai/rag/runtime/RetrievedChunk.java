package com.lambda.fusion.ai.rag.runtime;

/**
 * 检索命中的知识片段（防腐层自有返回类型，业务层不接触 AgentScope RAG 模型类）。
 *
 * @param content 片段文本
 * @param score 相似度分数
 * @param kbId 来源知识库ID
 * @param docId 来源文档ID
 * @param fileName 来源文件名
 * @param chunkId 切块ID
 * @param chunkIndex 文档内零基切块序号；旧数据可能为空
 * @param chunkCount 文档切块总数；旧数据可能为空
 * @param sectionPath 章节路径；无章节结构时为空
 * @param scoreType 分数语义：COSINE/BM25/RRF
 * @author Jin
 */
public record RetrievedChunk(
        String content,
        double score,
        String kbId,
        String docId,
        String fileName,
        String chunkId,
        Integer chunkIndex,
        Integer chunkCount,
        String sectionPath,
        String scoreType) {

    /** 兼容仅有单路向量检索时的旧构造方式；新代码应显式传入分数语义。 */
    public RetrievedChunk(
            String content,
            double score,
            String kbId,
            String docId,
            String fileName,
            String chunkId,
            Integer chunkIndex,
            Integer chunkCount,
            String sectionPath) {
        this(content, score, kbId, docId, fileName, chunkId, chunkIndex, chunkCount, sectionPath, "UNKNOWN");
    }

    public RetrievedChunk withScore(double newScore, String newScoreType) {
        return new RetrievedChunk(
                content, newScore, kbId, docId, fileName, chunkId, chunkIndex, chunkCount, sectionPath, newScoreType);
    }
}
