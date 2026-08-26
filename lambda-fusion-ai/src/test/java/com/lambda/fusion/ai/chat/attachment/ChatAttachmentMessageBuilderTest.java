package com.lambda.fusion.ai.chat.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import com.lambda.fusion.ai.llm.service.LlmModelService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatAttachmentMessageBuilderTest {

    private ChatAttachmentTextReader textReader;
    private AiProperties properties;
    private ChatAttachmentMessageBuilder builder;

    @BeforeEach
    void setUp() {
        LlmModelService modelService = mock(LlmModelService.class);
        LlmModelEntity model = new LlmModelEntity();
        model.setSupportsVision(false);
        when(modelService.loadById("model-1")).thenReturn(model);

        textReader = mock(ChatAttachmentTextReader.class);
        properties = new AiProperties();
        builder = new ChatAttachmentMessageBuilder(
                modelService, mock(ChatAttachmentService.class), textReader, properties);
    }

    @Test
    void shouldInlineShortDocumentCompletely() throws Exception {
        properties.getChat().getAttachment().setMaxInlineChars(20);
        ChatAttachmentEntity attachment = document("att-1", "说明.txt");
        when(textReader.readInlineIfComplete(attachment, 20)).thenReturn(Optional.of("完整短文件正文"));

        String text = messageText(builder.buildUserMsg(session(), app(), "请总结", List.of(attachment)));

        assertThat(text)
                .contains("请总结")
                .contains("附件「说明.txt」完整内容")
                .contains("完整短文件正文")
                .doesNotContain("长附件");
    }

    @Test
    void shouldToolizeLongDocumentWithoutInjectingTruncatedPrefix() throws Exception {
        properties.getChat().getAttachment().setMaxInlineChars(8);
        ChatAttachmentEntity attachment = document("att-long", "协议.txt");
        attachment.setSizeBytes(16L);
        when(textReader.readInlineIfComplete(attachment, 8)).thenReturn(Optional.empty());

        String text = messageText(builder.buildUserMsg(session(), app(), "分析附件", List.of(attachment)));

        assertThat(text)
                .contains("按需附件「协议.txt」")
                .contains("attachment_id=att-long")
                .contains("size_bytes=16")
                .contains(ChatAttachmentContentTool.SEARCH_TOOL_NAME)
                .contains(ChatAttachmentContentTool.READ_TOOL_NAME)
                .doesNotContain("SECRET")
                .doesNotContain("SECRET-L");
        verify(textReader, never()).readAll(attachment);
    }

    @Test
    void shouldApplyInlineBudgetAcrossAllDocuments() throws Exception {
        properties.getChat().getAttachment().setMaxInlineChars(10);
        ChatAttachmentEntity first = document("att-1", "一.txt");
        ChatAttachmentEntity second = document("att-2", "二.txt");
        when(textReader.readInlineIfComplete(first, 10)).thenReturn(Optional.of("123456"));
        when(textReader.readInlineIfComplete(second, 4)).thenReturn(Optional.empty());

        String text = messageText(builder.buildUserMsg(session(), app(), "", List.of(first, second)));

        assertThat(text)
                .contains("附件「一.txt」完整内容")
                .contains("123456")
                .contains("按需附件「二.txt」")
                .doesNotContain("abcdef");
    }

    private static String messageText(Msg message) {
        return message.getContentBlocks(TextBlock.class).getFirst().getText();
    }

    private static ChatAttachmentEntity document(String id, String name) {
        ChatAttachmentEntity attachment = new ChatAttachmentEntity();
        attachment.setId(id);
        attachment.setFileName(name);
        attachment.setFileType("txt");
        attachment.setCategory("DOCUMENT");
        attachment.setSizeBytes(10L);
        return attachment;
    }

    private static ChatSessionEntity session() {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        return session;
    }

    private static AppEntity app() {
        AppEntity app = new AppEntity();
        app.setId("app-1");
        app.setModelId("model-1");
        return app;
    }
}
