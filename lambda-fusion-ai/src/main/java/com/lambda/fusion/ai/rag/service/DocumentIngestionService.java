package com.lambda.fusion.ai.rag.service;

import com.lambda.fusion.ai.AiConstants.DocumentChunkStrategy;
import com.lambda.fusion.ai.AiConstants.DocumentStatus;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.rag.mapper.KnowledgeDocumentMapper;
import com.lambda.fusion.ai.rag.model.entity.KnowledgeDocumentEntity;
import com.lambda.fusion.ai.rag.runtime.DocumentChunker;
import com.lambda.fusion.ai.rag.runtime.IngestChunk;
import com.lambda.fusion.ai.rag.runtime.SimpleKnowledgeAdapter;
import com.lambda.fusion.ai.runtime.document.DocumentTextExtractor;
import com.lambda.fusion.ai.storage.AiFileStorage;
import com.lambda.fusion.ai.storage.AiFileStorageResolver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Async;

/**
 * 文档入库管线：从已持久化的原文件读取 → 抽取全文 → 按文档策略切块 → 向量库写入 → 更新文档行状态。
 * 按 document 行记录的 {@code storageType} 路由取回原文件（临时文件生命周期闭合在
 * {@link #ingest(String)} 内，不跨同步/异步边界传递）；异步执行，调用方在 {@code afterCommit}
 * 触发保证行已提交可见；格式解析与切割语义收敛到 {@link DocumentChunker}，
 * 向量文档组装由 {@link SimpleKnowledgeAdapter} 完成。
 *
 * @author Jin
 */
@Slf4j
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class DocumentIngestionService {

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final SimpleKnowledgeAdapter simpleKnowledgeAdapter;
    private final AiFileStorageResolver storageResolver;
    private final AiProperties aiProperties;

    /**
     * 异步执行入库；任何失败只落文档行状态（FAILED + error_msg），不向调用方抛出，
     * 内容从已持久化的原文件取回，临时文件由本方法创建并清理。
     *
     * @param documentId 文档行ID
     */
    @Async
    public void ingest(String documentId) {
        Path tempFile = null;
        try {
            KnowledgeDocumentEntity document = knowledgeDocumentMapper.selectById(documentId);
            if (document == null) {
                return;
            }
            // 按 document 行记录的 storageType 路由（配置变更后老文档仍能命中其原始存储后端）
            AiFileStorage storage = storageResolver.resolve(document.getStorageType());
            tempFile = Files.createTempFile("kb-ingest-", "." + StringUtils.defaultString(document.getFileType()));
            try (OutputStream out = Files.newOutputStream(tempFile)) {
                storage.download(document.getStoragePath(), out);
            }

            String text = DocumentTextExtractor.extractText(document.getFileType(), tempFile);
            DocumentChunkStrategy strategy = resolveStrategy(document.getChunkStrategy());
            List<IngestChunk> chunks = DocumentChunker.chunk(
                    documentId, text, strategy, aiProperties.getRag().getChunking());
            simpleKnowledgeAdapter.addChunks(
                    document.getKbId(), document.getId(), document.getTenantId(), document.getFileName(), chunks);
            document.setStatus(DocumentStatus.READY.getCode());
            document.setChunkStrategy(strategy.getCode());
            document.setChunkCount(chunks.size());
            document.setErrorMsg(null);
            document.setUpdatedAt(LocalDateTime.now());
            knowledgeDocumentMapper.updateById(document);
            log.info("知识库文档入库完成: doc={}, chunks={}", documentId, chunks.size());
        } catch (Exception e) {
            log.error("知识库文档入库失败: doc={}", documentId, e);
            markFailed(documentId, e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("删除知识库入库临时文件失败: {}, {}", tempFile, e.getMessage());
                }
            }
        }
    }

    private void markFailed(String documentId, Exception e) {
        try {
            KnowledgeDocumentEntity document = knowledgeDocumentMapper.selectById(documentId);
            if (document == null) {
                return;
            }
            document.setStatus(DocumentStatus.FAILED.getCode());
            document.setErrorMsg(StringUtils.left(e.getMessage(), 1000));
            document.setUpdatedAt(LocalDateTime.now());
            knowledgeDocumentMapper.updateById(document);
        } catch (Exception updateError) {
            log.warn("更新文档失败状态出错: doc={}, {}", documentId, updateError.getMessage());
        }
    }

    private static DocumentChunkStrategy resolveStrategy(String code) {
        if (StringUtils.isBlank(code)) {
            return DocumentChunkStrategy.AUTO;
        }
        DocumentChunkStrategy strategy = DocumentChunkStrategy.of(code);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown document chunk strategy: " + code);
        }
        return strategy;
    }
}
