package com.lambda.fusion.ai.chat.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.ai.AiConstants.ChatAttachmentSource;
import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.apps.service.AppService;
import com.lambda.fusion.ai.chat.attachment.ChatAttachmentPreviewPolicy;
import com.lambda.fusion.ai.chat.attachment.ChatAttachmentPreviewTokenService;
import com.lambda.fusion.ai.chat.mapper.ChatAttachmentMapper;
import com.lambda.fusion.ai.chat.mapper.ChatRunMapper;
import com.lambda.fusion.ai.chat.mapper.ChatSessionMapper;
import com.lambda.fusion.ai.chat.model.ChatAttachmentView;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.chat.support.ChatFileSupport;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.storage.AiFileResource;
import com.lambda.fusion.ai.storage.AiFileStorage;
import com.lambda.fusion.ai.storage.AiFileStorageResolver;
import com.lambda.fusion.core.utils.AuthUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 对话附件服务实现：负责附件的上传、消息绑定、下载预览与清理。
 *
 * <p>存储：上传先落临时文件再经 {@link AiFileStorage} 持久化，与知识库文档共用同一套存储后端
 * （复用 {@code rag.document-storage} 配置）；相对路径使用独立前缀
 * {@code chat/{sessionId}/{attachmentId}.{ext}}，与知识库文件相互隔离。
 *
 * <p>写入顺序：上传与生成采用"先落库、后存储、再回填路径"三段流程，两个数据库步骤各以独立短事务
 * （{@code REQUIRES_NEW}）提交，存储写入不处于任何数据库事务内；既避免存储耗时长期占用数据库连接，
 * 也消除"事务提交阶段失败"留下的存储孤儿。任一步骤失败即补偿删除已写文件与记录；回填前先预置
 * 存储路径，保证写入中断时半成品对象仍可按路径删除。调用方事务回滚不影响已提交的附件记录，
 * 记录以孤儿形态留存并交由回收任务处理。
 *
 * <p>生命周期：未绑定消息的附件（{@code message_id IS NULL}）为孤儿附件，只能由 {@link #delete} 主动
 * 撤销、随 {@link #deleteBySession} 会话级联清理，或由定时任务 {@link ChatAttachmentOrphanCleaner}
 * 按创建时间回收；已发送附件不单独删除，避免历史消息出现空洞引用。
 *
 * @author Jin
 */
@Slf4j
@Service
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class ChatAttachmentServiceImpl implements ChatAttachmentService {

    private static final String CATEGORY_IMAGE = "IMAGE";
    private static final String CATEGORY_DOCUMENT = "DOCUMENT";
    private static final String CATEGORY_AUDIO = "AUDIO";
    private static final String CATEGORY_VIDEO = "VIDEO";
    private static final String CATEGORY_FILE = "FILE";
    private static final List<String> SAFE_IMAGE_MIME_TYPES =
            List.of("image/jpeg", "image/png", "image/gif", "image/webp", "image/avif", "image/bmp");
    private static final List<String> SAFE_AUDIO_MIME_TYPES =
            List.of("audio/mpeg", "audio/mp4", "audio/wav", "audio/ogg", "audio/webm", "audio/aac", "audio/flac");
    private static final List<String> SAFE_VIDEO_MIME_TYPES =
            List.of("video/mp4", "video/webm", "video/quicktime", "video/ogg", "video/mpeg");
    private static final Map<String, String> MIME_EXTENSIONS = Map.ofEntries(
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/png", "png"),
            Map.entry("image/gif", "gif"),
            Map.entry("image/webp", "webp"),
            Map.entry("audio/mpeg", "mp3"),
            Map.entry("audio/mp4", "m4a"),
            Map.entry("audio/wav", "wav"),
            Map.entry("audio/ogg", "ogg"),
            Map.entry("video/mp4", "mp4"),
            Map.entry("video/webm", "webm"),
            Map.entry("video/quicktime", "mov"),
            Map.entry("application/pdf", "pdf"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
            Map.entry("text/plain", "txt"),
            Map.entry("text/markdown", "md"),
            Map.entry("text/csv", "csv"),
            Map.entry("application/json", "json"),
            Map.entry("application/xml", "xml"),
            Map.entry("text/html", "html"));
    private static final List<String> READABLE_DOCUMENT_MIME_TYPES = List.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "text/markdown",
            "text/csv",
            "application/json",
            "application/xml",
            "text/html");

    private final ChatAttachmentMapper chatAttachmentMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatRunMapper chatRunMapper;
    private final AiFileStorageResolver storageResolver;
    private final AiProperties aiProperties;
    private final ChatAttachmentPreviewTokenService previewTokenService;
    private final AppService appService;
    /** 存储写入前后的落库短事务；独立于调用方事务，保证附件记录不随外层回滚丢失。 */
    private final TransactionTemplate requiresNewTx;

    public ChatAttachmentServiceImpl(
            ChatAttachmentMapper chatAttachmentMapper,
            ChatSessionMapper chatSessionMapper,
            ChatRunMapper chatRunMapper,
            AiFileStorageResolver storageResolver,
            AiProperties aiProperties,
            ChatAttachmentPreviewTokenService previewTokenService,
            PlatformTransactionManager transactionManager,
            AppService appService) {
        this.chatAttachmentMapper = chatAttachmentMapper;
        this.chatSessionMapper = chatSessionMapper;
        this.chatRunMapper = chatRunMapper;
        this.storageResolver = storageResolver;
        this.aiProperties = aiProperties;
        this.previewTokenService = previewTokenService;
        this.appService = appService;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTx = template;
    }

    @Override
    public ChatAttachmentEntity upload(String sessionId, MultipartFile file) {
        ChatSessionEntity session =
                chatSessionMapper.selectOwned(sessionId, AuthUtils.getUser().getUsername());
        if (session == null) {
            throw new AiBusinessException(AiErrorCode.CHAT_SESSION_NOT_FOUND, sessionId);
        }
        AiProperties.Chat.Attachment cfg = aiProperties.getChat().getAttachment();
        String extension = resolveExtension(file, cfg);
        validateSize(file, cfg);
        String category = resolveCategory(extension, cfg);
        validateUploadPermission(session, category);

        ChatAttachmentEntity entity = new ChatAttachmentEntity();
        entity.setId(IdUtil.getSnowflakeNextIdStr());
        entity.setSessionId(session.getId());
        entity.setFileName(StringUtils.defaultIfBlank(normalizeFileName(file.getOriginalFilename()), entity.getId()));
        entity.setFileType(extension);
        entity.setMimeType(file.getContentType());
        entity.setSizeBytes(file.getSize());
        entity.setCategory(category);
        entity.setSource(ChatAttachmentSource.UPLOADED.getCode());
        entity.setCreatedAt(LocalDateTime.now());

        // MultipartFile 内容在请求结束后即被清理，先落临时文件再持久化；finally 立即删除
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("chat-att-", "." + extension);
            file.transferTo(tempFile);
            inNewTransaction(() -> chatAttachmentMapper.insert(entity));
            AiFileStorage storage = storageResolver.resolve(
                    aiProperties.getRag().getDocumentStorage().getType());
            String relativeName = "chat/" + session.getId() + "/" + entity.getId() + "." + extension;
            // 先预置存储路径再写入，保证存储中断时补偿删除仍能定位半成品对象
            entity.setStorageType(storage.type());
            entity.setStoragePath(relativeName);
            entity.setStoragePath(storage.store(tempFile, relativeName, entity.getMimeType()));
            inNewTransaction(() -> chatAttachmentMapper.updateById(entity));
            return entity;
        } catch (IOException e) {
            compensateFailedStore(entity);
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_STORAGE_ERROR, e);
        } catch (RuntimeException e) {
            compensateFailedStore(entity);
            throw e instanceof AiBusinessException aiBusinessException
                    ? aiBusinessException
                    : new AiBusinessException(AiErrorCode.ATTACHMENT_STORAGE_ERROR, e);
        } finally {
            deleteQuietly(tempFile);
        }
    }

    @Override
    public ChatAttachmentEntity storeGenerated(String sessionId, Path source, String fileName, String mimeType) {
        return storeGenerated(sessionId, source, fileName, mimeType, null);
    }

    @Override
    public ChatAttachmentEntity storeGenerated(
            String sessionId, Path source, String fileName, String mimeType, Path pdfPreviewSource) {
        long size = fileSize(source);
        long maxBytes =
                aiProperties.getChat().getAttachment().getGeneratedMedia().getMaxFileSizeMb() * 1024L * 1024L;
        if (size > maxBytes) {
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_SIZE_EXCEEDED, fileName);
        }
        if (pdfPreviewSource != null && fileSize(pdfPreviewSource) > maxBytes) {
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_SIZE_EXCEEDED, fileName + " 预览");
        }
        String normalizedMimeType = ChatFileSupport.normalizeMimeType(mimeType);
        String normalizedFileName = normalizeFileName(fileName);
        String extension = resolveGeneratedExtension(normalizedFileName, normalizedMimeType);

        ChatAttachmentEntity entity = new ChatAttachmentEntity();
        entity.setId(IdUtil.getSnowflakeNextIdStr());
        entity.setSessionId(sessionId);
        entity.setFileName(StringUtils.defaultIfBlank(normalizedFileName, entity.getId() + "." + extension));
        entity.setFileType(extension);
        entity.setMimeType(normalizedMimeType);
        entity.setSizeBytes(size);
        entity.setCategory(resolveGeneratedCategory(normalizedMimeType));
        entity.setSource(ChatAttachmentSource.GENERATED.getCode());
        entity.setCreatedAt(LocalDateTime.now());

        try {
            inNewTransaction(() -> chatAttachmentMapper.insert(entity));
            AiFileStorage storage = storageResolver.resolve(
                    aiProperties.getRag().getDocumentStorage().getType());
            entity.setStorageType(storage.type());
            entity.setStoragePath("chat/" + sessionId + "/" + entity.getId() + "." + extension);
            entity.setStoragePath(storage.store(source, entity.getStoragePath(), normalizedMimeType));
            if (pdfPreviewSource != null) {
                entity.setPreviewStoragePath("chat/" + sessionId + "/" + entity.getId() + ".preview.pdf");
                entity.setPreviewStoragePath(storage.store(
                        pdfPreviewSource, entity.getPreviewStoragePath(), ChatAttachmentPreviewPolicy.PDF_MIME_TYPE));
            }
            inNewTransaction(() -> chatAttachmentMapper.updateById(entity));
            return entity;
        } catch (RuntimeException e) {
            compensateFailedStore(entity);
            throw e instanceof AiBusinessException aiBusinessException
                    ? aiBusinessException
                    : new AiBusinessException(AiErrorCode.ATTACHMENT_STORAGE_ERROR, e);
        }
    }

    /** 存储写入失败后的补偿：删除已写文件（含半成品路径）与已落库记录，两者都允许不存在的幂等。 */
    private void compensateFailedStore(ChatAttachmentEntity entity) {
        try {
            deleteStoredFile(entity);
        } catch (RuntimeException deleteFailure) {
            log.warn(
                    "补偿删除附件存储文件失败(att={}, path={}): {}",
                    entity.getId(),
                    entity.getStoragePath(),
                    deleteFailure.getMessage());
        }
        chatAttachmentMapper.deleteById(entity.getId());
    }

    /** 在独立短事务中执行落库动作；失败向上抛出，由调用方统一补偿。 */
    private void inNewTransaction(Runnable action) {
        requiresNewTx.executeWithoutResult(status -> action.run());
    }

    @Override
    public ChatAttachmentEntity loadOwned(String attachmentId) {
        ChatAttachmentEntity entity = chatAttachmentMapper.selectById(attachmentId);
        if (entity == null) {
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_NOT_FOUND, attachmentId);
        }
        ChatSessionEntity session = chatSessionMapper.selectOwned(
                entity.getSessionId(), AuthUtils.getUser().getUsername());
        if (session == null) {
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_NOT_FOUND, attachmentId);
        }
        return entity;
    }

    @Override
    public ChatAttachmentEntity loadBoundInSession(String attachmentId, String sessionId, String userId) {
        if (StringUtils.isAnyBlank(attachmentId, sessionId, userId)) {
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_NOT_FOUND, attachmentId);
        }
        ChatAttachmentEntity entity = chatAttachmentMapper.selectBoundOwned(attachmentId, sessionId, userId);
        if (entity == null) {
            // 不区分不存在、跨会话和尚未发送，避免通过工具探测其他附件状态。
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_NOT_FOUND, attachmentId);
        }
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<ChatAttachmentEntity> bindToMessage(
            ChatSessionEntity session, List<String> attachmentIds, Long messageId) {
        List<ChatAttachmentEntity> attachments = new java.util.ArrayList<>();
        for (String attachmentId : attachmentIds) {
            LambdaQueryWrapper<ChatAttachmentEntity> query = new LambdaQueryWrapper<ChatAttachmentEntity>()
                    .eq(ChatAttachmentEntity::getId, attachmentId)
                    .eq(ChatAttachmentEntity::getSessionId, session.getId());
            ChatAttachmentEntity entity = chatAttachmentMapper.selectOne(query);
            if (entity == null
                    || (entity.getMessageId() != null && !entity.getMessageId().equals(messageId))) {
                throw new AiBusinessException(AiErrorCode.ATTACHMENT_NOT_FOUND, attachmentId);
            }
            entity.setMessageId(messageId);
            chatAttachmentMapper.updateById(entity);
            attachments.add(entity);
        }
        return attachments;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<ChatAttachmentEntity> bindGeneratedToMessage(
            ChatSessionEntity session, List<String> attachmentIds, Long messageId) {
        List<ChatAttachmentEntity> attachments = new java.util.ArrayList<>();
        for (String attachmentId : attachmentIds) {
            // 只认本会话内「工具生成且未绑定」的孤儿附件；伪造/缺失/越界/已绑定一律跳过，
            // 工具输出里的 attachment:<id> 引用不是可信事实，不能让它终结整条 Run。
            ChatAttachmentEntity entity = chatAttachmentMapper.selectOne(new LambdaQueryWrapper<ChatAttachmentEntity>()
                    .eq(ChatAttachmentEntity::getId, attachmentId)
                    .eq(ChatAttachmentEntity::getSessionId, session.getId())
                    .eq(ChatAttachmentEntity::getSource, ChatAttachmentSource.GENERATED.getCode())
                    .isNull(ChatAttachmentEntity::getMessageId));
            if (entity == null) {
                log.warn("生成附件终态绑定跳过（不存在/来源不符/已绑定/跨会话）: attachmentId={}, sessionId={}", attachmentId, session.getId());
                continue;
            }
            entity.setMessageId(messageId);
            chatAttachmentMapper.updateById(entity);
            attachments.add(entity);
        }
        return attachments;
    }

    @Override
    public List<ChatAttachmentEntity> listByMessageIds(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        return chatAttachmentMapper.selectList(new LambdaQueryWrapper<ChatAttachmentEntity>()
                .in(ChatAttachmentEntity::getMessageId, messageIds)
                .orderByAsc(ChatAttachmentEntity::getMessageId)
                .orderByAsc(ChatAttachmentEntity::getId));
    }

    @Override
    public void download(String attachmentId, OutputStream out) {
        writeTo(loadOwned(attachmentId), out);
    }

    @Override
    public ChatAttachmentEntity loadByIdForPreview(String attachmentId) {
        ChatAttachmentEntity entity = chatAttachmentMapper.selectById(attachmentId);
        if (entity == null) {
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_NOT_FOUND, attachmentId);
        }
        return entity;
    }

    @Override
    public void writeTo(ChatAttachmentEntity entity, OutputStream out) {
        storageResolver.resolve(entity.getStorageType()).download(entity.getStoragePath(), out);
    }

    @Override
    public AiFileResource openContent(ChatAttachmentEntity entity) {
        return storageResolver.resolve(entity.getStorageType()).open(entity.getStoragePath());
    }

    @Override
    public AiFileResource openPreviewContent(ChatAttachmentEntity entity) {
        if (!ChatAttachmentPreviewPolicy.hasDerivedPreview(entity)) {
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_NOT_FOUND, entity == null ? null : entity.getId());
        }
        return storageResolver.resolve(entity.getStorageType()).open(entity.getPreviewStoragePath());
    }

    @Override
    public Optional<String> temporaryReadUrl(ChatAttachmentEntity entity, Duration ttl) {
        return storageResolver.resolve(entity.getStorageType()).temporaryReadUrl(entity.getStoragePath(), ttl);
    }

    @Override
    public Optional<String> temporaryPreviewReadUrl(ChatAttachmentEntity entity, Duration ttl) {
        if (!ChatAttachmentPreviewPolicy.hasDerivedPreview(entity)) {
            return Optional.empty();
        }
        return storageResolver.resolve(entity.getStorageType()).temporaryReadUrl(entity.getPreviewStoragePath(), ttl);
    }

    @Override
    public ChatAttachmentView toView(ChatAttachmentEntity entity) {
        return ChatAttachmentView.of(
                entity,
                previewTokenService.generatePreviewUrl(entity),
                previewTokenService.generateDownloadUrl(entity),
                ChatAttachmentPreviewPolicy.previewMimeType(entity));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String attachmentId) {
        ChatAttachmentEntity entity = loadOwned(attachmentId);
        if (entity.getMessageId() != null) {
            // 已发送附件不单独删除，随会话级联清理，避免历史消息出现空洞引用
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_NOT_FOUND, attachmentId);
        }
        // 工具生成且尚未绑定的附件是活跃 Run 的终结投影，运行期间禁止删除（否则终态投影缺失）。
        if (ChatAttachmentSource.GENERATED.getCode().equals(entity.getSource())
                && chatRunMapper.exists(new LambdaQueryWrapper<ChatRunEntity>()
                        .eq(ChatRunEntity::getSessionId, entity.getSessionId())
                        .notIn(ChatRunEntity::getStatus, ChatRunStatus.terminalNames()))) {
            throw new AiBusinessException(AiErrorCode.CHAT_RUN_ALREADY_ACTIVE, entity.getSessionId());
        }
        // 条件删除兜底并发绑定：检查与删除之间被绑定时按不可删除处理。
        int removed = chatAttachmentMapper.delete(new LambdaQueryWrapper<ChatAttachmentEntity>()
                .eq(ChatAttachmentEntity::getId, entity.getId())
                .isNull(ChatAttachmentEntity::getMessageId));
        if (removed != 1) {
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_NOT_FOUND, attachmentId);
        }
        deleteStoredFilesAfterCommit(List.of(entity));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBySession(String sessionId) {
        LambdaQueryWrapper<ChatAttachmentEntity> query =
                new LambdaQueryWrapper<ChatAttachmentEntity>().eq(ChatAttachmentEntity::getSessionId, sessionId);
        List<ChatAttachmentEntity> attachments = chatAttachmentMapper.selectList(query);
        chatAttachmentMapper.delete(query);
        deleteStoredFilesAfterCommit(attachments);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void discardGenerated(String sessionId, Collection<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<ChatAttachmentEntity> query = new LambdaQueryWrapper<ChatAttachmentEntity>()
                .eq(ChatAttachmentEntity::getSessionId, sessionId)
                .isNull(ChatAttachmentEntity::getMessageId)
                .in(ChatAttachmentEntity::getId, attachmentIds);
        List<ChatAttachmentEntity> attachments = chatAttachmentMapper.selectList(query);
        if (!attachments.isEmpty()) {
            chatAttachmentMapper.delete(new LambdaQueryWrapper<ChatAttachmentEntity>()
                    .eq(ChatAttachmentEntity::getSessionId, sessionId)
                    .isNull(ChatAttachmentEntity::getMessageId)
                    .in(
                            ChatAttachmentEntity::getId,
                            attachments.stream()
                                    .map(ChatAttachmentEntity::getId)
                                    .toList()));
            deleteStoredFilesAfterCommit(attachments);
        }
    }

    @Override
    public int purgeOrphans(LocalDateTime cutoff, int limit) {
        List<ChatAttachmentEntity> orphans =
                chatAttachmentMapper.selectList(new LambdaQueryWrapper<ChatAttachmentEntity>()
                        .isNull(ChatAttachmentEntity::getMessageId)
                        .lt(ChatAttachmentEntity::getCreatedAt, cutoff)
                        .last("LIMIT " + Math.max(1, limit)));
        int purged = 0;
        for (ChatAttachmentEntity orphan : orphans) {
            // 先删文件后删记录：文件删除失败时保留记录留下一轮重试，
            // 否则将永久失去存储对象的定位依据。
            if (!deleteStoredFile(orphan)) {
                continue;
            }
            // 条件删除兜底并发绑定：选中后又被绑定的附件已不再是孤儿，跳过不删。
            purged += chatAttachmentMapper.delete(new LambdaQueryWrapper<ChatAttachmentEntity>()
                    .eq(ChatAttachmentEntity::getId, orphan.getId())
                    .isNull(ChatAttachmentEntity::getMessageId));
        }
        if (purged > 0) {
            log.info("已回收孤儿对话附件: count={}, cutoff={}", purged, cutoff);
        }
        return purged;
    }

    /** 数据库记录提交成功后再删除实体文件，避免外层事务回滚时留下指向已删除文件的附件记录。 */
    private void deleteStoredFilesAfterCommit(Collection<ChatAttachmentEntity> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        List<ChatAttachmentEntity> snapshot = List.copyOf(attachments);
        Runnable cleanup = () -> snapshot.forEach(this::deleteStoredFile);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
            return;
        }
        cleanup.run();
    }

    // 按行记录的 storageType 路由删除（配置变更后旧附件仍可删）；全部成功或本就缺失才返回 true
    private boolean deleteStoredFile(ChatAttachmentEntity attachment) {
        if (StringUtils.isBlank(attachment.getStorageType())) {
            return true;
        }
        return deleteStoredPath(attachment, attachment.getStoragePath(), "原文件")
                & deleteStoredPath(attachment, attachment.getPreviewStoragePath(), "派生预览");
    }

    private boolean deleteStoredPath(ChatAttachmentEntity attachment, String storagePath, String label) {
        if (StringUtils.isBlank(storagePath)) {
            return true;
        }
        try {
            storageResolver.resolve(attachment.getStorageType()).delete(storagePath);
            return true;
        } catch (RuntimeException e) {
            log.warn("删除对话附件{}失败(att={}, path={}): {}", label, attachment.getId(), storagePath, e.getMessage());
            return false;
        }
    }

    private static String resolveExtension(MultipartFile file, AiProperties.Chat.Attachment cfg) {
        String fileName = StringUtils.defaultString(file.getOriginalFilename());
        String extension = StringUtils.substringAfterLast(fileName, ".").toLowerCase(Locale.ROOT);
        boolean supported =
                cfg.getImageTypes().contains(extension) || cfg.getDocTypes().contains(extension);
        if (!supported) {
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_TYPE_NOT_SUPPORTED, fileName);
        }
        return extension;
    }

    private static void validateSize(MultipartFile file, AiProperties.Chat.Attachment cfg) {
        long maxBytes = cfg.getMaxFileSizeMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_SIZE_EXCEEDED, file.getOriginalFilename());
        }
    }

    private static String resolveCategory(String extension, AiProperties.Chat.Attachment cfg) {
        return cfg.getImageTypes().contains(extension) ? CATEGORY_IMAGE : CATEGORY_DOCUMENT;
    }

    /**
     * 用户上传的应用级权限边界：图片 = 应用开启图片上传；其余类别 = 应用开启附件上传。
     * 可见性/启用态已在会话建立阶段校验，此处仅按会话归属应用读开关；图片与模型视觉能力的组合
     * （supportsVision && enableImageUpload）在消息组装侧拦截，此处只管「应用是否允许该类别上传」。
     */
    private void validateUploadPermission(ChatSessionEntity session, String category) {
        AppEntity app = appService.loadById(session.getAppId());
        if (CATEGORY_IMAGE.equals(category)) {
            if (!Boolean.TRUE.equals(app.getEnableImageUpload())) {
                throw new AiBusinessException(AiErrorCode.ATTACHMENT_IMAGE_UPLOAD_DISABLED);
            }
        } else if (!Boolean.TRUE.equals(app.getEnableFileUpload())) {
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_FILE_UPLOAD_DISABLED);
        }
    }

    private static long fileSize(Path source) {
        try {
            return Files.size(source);
        } catch (IOException e) {
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_STORAGE_ERROR, e);
        }
    }

    private static String normalizeFileName(String fileName) {
        return StringUtils.left(ChatFileSupport.cleanFileName(fileName), 255);
    }

    private static String resolveGeneratedExtension(String fileName, String mimeType) {
        String extension = StringUtils.substringAfterLast(fileName, ".").toLowerCase(Locale.ROOT);
        if (extension.matches("[a-z0-9]{1,10}")) {
            return extension;
        }
        return MIME_EXTENSIONS.getOrDefault(mimeType, "bin");
    }

    private static String resolveGeneratedCategory(String mimeType) {
        if (SAFE_IMAGE_MIME_TYPES.contains(mimeType)) {
            return CATEGORY_IMAGE;
        }
        if (SAFE_AUDIO_MIME_TYPES.contains(mimeType)) {
            return CATEGORY_AUDIO;
        }
        if (SAFE_VIDEO_MIME_TYPES.contains(mimeType)) {
            return CATEGORY_VIDEO;
        }
        if (READABLE_DOCUMENT_MIME_TYPES.contains(mimeType)) {
            return CATEGORY_DOCUMENT;
        }
        return CATEGORY_FILE;
    }

    private static void deleteQuietly(Path tempFile) {
        ChatFileSupport.deleteQuietly(tempFile, "对话附件上传临时文件");
    }
}
