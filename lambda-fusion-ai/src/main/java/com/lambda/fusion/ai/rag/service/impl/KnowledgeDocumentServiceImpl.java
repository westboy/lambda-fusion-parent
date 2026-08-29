package com.lambda.fusion.ai.rag.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.fusion.ai.AiConstants.DocumentChunkStrategy;
import com.lambda.fusion.ai.AiConstants.DocumentStatus;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.rag.mapper.KnowledgeDocumentMapper;
import com.lambda.fusion.ai.rag.model.KnowledgeDocumentPage;
import com.lambda.fusion.ai.rag.model.entity.KnowledgeBaseEntity;
import com.lambda.fusion.ai.rag.model.entity.KnowledgeDocumentEntity;
import com.lambda.fusion.ai.rag.runtime.SimpleKnowledgeAdapter;
import com.lambda.fusion.ai.rag.service.DocumentIngestionService;
import com.lambda.fusion.ai.rag.service.KnowledgeBaseService;
import com.lambda.fusion.ai.rag.service.KnowledgeDocumentService;
import com.lambda.fusion.ai.storage.AiFileStorage;
import com.lambda.fusion.ai.storage.AiFileStorageResolver;
import com.lambda.fusion.core.utils.AuthUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    /** 支持的文档扩展名（ReaderInput 无 InputStream 支持，上传内容需先落临时文件）。 */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "doc", "docx", "txt", "md");

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AiProperties aiProperties;
    private final ObjectProvider<SimpleKnowledgeAdapter> adapterProvider;
    private final ObjectProvider<DocumentIngestionService> ingestionServiceProvider;
    private final AiFileStorageResolver storageResolver;

    @Override
    public Page<KnowledgeDocumentEntity> page(KnowledgeDocumentPage query) {
        return knowledgeDocumentMapper.selectPage(query.getPage(), query.getLambdaQueryWrapper());
    }

    @Override
    public KnowledgeDocumentEntity get(String kbId, String documentId) {
        return requireOwned(kbId, documentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocumentEntity upload(String kbId, MultipartFile file, String chunkStrategy) {
        KnowledgeBaseEntity kb = knowledgeBaseService.loadById(kbId);
        if (!Boolean.TRUE.equals(kb.getEnabled())) {
            throw new AiBusinessException(AiErrorCode.KB_DISABLED, kbId);
        }
        DocumentIngestionService ingestionService = ingestionServiceProvider.getIfAvailable();
        if (ingestionService == null) {
            throw new AiBusinessException(AiErrorCode.KB_RAG_NOT_ENABLED);
        }
        String extension = resolveExtension(file);
        KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
        entity.setId(IdUtil.getSnowflakeNextIdStr());
        entity.setTenantId(AuthUtils.getTenantId());
        entity.setKbId(kbId);
        entity.setFileName(file.getOriginalFilename());
        entity.setFileType(extension);
        entity.setChunkStrategy(resolveChunkStrategy(chunkStrategy).getCode());
        entity.setStatus(DocumentStatus.PENDING.getCode());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        knowledgeDocumentMapper.insert(entity);
        // MultipartFile 内容在请求结束后即被清理，异步入库前先落临时文件做持久化；
        // 持久化完成后（或失败）由 finally 立即删除，异步入库从 storage 取回，不再依赖该临时文件
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("kb-upload-", "." + extension);
            file.transferTo(tempFile);
            // 原文件持久化必须在异步入库之前同步完成；持久化失败即上传失败，避免只留下向量数据
            AiFileStorage storage = storageResolver.resolve(
                    aiProperties.getRag().getDocumentStorage().getType());
            String relativeName = kbId + "/" + entity.getId() + "." + extension;
            entity.setStorageType(storage.type());
            entity.setStoragePath(storage.store(tempFile, relativeName, file.getContentType()));
            entity.setUpdatedAt(LocalDateTime.now());
            knowledgeDocumentMapper.updateById(entity);
        } catch (IOException e) {
            markFailed(entity, "临时文件写入失败: " + e.getMessage());
            throw new AiBusinessException(AiErrorCode.DOCUMENT_PARSE_FAILED, e);
        } catch (RuntimeException e) {
            markFailed(entity, "原文件存储失败: " + e.getMessage());
            throw e instanceof AiBusinessException aiBusinessException
                    ? aiBusinessException
                    : new AiBusinessException(AiErrorCode.DOCUMENT_STORAGE_ERROR, e);
        } finally {
            deleteQuietly(tempFile);
        }
        scheduleIngestion(ingestionService, List.of(entity.getId()));
        return entity;
    }

    @Override
    public void download(String kbId, String documentId, OutputStream out) {
        KnowledgeDocumentEntity entity = requireOwned(kbId, documentId);
        if (StringUtils.isBlank(entity.getStorageType()) || StringUtils.isBlank(entity.getStoragePath())) {
            throw new AiBusinessException(AiErrorCode.DOCUMENT_NOT_FOUND, documentId);
        }
        storageResolver.resolve(entity.getStorageType()).download(entity.getStoragePath(), out);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String kbId, String documentId) {
        KnowledgeDocumentEntity entity = requireOwned(kbId, documentId);
        deleteIndexData(entity);
        deleteStoredFile(entity);
        knowledgeDocumentMapper.deleteById(documentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByKbId(String kbId) {
        List<KnowledgeDocumentEntity> documents = knowledgeDocumentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>().eq(KnowledgeDocumentEntity::getKbId, kbId));
        for (KnowledgeDocumentEntity document : documents) {
            deleteIndexData(document);
            deleteStoredFile(document);
        }
        knowledgeDocumentMapper.delete(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>().eq(KnowledgeDocumentEntity::getKbId, kbId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocumentEntity reingest(String kbId, String documentId, String chunkStrategy) {
        KnowledgeDocumentEntity entity = requireOwned(kbId, documentId);
        if (StringUtils.isBlank(entity.getStorageType()) || StringUtils.isBlank(entity.getStoragePath())) {
            throw new AiBusinessException(AiErrorCode.DOCUMENT_STORAGE_ERROR, "文档原文件缺失，请重新上传");
        }
        DocumentIngestionService ingestionService = ingestionServiceProvider.getIfAvailable();
        if (ingestionService == null) {
            throw new AiBusinessException(AiErrorCode.KB_RAG_NOT_ENABLED);
        }
        DocumentChunkStrategy resolvedStrategy =
                resolveChunkStrategy(StringUtils.defaultIfBlank(chunkStrategy, entity.getChunkStrategy()));
        // 显式 set null 清空 errorMsg/chunkCount（updateById 默认跳过 null 字段，无法清空）
        knowledgeDocumentMapper.update(
                null,
                new LambdaUpdateWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getId, documentId)
                        .set(KnowledgeDocumentEntity::getStatus, DocumentStatus.PENDING.getCode())
                        .set(KnowledgeDocumentEntity::getChunkStrategy, resolvedStrategy.getCode())
                        .set(KnowledgeDocumentEntity::getErrorMsg, null)
                        .set(KnowledgeDocumentEntity::getChunkCount, null)
                        .set(KnowledgeDocumentEntity::getUpdatedAt, LocalDateTime.now()));
        entity.setStatus(DocumentStatus.PENDING.getCode());
        entity.setChunkStrategy(resolvedStrategy.getCode());
        entity.setErrorMsg(null);
        entity.setChunkCount(null);
        scheduleIngestion(ingestionService, List.of(documentId));
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reingestByKbId(String kbId) {
        List<KnowledgeDocumentEntity> documents =
                knowledgeDocumentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getKbId, kbId)
                        .eq(KnowledgeDocumentEntity::getStatus, DocumentStatus.READY.getCode()));
        if (documents.isEmpty()) {
            return;
        }
        DocumentIngestionService ingestionService = ingestionServiceProvider.getIfAvailable();
        if (ingestionService == null) {
            throw new AiBusinessException(AiErrorCode.KB_RAG_NOT_ENABLED);
        }
        List<String> documentIds = new ArrayList<>(documents.size());
        for (KnowledgeDocumentEntity document : documents) {
            if (StringUtils.isBlank(document.getStorageType()) || StringUtils.isBlank(document.getStoragePath())) {
                throw new AiBusinessException(AiErrorCode.DOCUMENT_STORAGE_ERROR, "文档原文件缺失: " + document.getId());
            }
            knowledgeDocumentMapper.update(
                    null,
                    new LambdaUpdateWrapper<KnowledgeDocumentEntity>()
                            .eq(KnowledgeDocumentEntity::getId, document.getId())
                            .set(KnowledgeDocumentEntity::getStatus, DocumentStatus.PENDING.getCode())
                            .set(KnowledgeDocumentEntity::getErrorMsg, null)
                            .set(KnowledgeDocumentEntity::getChunkCount, null)
                            .set(KnowledgeDocumentEntity::getUpdatedAt, LocalDateTime.now()));
            documentIds.add(document.getId());
        }
        scheduleIngestion(ingestionService, documentIds);
    }

    // 按 entity 记录的 storageType（而非当前配置）路由清理，配置变更后旧文件仍可删；失败仅告警不阻断
    private void deleteStoredFile(KnowledgeDocumentEntity document) {
        if (StringUtils.isBlank(document.getStorageType()) || StringUtils.isBlank(document.getStoragePath())) {
            return;
        }
        try {
            storageResolver.resolve(document.getStorageType()).delete(document.getStoragePath());
        } catch (RuntimeException e) {
            log.warn("删除文档原文件失败(doc={}, path={}): {}", document.getId(), document.getStoragePath(), e.getMessage());
        }
    }

    // 检索功能未启用（无 adapter Bean）时仅删库行并告警，派生索引由人工清理
    private void deleteIndexData(KnowledgeDocumentEntity document) {
        SimpleKnowledgeAdapter adapter = adapterProvider.getIfAvailable();
        if (adapter == null) {
            log.warn("知识库检索功能未启用，跳过派生索引删除(doc={})", document.getId());
            return;
        }
        adapter.deleteDocument(document.getKbId(), document.getId());
    }

    private static void scheduleIngestion(DocumentIngestionService ingestionService, List<String> documentIds) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    documentIds.forEach(ingestionService::ingest);
                }
            });
        } else {
            documentIds.forEach(ingestionService::ingest);
        }
    }

    private void markFailed(KnowledgeDocumentEntity entity, String errorMsg) {
        entity.setStatus(DocumentStatus.FAILED.getCode());
        entity.setErrorMsg(StringUtils.left(errorMsg, 1000));
        entity.setUpdatedAt(LocalDateTime.now());
        knowledgeDocumentMapper.updateById(entity);
    }

    private static void deleteQuietly(Path tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            log.warn("删除知识库上传临时文件失败: {}, {}", tempFile, e.getMessage());
        }
    }

    private static String resolveExtension(MultipartFile file) {
        String fileName = StringUtils.defaultString(file.getOriginalFilename());
        String extension = StringUtils.substringAfterLast(fileName, ".").toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new AiBusinessException(AiErrorCode.DOCUMENT_TYPE_NOT_SUPPORTED, fileName);
        }
        return extension;
    }

    private static DocumentChunkStrategy resolveChunkStrategy(String code) {
        String resolvedCode = StringUtils.defaultIfBlank(code, DocumentChunkStrategy.AUTO.getCode());
        DocumentChunkStrategy strategy = DocumentChunkStrategy.of(resolvedCode);
        if (strategy == null) {
            throw new AiBusinessException(AiErrorCode.DOCUMENT_CHUNK_STRATEGY_INVALID, code);
        }
        return strategy;
    }

    private KnowledgeDocumentEntity requireOwned(String kbId, String documentId) {
        KnowledgeDocumentEntity entity = requireExists(documentId);
        if (!kbId.equals(entity.getKbId())) {
            throw new AiBusinessException(AiErrorCode.DOCUMENT_NOT_FOUND, documentId);
        }
        return entity;
    }

    private KnowledgeDocumentEntity requireExists(String id) {
        KnowledgeDocumentEntity entity = knowledgeDocumentMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>().eq(KnowledgeDocumentEntity::getId, id));
        if (entity == null) {
            throw new AiBusinessException(AiErrorCode.DOCUMENT_NOT_FOUND, id);
        }
        return entity;
    }
}
