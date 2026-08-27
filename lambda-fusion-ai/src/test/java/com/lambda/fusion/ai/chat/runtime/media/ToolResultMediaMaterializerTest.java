package com.lambda.fusion.ai.chat.runtime.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.attachment.ChatAttachmentBlockFactory;
import com.lambda.fusion.ai.chat.attachment.ChatAttachmentPreviewTokenService;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.runtime.tools.AudioGenerationTool;
import com.lambda.fusion.ai.runtime.tools.ImageGenerationTool;
import com.lambda.fusion.ai.runtime.tools.VideoGenerationTool;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ToolResultMediaMaterializerTest {

    private ToolResultMediaMaterializer materializer;

    @AfterEach
    void tearDown() {
        if (materializer != null) {
            materializer.close();
        }
    }

    @Test
    void shouldStoreBase64AndReturnOfficialDataBlock() {
        AiProperties properties = properties();
        ChatAttachmentService attachmentService = mock(ChatAttachmentService.class);
        ChatAttachmentEntity stored = attachment("123456", "generated.png", "image/png");
        AtomicReference<byte[]> storedBytes = new AtomicReference<>();
        when(attachmentService.storeGenerated(anyString(), any(Path.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    storedBytes.set(Files.readAllBytes(invocation.getArgument(1)));
                    return stored;
                });
        materializer = new ToolResultMediaMaterializer(attachmentService, blockFactory(properties), properties);
        byte[] expected = "generated-image".getBytes();
        ToolResultBlock toolResult = result(
                ImageGenerationTool.TOOL_NAME,
                ImageBlock.builder()
                        .source(new Base64Source(
                                "image/png", Base64.getEncoder().encodeToString(expected)))
                        .build());

        ToolResultBlock result = materializer
                .materialize(
                        toolResult, new ToolResultMediaMaterializer.Context("run-1", "session-1", "tenant-1"), "tool-1")
                .block(Duration.ofSeconds(5));

        assertThat(storedBytes.get()).isEqualTo(expected);
        assertThat(result.getOutput()).singleElement().isInstanceOfSatisfying(DataBlock.class, data -> {
            assertThat(data.getId()).isEqualTo("attachment:123456");
            assertThat(data.getName()).isEqualTo("generated.png");
            assertThat(data.getSource()).isInstanceOfSatisfying(URLSource.class, source -> {
                assertThat(source.getUrl())
                        .startsWith("/v1/ai/chat/attachments/123456/preview?expires=")
                        .contains("&sig=");
                assertThat(source.getMimeType()).isEqualTo("image/png");
            });
        });
    }

    @Test
    void shouldRejectPrivateRemoteAddressBeforeDownload() {
        AiProperties properties = properties();
        ChatAttachmentService attachmentService = mock(ChatAttachmentService.class);
        materializer = new ToolResultMediaMaterializer(attachmentService, blockFactory(properties), properties);
        ToolResultBlock toolResult = result(
                ImageGenerationTool.TOOL_NAME,
                ImageBlock.builder()
                        .source(new URLSource("http://127.0.0.1/generated.png", "image/png"))
                        .build());

        assertThatThrownBy(() -> materializer
                        .materialize(
                                toolResult,
                                new ToolResultMediaMaterializer.Context("run-1", "session-1", "tenant-1"),
                                "tool-1")
                        .block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(AiBusinessException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AiErrorCode.TOOL_RESULT_MEDIA_MATERIALIZATION_FAILED.getCode()));
        verify(attachmentService, never()).storeGenerated(anyString(), any(Path.class), anyString(), anyString());
    }

    @Test
    void shouldMaterializeOfficialVideoBlockThroughTheSharedStoragePath() {
        AiProperties properties = properties();
        ChatAttachmentService attachmentService = mock(ChatAttachmentService.class);
        ChatAttachmentEntity stored = attachment("video-1", "generated.mp4", "video/mp4");
        when(attachmentService.storeGenerated(anyString(), any(Path.class), anyString(), anyString()))
                .thenReturn(stored);
        materializer = new ToolResultMediaMaterializer(attachmentService, blockFactory(properties), properties);
        ToolResultBlock toolResult = result(
                VideoGenerationTool.TOOL_NAME,
                VideoBlock.builder()
                        .source(new Base64Source(
                                "video/mp4", Base64.getEncoder().encodeToString("video".getBytes())))
                        .build());

        ToolResultBlock result = materializer
                .materialize(
                        toolResult, new ToolResultMediaMaterializer.Context("run-1", "session-1", "tenant-1"), "tool-1")
                .block(Duration.ofSeconds(5));

        assertThat(result.getOutput()).singleElement().isInstanceOfSatisfying(DataBlock.class, data -> {
            assertThat(data.getId()).isEqualTo("attachment:video-1");
            assertThat(data.getSource())
                    .isInstanceOfSatisfying(URLSource.class, source -> assertThat(source.getMimeType())
                            .isEqualTo("video/mp4"));
        });
        verify(attachmentService).storeGenerated(eq("session-1"), any(Path.class), anyString(), eq("video/mp4"));
    }

    @Test
    void shouldMaterializeOfficialAudioBlockThroughTheSharedStoragePath() {
        AiProperties properties = properties();
        ChatAttachmentService attachmentService = mock(ChatAttachmentService.class);
        when(attachmentService.storeGenerated(anyString(), any(Path.class), anyString(), eq("audio/mpeg")))
                .thenReturn(attachment("audio-1", "generated.mp3", "audio/mpeg"));
        materializer = new ToolResultMediaMaterializer(attachmentService, blockFactory(properties), properties);
        ToolResultBlock toolResult = result(
                AudioGenerationTool.TOOL_NAME,
                AudioBlock.builder()
                        .source(new Base64Source(
                                "audio/mpeg", Base64.getEncoder().encodeToString("audio".getBytes())))
                        .build());

        ToolResultBlock result = materializer
                .materialize(
                        toolResult, new ToolResultMediaMaterializer.Context("run-1", "session-1", "tenant-1"), "tool-1")
                .block(Duration.ofSeconds(5));

        assertThat(result.getOutput()).singleElement().isInstanceOfSatisfying(DataBlock.class, data -> assertThat(
                        data.getId())
                .isEqualTo("attachment:audio-1"));
    }

    private static ToolResultBlock result(String toolName, ContentBlock block) {
        return ToolResultBlock.builder().name(toolName).output(List.of(block)).build();
    }

    private static AiProperties properties() {
        AiProperties properties = new AiProperties();
        properties.getChat().getAttachment().getPreview().setSecret("test-preview-secret-with-enough-entropy");
        return properties;
    }

    private static ChatAttachmentPreviewTokenService previewTokenService(AiProperties properties) {
        ChatAttachmentPreviewTokenService service = new ChatAttachmentPreviewTokenService(properties);
        service.init();
        return service;
    }

    private static ChatAttachmentBlockFactory blockFactory(AiProperties properties) {
        return new ChatAttachmentBlockFactory(previewTokenService(properties));
    }

    private static ChatAttachmentEntity attachment(String id, String fileName, String mimeType) {
        ChatAttachmentEntity entity = new ChatAttachmentEntity();
        entity.setId(id);
        entity.setFileName(fileName);
        entity.setMimeType(mimeType);
        if (mimeType.startsWith("image/")) {
            entity.setCategory("IMAGE");
        } else if (mimeType.startsWith("audio/")) {
            entity.setCategory("AUDIO");
        } else if (mimeType.startsWith("video/")) {
            entity.setCategory("VIDEO");
        }
        return entity;
    }
}
