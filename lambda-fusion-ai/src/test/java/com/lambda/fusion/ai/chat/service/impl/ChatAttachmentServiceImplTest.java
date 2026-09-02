package com.lambda.fusion.ai.chat.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.attachment.ChatAttachmentPreviewTokenService;
import com.lambda.fusion.ai.chat.mapper.ChatAttachmentMapper;
import com.lambda.fusion.ai.chat.mapper.ChatSessionMapper;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.storage.AiFileStorage;
import com.lambda.fusion.ai.storage.AiFileStorageResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    private ChatAttachmentServiceImpl service;

    @BeforeEach
    void setUp() {
        attachmentMapper = mock(ChatAttachmentMapper.class);
        sessionMapper = mock(ChatSessionMapper.class);
        storage = mock(AiFileStorage.class);
        storageResolver = mock(AiFileStorageResolver.class);
        when(storageResolver.resolve("LOCAL")).thenReturn(storage);
        when(storageResolver.resolve((String) null)).thenReturn(storage);
        when(storage.type()).thenReturn("LOCAL");
        service = new ChatAttachmentServiceImpl(
                attachmentMapper,
                sessionMapper,
                storageResolver,
                new AiProperties(),
                mock(ChatAttachmentPreviewTokenService.class),
                mock(PlatformTransactionManager.class));
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

    private static ChatAttachmentEntity attachment(String sessionId, Long messageId) {
        ChatAttachmentEntity attachment = new ChatAttachmentEntity();
        attachment.setId("att-1");
        attachment.setSessionId(sessionId);
        attachment.setMessageId(messageId);
        return attachment;
    }
}
