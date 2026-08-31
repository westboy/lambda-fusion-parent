package com.lambda.fusion.ai.rag.store;

import com.lambda.fusion.ai.rag.model.entity.KnowledgeBaseEntity;
import com.lambda.fusion.ai.rag.runtime.IngestChunk;
import com.lambda.fusion.ai.rag.runtime.RetrievedChunk;
import java.util.List;

/**
 * 知识库全文索引后端契约。实现负责 chunk 全文索引的写入、检索与生命周期管理，并保证多租户隔离。
 * Lucene 实现（{@link LuceneKnowledgeIndex}）按知识库维护独立索引目录；
 * OceanBase 实现共享一张全文索引表，按 {@code kbId}（及可选 {@code tenantId}）过滤。
 *
 * @author Jin
 */
public interface KnowledgeFullTextIndex {

    /**
     * 原子替换同一文档的全部 chunk，失败时不会留下部分新文档。
     *
     * @param kb 知识库实体
     * @param docId 文档 ID
     * @param tenantId 租户 ID，可为空（系统级）
     * @param fileName 文件名，可为空
     * @param chunks 文档切块结果
     */
    void replaceDocument(
            KnowledgeBaseEntity kb, String docId, String tenantId, String fileName, List<IngestChunk> chunks);

    /**
     * 全文检索，返回按相关性降序的 chunk 列表。
     *
     * @param kb 知识库实体
     * @param queryText 查询文本
     * @param limit 最大返回条数
     * @return 检索结果，分数为后端相关性评分
     */
    List<RetrievedChunk> search(KnowledgeBaseEntity kb, String queryText, int limit);

    /**
     * 删除指定文档的全部全文索引数据。
     *
     * @param kb 知识库实体
     * @param docId 文档 ID
     */
    void deleteDocument(KnowledgeBaseEntity kb, String docId);

    /**
     * 判断知识库是否已存在全文索引数据。用于清理遗留索引等幂等决策。
     *
     * @param kb 知识库实体
     * @return 是否存在索引数据
     */
    boolean exists(KnowledgeBaseEntity kb);

    /**
     * 释放该知识库的索引句柄缓存；无句柄缓存的后端可为空实现。
     *
     * @param kb 知识库实体
     */
    void evict(KnowledgeBaseEntity kb);

    /**
     * 删除知识库的全部全文索引数据，用于知识库删除或索引重建。
     *
     * @param kb 知识库实体
     */
    void deleteKnowledgeBase(KnowledgeBaseEntity kb);
}
