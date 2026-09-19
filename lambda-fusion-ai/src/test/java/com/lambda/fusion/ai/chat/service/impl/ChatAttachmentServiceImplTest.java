package com.lambda.fusion.ai.chat.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.ChatAttachmentSource;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.apps.service.AppService;
import com.lambda.fusion.ai.chat.attachment.ChatAttachmentPreviewTokenService;
import com.lambda.fusion.ai.chat.mapper.ChatAttachmentMapper;
import com.lambda.fusion.ai.chat.mapper.ChatSessionMapper;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.storage.AiFileStorage;
import com.lambda.fusion.ai.storage.AiFileStorageResolver;
import com.lambda.fusion.core.utils.AuthUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ChatAttachmentServiceImplTest {

    @TempDir
    Path tempDir;

    private ChatAttachmentMapper attachmentMapper;
    private ChatSessionMapper sessionMapper;
    private AiFileStorageResolver storageResolver;
    private AiFileStorage storage;
    private AppService appService;
    private ChatAttachmentServiceImpl service;

    @BeforeEach
    void setUp() {
        attachmentMapper = mock(ChatAttachmentMapper.class);
        sessionMapper = mock(ChatSessionMapper.class);
        storage = mock(AiFileStorage.class);
        storageResolver = mock(AiFileStorageResolver.class);
        appService = mock(AppService.class);
        when(storageResolver.resolve("LOCAL")).thenReturn(storage);
        when(storageResolver.resolve((String) null)).thenReturn(storage);
        when(storage.type()).thenReturn("LOCAL");
        service = new ChatAttachmentServiceImpl(
                attachmentMapper,
                sessionMapper,
                storageResolver,
                new AiProperties(),
                mock(ChatAttachmentPreviewTokenService.class),
                mock(PlatformTransactionManager.class),
                appService);
    }

    @Test
    void shouldLoadOnlySentAttachmentOwnedByRuntimeUserAndSession() {
        ChatAttachmentEntity attachment = attachment("session-1", 10L);
        when(attachmentMapper.selectBoundOwned("att-1", "session-1", "zhangsan"))
                .thenReturn(attachment);

        assertThat(service.loadBoundInSession("att-1", "session-1", "zhangsan")).isSameAs(attachment);
        verify(attachmentMapper).selectBoundOwned("att-1", "session-1", "zhangsan");
        verifyNoInteractions(sessionMapper);
    }

    @Test
    void shouldRejectAttachmentOutsideOwnedBoundQuery() {
        when(attachmentMapper.selectBoundOwned("att-1", "session-1", "zhangsan"))
                .thenReturn(null);
        assertThatThrownBy(() -> service.loadBoundInSession("att-1", "session-1", "zhangsan"))
                .isInstanceOf(AiBusinessException.class);
        verifyNoInteractions(sessionMapper);
    }

    @Test
    void shouldStoreAndDeleteOfficePreviewWithOriginalAttachment() throws Exception {
        when(storage.store(any(Path.class), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        Path original = Files.createTempFile("generated-document-", ".docx");
        Path preview = Files.createTempFile("generated-preview-", ".pdf");
        Files.writeString(original, "office");
        Files.writeString(preview, "%PDF-1.7");
        try {
            ChatAttachmentEntity stored = service.storeGenerated(
                    "session-1",
                    original,
                    "report.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    preview);

            assertThat(stored.getCategory()).isEqualTo("DOCUMENT");
            assertThat(stored.getStoragePath()).endsWith(".docx");
            assertThat(stored.getPreviewStoragePath()).endsWith(".preview.pdf");

            when(attachmentMapper.selectList(any())).thenReturn(List.of(stored));
            service.deleteBySession("session-1");
            verify(storage).delete(stored.getStoragePath());
            verify(storage).delete(stored.getPreviewStoragePath());
        } finally {
            Files.deleteIfExists(original);
            Files.deleteIfExists(preview);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "txt,text/plain",
        "md,text/markdown",
        "csv,text/csv",
        "json,application/json",
        "xml,application/xml",
        "html,text/html",
        "htm,text/html"
    })
    void shouldClassifyGeneratedUtf8TextAsReadableDocument(String extension, String mimeType) throws Exception {
        when(storage.store(any(Path.class), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        Path source = tempDir.resolve("artifact." + extension);
        Files.writeString(source, "UTF-8 文本 " + extension);

        ChatAttachmentEntity stored = service.storeGenerated("session-1", source, "生成结果." + extension, mimeType);

        assertThat(stored.getCategory()).isEqualTo("DOCUMENT");
        assertThat(stored.getFileType()).isEqualTo(extension);
    }

    @Test
    void shouldDeleteStoredFilesOnlyAfterSessionTransactionCommits() {
        ChatAttachmentEntity stored = attachment("session-1", 10L);
        stored.setStorageType("LOCAL");
        stored.setStoragePath("chat/session-1/original.txt");
        stored.setPreviewStoragePath("chat/session-1/original.preview.pdf");
        when(attachmentMapper.selectList(any())).thenReturn(List.of(stored));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.deleteBySession("session-1");

            verify(attachmentMapper).delete(any());
            verifyNoInteractions(storage);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(storage).delete(stored.getStoragePath());
            verify(storage).delete(stored.getPreviewStoragePath());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldKeepStoredFilesWhenSessionTransactionRollsBack() {
        ChatAttachmentEntity stored = attachment("session-1", 10L);
        stored.setStorageType("LOCAL");
        stored.setStoragePath("chat/session-1/original.txt");
        stored.setPreviewStoragePath("chat/session-1/original.preview.pdf");
        when(attachmentMapper.selectList(any())).thenReturn(List.of(stored));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.deleteBySession("session-1");

            verify(attachmentMapper).delete(any());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization ->
                            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verifyNoInteractions(storage);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldCompensateRowAndStoredFileWhenGeneratedStoreFails() throws Exception {
        when(storage.store(any(Path.class), anyString(), anyString())).thenThrow(new IllegalStateException("oss down"));
        Path source = tempDir.resolve("generated.png");
        Files.writeString(source, "png");

        assertThatThrownBy(() -> service.storeGenerated("session-1", source, "pic.png", "image/png"))
                .isInstanceOf(AiBusinessException.class);

        // 存储路径已预置（以附件 ID 命名），补偿删除能定位失败前写入的半成品对象；数据库记录同步删除
        verify(storage).delete(org.mockito.ArgumentMatchers.endsWith(".png"));
        verify(attachmentMapper).deleteById(anyString());
    }

    @Test
    void shouldPurgeOrphanAttachmentsFilesBeforeRows() {
        ChatAttachmentEntity orphan = attachment("session-1", null);
        orphan.setStorageType("LOCAL");
        orphan.setStoragePath("chat/session-1/orphan.txt");
        when(attachmentMapper.selectList(any())).thenReturn(List.of(orphan));
        when(attachmentMapper.deleteById("att-1")).thenReturn(1);

        int purged = service.purgeOrphans(java.time.LocalDateTime.now().minusHours(1), 100);

        assertThat(purged).isEqualTo(1);
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(storage, attachmentMapper);
        inOrder.verify(storage).delete("chat/session-1/orphan.txt");
        inOrder.verify(attachmentMapper).deleteById("att-1");
    }

    @Test
    void shouldPurgeNothingWhenNoOrphanAttachments() {
        when(attachmentMapper.selectList(any())).thenReturn(List.of());

        int purged = service.purgeOrphans(java.time.LocalDateTime.now().minusHours(1), 100);

        assertThat(purged).isZero();
        verifyNoInteractions(storage);
        verify(attachmentMapper, org.mockito.Mockito.never()).deleteById(anyString());
    }

    @Test
    void shouldRejectImageUploadWhenAppDisablesImageUpload() {
        ChatSessionEntity session = sessionOf("app-1");
        when(sessionMapper.selectOwned("session-1", "zhangsan")).thenReturn(session);
        AppEntity app = new AppEntity();
        app.setEnableImageUpload(false);
        app.setEnableFileUpload(true);
        when(appService.loadById("app-1")).thenReturn(app);

        try (MockedStatic<AuthUtils> auth = Mockito.mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(user("zhangsan"));
            MockMultipartFile image = new MockMultipartFile("file", "pic.png", "image/png", new byte[] {1});

            assertThatThrownBy(() -> service.upload("session-1", image))
                    .isInstanceOf(AiBusinessException.class)
                    .satisfies(e -> assertThat(((AiBusinessException) e).getCode())
                            .isEqualTo(AiErrorCode.ATTACHMENT_IMAGE_UPLOAD_DISABLED.getCode()));
        }
        verifyNoInteractions(attachmentMapper);
    }

    @Test
    void shouldRejectDocumentUploadWhenAppDisablesFileUpload() {
        ChatSessionEntity session = sessionOf("app-1");
        when(sessionMapper.selectOwned("session-1", "zhangsan")).thenReturn(session);
        AppEntity app = new AppEntity();
        app.setEnableImageUpload(true);
        app.setEnableFileUpload(false);
        when(appService.loadById("app-1")).thenReturn(app);

        try (MockedStatic<AuthUtils> auth = Mockito.mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(user("zhangsan"));
            MockMultipartFile doc = new MockMultipartFile("file", "notes.txt", "text/plain", new byte[] {1});

            assertThatThrownBy(() -> service.upload("session-1", doc))
                    .isInstanceOf(AiBusinessException.class)
                    .satisfies(e -> assertThat(((AiBusinessException) e).getCode())
                            .isEqualTo(AiErrorCode.ATTACHMENT_FILE_UPLOAD_DISABLED.getCode()));
        }
        verifyNoInteractions(attachmentMapper);
    }

    @Test
    void shouldRejectUploadWhenSessionNotOwned() {
        when(sessionMapper.selectOwned("session-1", "zhangsan")).thenReturn(null);
        try (MockedStatic<AuthUtils> auth = Mockito.mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(user("zhangsan"));
            MockMultipartFile doc = new MockMultipartFile("file", "notes.txt", "text/plain", new byte[] {1});

            assertThatThrownBy(() -> service.upload("session-1", doc)).isInstanceOf(AiBusinessException.class);
        }
        verifyNoInteractions(appService);
    }

    @Test
    void shouldBindOnlyGeneratedOrphanAttachments() {
        ChatSessionEntity session = sessionOf("app-1");
        ChatAttachmentEntity generated = attachment("session-1", null);
        generated.setSource(ChatAttachmentSource.GENERATED.getCode());
        when(attachmentMapper.selectOne(any())).thenReturn(generated);

        List<ChatAttachmentEntity> bound = service.bindGeneratedToMessage(session, List.of("att-1"), 10L);

        assertThat(bound).hasSize(1);
        assertThat(generated.getMessageId()).isEqualTo(10L);
        verify(attachmentMapper).updateById(generated);
    }

    @Test
    void shouldSkipForgedMissingOrForeignGeneratedAttachmentIds() {
        // 伪造/缺失/来源不符/已绑定的引用一律查不到（查询带 generated+孤儿条件），降级为跳过。
        ChatSessionEntity session = sessionOf("app-1");
        when(attachmentMapper.selectOne(any())).thenReturn(null);

        List<ChatAttachmentEntity> bound = service.bindGeneratedToMessage(session, List.of("att-x", "att-y"), 10L);

        assertThat(bound).isEmpty();
        verify(attachmentMapper, never()).updateById(any(ChatAttachmentEntity.class));
    }

    private static ChatSessionEntity sessionOf(String appId) {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setAppId(appId);
        session.setUserId("zhangsan");
        return session;
    }

    private static com.lambda.fusion.core.identity.UserDetails user(String username) {
        com.lambda.fusion.core.identity.UserDetails user = new com.lambda.fusion.core.identity.UserDetails();
        user.setUsername(username);
        return user;
    }

    private static ChatAttachmentEntity attachment(String sessionId, Long messageId) {
        ChatAttachmentEntity attachment = new ChatAttachmentEntity();
        attachment.setId("att-1");
        attachment.setSessionId(sessionId);
        attachment.setMessageId(messageId);
        return attachment;
    }
}
