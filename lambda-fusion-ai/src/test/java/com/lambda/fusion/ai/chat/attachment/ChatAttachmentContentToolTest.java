package com.lambda.fusion.ai.chat.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.runtime.gateway.RuntimeProperty;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatAttachmentContentToolTest {

    @TempDir
    Path tempDir;

    private ChatAttachmentService attachmentService;
    private ChatAttachmentTextReader textReader;
    private AiProperties properties;
    private ChatAttachmentContentTool tool;
    private ChatAttachmentEntity attachment;
    private AbstractFilesystem filesystem;

    @BeforeEach
    void setUp() throws Exception {
        attachmentService = mock(ChatAttachmentService.class);
        textReader = mock(ChatAttachmentTextReader.class);
        properties = new AiProperties();
        tool = new ChatAttachmentContentTool(attachmentService, textReader, properties, new ChatAttachmentTextCache());
        filesystem = new LocalFilesystem(tempDir, true, 10, null);

        attachment = new ChatAttachmentEntity();
        attachment.setId("att-1");
        attachment.setFileName("协议.txt");
        attachment.setCategory("DOCUMENT");
        when(attachmentService.loadBoundInSession("att-1", "session-1", "zhangsan"))
                .thenReturn(attachment);
    }

    @Test
    void shouldStageOnceAndReadBoundedRangesFromAgentScopeFilesystem() throws Exception {
        properties.getChat().getAttachment().setMaxToolReadChars(5);
        when(textReader.readAll(attachment)).thenReturn("0123456789");

        String first = text(tool.read("att-1", 2, 100, context()));
        String second = text(tool.read("att-1", 7, 5, context()));

        assertThat(first)
                .contains("requested_length=100")
                .contains("applied_length=5")
                .contains("max_read_chars=5")
                .contains("range=2..7")
                .contains("total_chars=10")
                .contains("next_start=7")
                .contains("23456")
                .doesNotContain("789");
        assertThat(second).contains("range=7..10").contains("next_start=null").contains("789");
        verify(textReader, times(1)).readAll(attachment);
        verify(attachmentService, times(2)).loadBoundInSession("att-1", "session-1", "zhangsan");
    }

    @Test
    void shouldSearchThroughAgentScopeFilesystem() throws Exception {
        when(textReader.readAll(attachment)).thenReturn("第一段\n这里是目标词附近的正文\n最后一段");

        String result = text(tool.search("att-1", "目标词", null, context()));

        assertThat(result).contains("query=目标词").contains("line=2").contains("这里是目标词附近的正文");
    }

    @Test
    void shouldHideRuntimeContextFromModelVisibleSchema() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);

        List<ToolSchema> schemas = toolkit.getToolSchemas();
        String parameters = schemas.stream()
                .map(ToolSchema::getParameters)
                .map(Object::toString)
                .reduce("", String::concat);

        assertThat(schemas)
                .extracting(ToolSchema::getName)
                .containsExactlyInAnyOrder(
                        ChatAttachmentContentTool.SEARCH_TOOL_NAME, ChatAttachmentContentTool.READ_TOOL_NAME);
        assertThat(parameters)
                .contains("attachment_id")
                .doesNotContain("RuntimeContext")
                .doesNotContain("tenantId")
                .doesNotContain("sessionId")
                .doesNotContain("userId");
    }

    @Test
    void shouldReceiveRuntimeContextWhenInvokedThroughAgentScopeToolkit() throws Exception {
        properties.getChat().getAttachment().setMaxToolReadChars(4);
        when(textReader.readAll(attachment)).thenReturn("012345");
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);
        ToolUseBlock call = ToolUseBlock.builder()
                .id("call-1")
                .name(ChatAttachmentContentTool.READ_TOOL_NAME)
                .input(java.util.Map.of("attachment_id", "att-1", "start", 1, "length", 4))
                .content("{\"attachment_id\":\"att-1\",\"start\":1,\"length\":4}")
                .build();

        List<ToolResultBlock> results =
                toolkit.callTools(List.of(call), null, null, context()).block();

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getOutput())
                .singleElement()
                .isInstanceOfSatisfying(TextBlock.class, block -> assertThat(block.getText())
                        .contains("range=1..5")
                        .contains("1234"));
        verify(attachmentService).loadBoundInSession("att-1", "session-1", "zhangsan");
    }

    @Test
    void shouldReturnAgentScopeErrorForMissingOrForeignAttachment() {
        when(attachmentService.loadBoundInSession("foreign", "session-1", "zhangsan"))
                .thenThrow(new IllegalStateException("different session"));

        ToolResultBlock result = tool.read("foreign", 0, 10, context());

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(text(result)).contains("无法读取该附件").doesNotContain("different session");
    }

    @Test
    void shouldServePagedReadsFromStageCacheWithoutRedownload() throws Exception {
        properties.getChat().getAttachment().setMaxToolReadChars(5);
        AbstractFilesystem spyFilesystem = spy(new LocalFilesystem(tempDir.resolve("cache"), true, 10, null));
        when(textReader.readAll(attachment)).thenReturn("0123456789");

        tool.read("att-1", 0, 5, contextWith(spyFilesystem));
        tool.read("att-1", 5, 5, contextWith(spyFilesystem));

        // 暂存即缓存：两页读取均命中缓存，从未回源下载。
        verify(spyFilesystem, never()).downloadFiles(any(), anyList());
        verify(textReader, times(1)).readAll(attachment);
    }

    @Test
    void shouldDownloadOnceWhenStageCacheMisses() throws Exception {
        // 缓存丢失但沙箱文件仍在（如进程重启后）：第一次读取回源一次，随后命中缓存。
        properties.getChat().getAttachment().setMaxToolReadChars(5);
        AbstractFilesystem spyFilesystem = spy(new LocalFilesystem(tempDir.resolve("miss"), true, 10, null));
        RuntimeContext context = contextWith(spyFilesystem);
        assertThat(spyFilesystem
                        .write(context, ".chat-attachments/session-1/att-1.txt", "0123456789")
                        .isSuccess())
                .isTrue();

        tool.read("att-1", 0, 5, context);
        tool.read("att-1", 5, 5, context);

        verify(spyFilesystem, times(1)).downloadFiles(any(), anyList());
        verify(textReader, never()).readAll(attachment);
    }

    private RuntimeContext context() {
        return contextWith(filesystem);
    }

    private RuntimeContext contextWith(AbstractFilesystem targetFilesystem) {
        return RuntimeContext.builder()
                .userId("zhangsan")
                .sessionId("session-1")
                .put(RuntimeProperty.KEY_TENANT_ID, "tenant-1")
                .put(RuntimeProperty.KEY_LF_SESSION_ID, "session-1")
                .put(AbstractFilesystem.class, targetFilesystem)
                .build();
    }

    private static String text(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", String::concat);
    }
}
