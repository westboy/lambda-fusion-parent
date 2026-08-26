package com.lambda.fusion.ai.chat.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.storage.AiFileResource;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ChatAttachmentTextReaderTest {

    private final ChatAttachmentService attachmentService = mock(ChatAttachmentService.class);
    private final ChatAttachmentTextReader reader = new ChatAttachmentTextReader(attachmentService);

    @Test
    void shouldInlineOnlyWhenBoundedProbeContainsCompleteText() throws Exception {
        ChatAttachmentEntity attachment = textAttachment();
        byte[] content = "完整短文件正文".getBytes(StandardCharsets.UTF_8);
        when(attachmentService.openContent(attachment))
                .thenReturn(new AiFileResource(new ByteArrayInputStream(content), content.length));

        assertThat(reader.readInlineIfComplete(attachment, 20)).contains("完整短文件正文");
    }

    @Test
    void shouldStopProbeWithoutDownloadingLongTextCompletely() throws Exception {
        ChatAttachmentEntity attachment = textAttachment();
        byte[] content = "x".repeat(1000).getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream stream = new ByteArrayInputStream(content);
        when(attachmentService.openContent(attachment)).thenReturn(new AiFileResource(stream, content.length));

        assertThat(reader.readInlineIfComplete(attachment, 20)).isEmpty();
        assertThat(stream.available()).isGreaterThan(0);
    }

    @Test
    void shouldDecodeCommonChineseTextEncodingWhenStaging() throws Exception {
        ChatAttachmentEntity attachment = textAttachment();
        byte[] content = "中文附件".getBytes(Charset.forName("GB18030"));
        when(attachmentService.openContent(attachment))
                .thenReturn(new AiFileResource(new ByteArrayInputStream(content), content.length));

        assertThat(reader.readAll(attachment)).isEqualTo("中文附件");
    }

    @Test
    void shouldNotProbeBinaryDocumentBeforeToolAccess() throws Exception {
        ChatAttachmentEntity attachment = textAttachment();
        attachment.setFileType("pdf");

        assertThat(reader.readInlineIfComplete(attachment, 8000)).isEmpty();
        verifyNoInteractions(attachmentService);
    }

    private static ChatAttachmentEntity textAttachment() {
        ChatAttachmentEntity attachment = new ChatAttachmentEntity();
        attachment.setId("att-1");
        attachment.setFileName("说明.txt");
        attachment.setFileType("txt");
        attachment.setCategory("DOCUMENT");
        return attachment;
    }
}
