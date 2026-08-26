package com.lambda.fusion.ai.chat.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.attachment.ChatAttachmentPreviewTokenService;
import com.lambda.fusion.ai.chat.mapper.ChatAttachmentMapper;
import com.lambda.fusion.ai.chat.mapper.ChatSessionMapper;
import com.lambda.fusion.ai.chat.model.ChatAttachmentView;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 对话附件服务实现：负责附件的上传、消息绑定、下载预览与清理。
 *
 * <p>存储：上传先落临时文件再经 {@link AiFileStorage} 持久化，与知识库文档共用同一套存储后端
 * （复用 {@code rag.document-storage} 配置）；相对路径使用独立前缀
 * {@code chat/{sessionId}/{attachmentId}.{ext}}，与知识库文件相互隔离。
 *
 * <p>生命周期：未绑定消息的附件（{@code message_id IS NULL}）为孤儿附件，只能由 {@link #delete} 主动
 * 撤销或随 {@link #deleteBySession} 会话级联清理；已发送附件不单独删除，避免历史消息出现空洞引用。
 * 未引入定时清理任务。
 *
 * @author Jin
 */
@Slf4j
@Service
@RequiredArgsConstructor
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
            Map.entry("application/pdf", "pdf"));

    private final ChatAttachmentMapper chatAttachmentMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final AiFileStorageResolver storageResolver;
    private final AiProperties aiProperties;
    private final ChatAttachmentPreviewTokenService previewTokenService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatAttachmentEntity upload(String sessionId, MultipartFile file) {
        ChatSessionEntity session =
                chatSessionMapper.selectOwned(sessionId, AuthUtils.getUser().getUsername());
        if (session == null) {
            throw new AiBusinessException(AiErrorCode.CHAT_SESSION_NOT_FOUND, sessionId);
        }
        AiProperties.Chat.Attachment cfg = aiProperties.getChat().getAttachment();
        String extension = resolveExtension(file, cfg);
        validateSize(file, cfg);

        ChatAttachmentEntity entity = new ChatAttachmentEntity();
        entity.setId(IdUtil.getSnowflakeNextIdStr());
        entity.setSessionId(session.getId());
        entity.setFileName(StringUtils.defaultIfBlank(file.getOriginalFilename(), entity.getId()));
        entity.setFileType(extension);
        entity.setMimeType(file.getContentType());
        entity.setSizeBytes(file.getSize());
        entity.setCategory(resolveCategory(extension, cfg));
        entity.setCreatedAt(LocalDateTime.now());
        chatAttachmentMapper.insert(entity);

        // MultipartFile 内容在请求结束后即被清理，先落临时文件再持久化；finally 立即删除
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("chat-att-", "." + extension);
            file.transferTo(tempFile);
            AiFileStorage storage = storageResolver.resolve(
                    aiProperties.getRag().getDocumentStorage().getType());
            String relativeName = "chat/" + session.getId() + "/" + entity.getId() + "." + extension;
            entity.setStorageType(storage.type());
            entity.setStoragePath(storage.store(tempFile, relativeName, entity.getMimeType()));
            chatAttachmentMapper.updateById(entity);
        } catch (IOException e) {
            chatAttachmentMapper.deleteById(entity.getId());
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_STORAGE_ERROR, e);
        } catch (RuntimeException e) {
            deleteStoredFile(entity);
            chatAttachmentMapper.deleteById(entity.getId());
            throw e instanceof AiBusinessException aiBusinessException
                    ? aiBusinessException
                    : new AiBusinessException(AiErrorCode.ATTACHMENT_STORAGE_ERROR, e);
        } finally {
            deleteQuietly(tempFile);
        }
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatAttachmentEntity storeGenerated(String sessionId, Path source, String fileName, String mimeType) {
        long size = fileSize(source);
        long maxBytes =
                aiProperties.getChat().getAttachment().getGeneratedMedia().getMaxFileSizeMb() * 1024L * 1024L;
        if (size > maxBytes) {
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_SIZE_EXCEEDED, fileName);
        }
        String normalizedMimeType = normalizeMimeType(mimeType);
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
        entity.setCreatedAt(LocalDateTime.now());
        chatAttachmentMapper.insert(entity);

        try {
            AiFileStorage storage = storageResolver.resolve(
                    aiProperties.getRag().getDocumentStorage().getType());
            String relativeName = "chat/" + sessionId + "/" + entity.getId() + "." + extension;
            entity.setStorageType(storage.type());
            entity.setStoragePath(storage.store(source, relativeName, normalizedMimeType));
            chatAttachmentMapper.updateById(entity);
            return entity;
        } catch (RuntimeException e) {
            deleteStoredFile(entity);
            chatAttachmentMapper.deleteById(entity.getId());
            throw e instanceof AiBusinessException aiBusinessException
                    ? aiBusinessException
                    : new AiBusinessException(AiErrorCode.ATTACHMENT_STORAGE_ERROR, e);
        }
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
        if (StringUtils.isAnyBlank(attachmentId, sessionId, userId)
                || chatSessionMapper.selectOwned(sessionId, userId) == null) {
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_NOT_FOUND, attachmentId);
        }
        ChatAttachmentEntity entity = chatAttachmentMapper.selectById(attachmentId);
        if (entity == null || !sessionId.equals(entity.getSessionId()) || entity.getMessageId() == null) {
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
    public Optional<String> temporaryReadUrl(ChatAttachmentEntity entity, Duration ttl) {
        return storageResolver.resolve(entity.getStorageType()).temporaryReadUrl(entity.getStoragePath(), ttl);
    }

    @Override
    public ChatAttachmentView toView(ChatAttachmentEntity entity) {
        return ChatAttachmentView.of(entity, previewTokenService.generatePreviewUrl(entity));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String attachmentId) {
        ChatAttachmentEntity entity = loadOwned(attachmentId);
        if (entity.getMessageId() != null) {
            // 已发送附件不单独删除，随会话级联清理，避免历史消息出现空洞引用
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_NOT_FOUND, attachmentId);
        }
        deleteStoredFile(entity);
        chatAttachmentMapper.deleteById(attachmentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBySession(String sessionId) {
        LambdaQueryWrapper<ChatAttachmentEntity> query =
                new LambdaQueryWrapper<ChatAttachmentEntity>().eq(ChatAttachmentEntity::getSessionId, sessionId);
        List<ChatAttachmentEntity> attachments = chatAttachmentMapper.selectList(query);
        for (ChatAttachmentEntity attachment : attachments) {
            deleteStoredFile(attachment);
        }
        chatAttachmentMapper.delete(query);
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
        attachments.forEach(this::deleteStoredFile);
        if (!attachments.isEmpty()) {
            chatAttachmentMapper.delete(new LambdaQueryWrapper<ChatAttachmentEntity>()
                    .eq(ChatAttachmentEntity::getSessionId, sessionId)
                    .isNull(ChatAttachmentEntity::getMessageId)
                    .in(
                            ChatAttachmentEntity::getId,
                            attachments.stream()
                                    .map(ChatAttachmentEntity::getId)
                                    .toList()));
        }
    }

    // 按行记录的 storageType 路由删除（配置变更后旧附件仍可删）；失败仅告警不阻断
    private void deleteStoredFile(ChatAttachmentEntity attachment) {
        if (StringUtils.isBlank(attachment.getStorageType()) || StringUtils.isBlank(attachment.getStoragePath())) {
            return;
        }
        try {
            storageResolver.resolve(attachment.getStorageType()).delete(attachment.getStoragePath());
        } catch (RuntimeException e) {
            log.warn(
                    "删除对话附件原文件失败(att={}, path={}): {}",
                    attachment.getId(),
                    attachment.getStoragePath(),
                    e.getMessage());
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

    private static long fileSize(Path source) {
        try {
            return Files.size(source);
        } catch (IOException e) {
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_STORAGE_ERROR, e);
        }
    }

    private static String normalizeMimeType(String mimeType) {
        String value = StringUtils.substringBefore(StringUtils.defaultString(mimeType), ";")
                .trim()
                .toLowerCase(Locale.ROOT);
        return StringUtils.defaultIfBlank(value, "application/octet-stream");
    }

    private static String normalizeFileName(String fileName) {
        String value = StringUtils.defaultString(fileName).replace('\\', '/');
        value = StringUtils.substringAfterLast("/" + value, "/").replaceAll("[\\p{Cntrl}]", "");
        return StringUtils.left(value, 255);
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
        return CATEGORY_FILE;
    }

    private static void deleteQuietly(Path tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            log.warn("删除对话附件上传临时文件失败: {}, {}", tempFile, e.getMessage());
        }
    }
}
