package com.lambda.fusion.ai.rag.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.fusion.ai.rag.model.KnowledgeDocumentPage;
import com.lambda.fusion.ai.rag.model.entity.KnowledgeDocumentEntity;
import java.io.OutputStream;
import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeDocumentService {

    Page<KnowledgeDocumentEntity> page(KnowledgeDocumentPage query);

    /**
     * 查询文档详情（校验归属知识库）；不存在抛出业务异常。
     */
    KnowledgeDocumentEntity get(String kbId, String documentId);

    /**
     * 上传文档：落 PENDING 行 + 原文件持久化后异步解析切块入库，立即返回文档行。
     * 原文件持久化失败即上传失败（避免只留下向量数据）。
     *
     * @param kbId 知识库ID
     * @param file 上传文件（pdf/doc/docx/txt/md）
     * @param chunkStrategy 切割策略（AUTO/WHOLE/HEADING/PARAGRAPH/TOKEN）
     * @return 文档行（状态 PENDING）
     */
    KnowledgeDocumentEntity upload(String kbId, MultipartFile file, String chunkStrategy);

    /**
     * 下载文档原文件：按文档记录的 storageType 路由到对应存储后端写出内容。
     */
    void download(String kbId, String documentId, OutputStream out);

    /**
     * 删除文档：同步删除整文档的全部派生索引与原文件。
     */
    void delete(String kbId, String documentId);

    /**
     * 级联删除知识库下全部文档（含派生索引与原文件）。供知识库删除调用。
     */
    void deleteByKbId(String kbId);

    /**
     * 重新解析切块入库：复用已持久化的原文件，异步替换当前检索方式使用的派生索引。
     * 用于失败文档重试或 READY 文档换策略重跑；原文件缺失抛业务异常。
     *
     * @param chunkStrategy 新切割策略；空值沿用文档当前策略
     * @return 重置为 PENDING 的文档行
     */
    KnowledgeDocumentEntity reingest(String kbId, String documentId, String chunkStrategy);

    /**
     * 知识库检索模式或嵌入模型变化后，重新异步入库全部就绪文档。
     */
    void reingestByKbId(String kbId);

    /**
     * 显式清空知识库派生索引，并重新异步入库全部有原文件且非 PENDING 的文档。
     *
     * @return 已提交重建的文档数
     */
    int rebuildIndexesByKbId(String kbId);
}
