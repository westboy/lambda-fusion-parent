package com.lambda.fusion.ai.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.attachment.ChatAttachmentPreviewTokenService;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.storage.AiFileResource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ChatAttachmentControllerTest {

    @Test
    void shouldServeSingleLocalByteRange() throws Exception {
        ChatAttachmentService attachmentService = mock(ChatAttachmentService.class);
        ChatAttachmentPreviewTokenService tokenService = mock(ChatAttachmentPreviewTokenService.class);
        ChatAttachmentController controller =
                new ChatAttachmentController(attachmentService, tokenService, new AiProperties());
        ChatAttachmentEntity attachment = attachment("123", "VIDEO", "video/mp4", 10);
        when(attachmentService.loadOwned("123")).thenReturn(attachment);
        when(attachmentService.openContent(attachment))
                .thenReturn(new AiFileResource(
                        new ByteArrayInputStream("0123456789".getBytes(StandardCharsets.UTF_8)), 10));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.RANGE, "bytes=2-5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.download("123", request, response);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.PARTIAL_CONTENT.value());
        assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 2-5/10");
        assertThat(response.getHeader(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getContentAsString()).isEqualTo("2345");
    }

    @Test
    void shouldRedirectOssMediaPreviewToFreshSignedUrl() throws Exception {
        ChatAttachmentService attachmentService = mock(ChatAttachmentService.class);
        ChatAttachmentPreviewTokenService tokenService = mock(ChatAttachmentPreviewTokenService.class);
        AiProperties properties = new AiProperties();
        ChatAttachmentController controller = new ChatAttachmentController(attachmentService, tokenService, properties);
        ChatAttachmentEntity attachment = attachment("123", "AUDIO", "audio/mpeg", 10);
        when(attachmentService.loadByIdForPreview("123")).thenReturn(attachment);
        when(attachmentService.temporaryReadUrl(attachment, Duration.ofSeconds(3600)))
                .thenReturn(Optional.of("https://oss.example/signed"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.preview("123", 9999999999L, "signature", new MockHttpServletRequest(), response);

        verify(tokenService).validate("123", 9999999999L, "signature");
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FOUND.value());
        assertThat(response.getRedirectedUrl()).isEqualTo("https://oss.example/signed");
    }

    @Test
    void shouldForceUnsafeImageMimeTypeToDownload() throws Exception {
        ChatAttachmentService attachmentService = mock(ChatAttachmentService.class);
        ChatAttachmentPreviewTokenService tokenService = mock(ChatAttachmentPreviewTokenService.class);
        ChatAttachmentController controller =
                new ChatAttachmentController(attachmentService, tokenService, new AiProperties());
        ChatAttachmentEntity attachment = attachment("123", "IMAGE", "text/html", 13);
        when(attachmentService.loadOwned("123")).thenReturn(attachment);
        when(attachmentService.openContent(attachment))
                .thenReturn(new AiFileResource(
                        new ByteArrayInputStream("<h1>unsafe</h1>".getBytes(StandardCharsets.UTF_8)), 15));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.download("123", new MockHttpServletRequest(), response);

        assertThat(response.getContentType()).isEqualTo("application/octet-stream");
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment;");
    }

    private static ChatAttachmentEntity attachment(String id, String category, String mimeType, long sizeBytes) {
        ChatAttachmentEntity entity = new ChatAttachmentEntity();
        entity.setId(id);
        entity.setFileName("media.bin");
        entity.setCategory(category);
        entity.setMimeType(mimeType);
        entity.setSizeBytes(sizeBytes);
        return entity;
    }
}
