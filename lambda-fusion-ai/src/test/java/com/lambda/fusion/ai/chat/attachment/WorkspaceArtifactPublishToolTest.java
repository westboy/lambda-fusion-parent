package com.lambda.fusion.ai.chat.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.runtime.gateway.RuntimeProperty;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class WorkspaceArtifactPublishToolTest {

    private AbstractFilesystem filesystem;
    private ChatAttachmentService attachmentService;
    private WorkspaceArtifactPublishTool tool;

    @BeforeEach
    void setUp() {
        filesystem = mock(AbstractFilesystem.class);
        attachmentService = mock(ChatAttachmentService.class);
        AiProperties properties = new AiProperties();
        properties.getChat().getAttachment().getPreview().setSecret("test-preview-secret-with-enough-entropy");
        ChatAttachmentPreviewTokenService tokenService = new ChatAttachmentPreviewTokenService(properties);
        tokenService.init();
        tool = new WorkspaceArtifactPublishTool(
                attachmentService, new ChatAttachmentBlockFactory(tokenService), properties);
        when(filesystem.exists(any(), anyString())).thenReturn(true);
    }

    @Test
    void shouldPublishOfficeOriginalAndPdfPreviewThroughExistingAttachmentChain() throws Exception {
        byte[] docx = validOffice("docx");
        byte[] pdf = validPdf();
        when(filesystem.ls(any(), eq("outputs")))
                .thenReturn(LsResult.success(List.of(
                        FileInfo.ofFile("outputs/report.docx", docx.length, ""),
                        FileInfo.ofFile("outputs/report.preview.pdf", pdf.length, ""))));
        when(filesystem.downloadFiles(any(), any())).thenAnswer(invocation -> {
            String path = ((List<String>) invocation.getArgument(1)).getFirst();
            byte[] bytes = path.endsWith(".docx") ? docx : pdf;
            return List.of(FileDownloadResponse.success(path, bytes));
        });
        AtomicReference<byte[]> storedOriginal = new AtomicReference<>();
        AtomicReference<byte[]> storedPreview = new AtomicReference<>();
        ChatAttachmentEntity stored = attachment();
        when(attachmentService.storeGenerated(
                        eq("session-1"), any(Path.class), eq("季度报告.docx"), anyString(), any(Path.class)))
                .thenAnswer(invocation -> {
                    storedOriginal.set(Files.readAllBytes(invocation.getArgument(1)));
                    storedPreview.set(Files.readAllBytes(invocation.getArgument(4)));
                    return stored;
                });

        ToolResultBlock result = tool.publish(
                "/workspace/outputs/report.docx",
                "季度报告.docx",
                "/workspace/outputs/report.preview.pdf",
                runtimeContext());

        assertThat(storedOriginal.get()).isEqualTo(docx);
        assertThat(storedPreview.get()).isEqualTo(pdf);
        assertThat(result.getOutput()).singleElement().isInstanceOfSatisfying(DataBlock.class, data -> {
            assertThat(data.getId()).isEqualTo("attachment:1001");
            assertThat(data.getName()).isEqualTo("季度报告.docx");
            assertThat(data.getSource()).isInstanceOfSatisfying(URLSource.class, source -> assertThat(source.getUrl())
                    .startsWith("/v1/ai/chat/attachments/1001/file?expires="));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"docx", "xlsx", "pptx"})
    void shouldPublishStructurallyValidOfficeArtifacts(String extension) throws Exception {
        String path = "outputs/report." + extension;
        String fileName = "报告." + extension;
        byte[] content = validOffice(extension);
        when(filesystem.ls(any(), eq("outputs")))
                .thenReturn(LsResult.success(List.of(FileInfo.ofFile(path, content.length, ""))));
        when(filesystem.downloadFiles(any(), eq(List.of(path))))
                .thenReturn(List.of(FileDownloadResponse.success(path, content)));
        when(attachmentService.storeGenerated(eq("session-1"), any(Path.class), eq(fileName), anyString(), isNull()))
                .thenReturn(attachment(fileName, "application/octet-stream"));

        ToolResultBlock result = tool.publish(path, fileName, null, runtimeContext());

        assertThat(result.getOutput()).singleElement().isInstanceOf(DataBlock.class);
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
    void shouldPublishSupportedUtf8TextArtifacts(String extension, String mimeType) throws Exception {
        String path = "outputs/artifact." + extension;
        String fileName = "生成结果." + extension;
        byte[] content = ("UTF-8 文本 " + extension).getBytes(StandardCharsets.UTF_8);
        when(filesystem.ls(any(), eq("outputs")))
                .thenReturn(LsResult.success(List.of(FileInfo.ofFile(path, content.length, ""))));
        when(filesystem.downloadFiles(any(), eq(List.of(path))))
                .thenReturn(List.of(FileDownloadResponse.success(path, content)));
        AtomicReference<byte[]> storedContent = new AtomicReference<>();
        ChatAttachmentEntity stored = attachment(fileName, mimeType);
        when(attachmentService.storeGenerated(eq("session-1"), any(Path.class), eq(fileName), eq(mimeType), isNull()))
                .thenAnswer(invocation -> {
                    storedContent.set(Files.readAllBytes(invocation.getArgument(1)));
                    return stored;
                });

        ToolResultBlock result = tool.publish(path, fileName, null, runtimeContext());

        assertThat(storedContent.get()).isEqualTo(content);
        assertThat(result.getOutput()).singleElement().isInstanceOfSatisfying(DataBlock.class, data -> {
            assertThat(data.getName()).isEqualTo(fileName);
            assertThat(data.getSource())
                    .isInstanceOfSatisfying(URLSource.class, source -> assertThat(source.getMimeType())
                            .isEqualTo(mimeType));
        });
    }

    @Test
    void shouldRejectTextArtifactThatIsNotUtf8() {
        byte[] invalidUtf8 = {(byte) 0xc3, 0x28};
        when(filesystem.ls(any(), eq("outputs")))
                .thenReturn(LsResult.success(List.of(FileInfo.ofFile("outputs/invalid.txt", invalidUtf8.length, ""))));
        when(filesystem.downloadFiles(any(), any()))
                .thenReturn(List.of(FileDownloadResponse.success("outputs/invalid.txt", invalidUtf8)));

        ToolResultBlock result = tool.publish("outputs/invalid.txt", null, null, runtimeContext());

        assertThat(result.getOutput().toString()).contains("UTF-8");
        verify(attachmentService, never())
                .storeGenerated(anyString(), any(Path.class), anyString(), anyString(), any());
    }

    @Test
    void shouldRejectPdfPreviewForTextArtifact() {
        byte[] html = "<html><body>safe download</body></html>".getBytes(StandardCharsets.UTF_8);
        when(filesystem.ls(any(), eq("outputs")))
                .thenReturn(LsResult.success(List.of(FileInfo.ofFile("outputs/page.html", html.length, ""))));
        when(filesystem.downloadFiles(any(), any()))
                .thenReturn(List.of(FileDownloadResponse.success("outputs/page.html", html)));

        ToolResultBlock result = tool.publish("outputs/page.html", null, "outputs/page.preview.pdf", runtimeContext());

        assertThat(result.getOutput().toString()).contains("仅 DOCX、XLSX 或 PPTX");
        verify(attachmentService, never())
                .storeGenerated(anyString(), any(Path.class), anyString(), anyString(), any());
    }

    @Test
    void shouldRejectPathOutsideOutputsWithoutReadingSandbox() {
        ToolResultBlock result = tool.publish("/workspace/secrets.txt", null, null, runtimeContext());

        assertThat(result.getOutput().toString()).contains("outputs/");
        verify(filesystem, never()).downloadFiles(any(), any());
        verify(attachmentService, never())
                .storeGenerated(anyString(), any(Path.class), anyString(), anyString(), any(Path.class));
    }

    @Test
    void shouldRejectSpoofedOfficeExtensionBeforeStorage() throws Exception {
        byte[] fakeDocx = "%PDF-1.7\n%%EOF".getBytes();
        when(filesystem.ls(any(), eq("outputs")))
                .thenReturn(LsResult.success(List.of(FileInfo.ofFile("outputs/fake.docx", fakeDocx.length, ""))));
        when(filesystem.downloadFiles(any(), any()))
                .thenReturn(List.of(FileDownloadResponse.success("outputs/fake.docx", fakeDocx)));

        ToolResultBlock result = tool.publish("outputs/fake.docx", null, null, runtimeContext());

        assertThat(result.getOutput().toString()).contains("不是有效的 DOCX");
        verify(attachmentService, never())
                .storeGenerated(anyString(), any(Path.class), anyString(), anyString(), any(Path.class));
    }

    @Test
    void shouldRejectPdfHeaderWithoutValidPdfStructure() {
        byte[] fakePdf = "%PDF-junk".getBytes(StandardCharsets.UTF_8);
        when(filesystem.ls(any(), eq("outputs")))
                .thenReturn(LsResult.success(List.of(FileInfo.ofFile("outputs/fake.pdf", fakePdf.length, ""))));
        when(filesystem.downloadFiles(any(), any()))
                .thenReturn(List.of(FileDownloadResponse.success("outputs/fake.pdf", fakePdf)));

        ToolResultBlock result = tool.publish("outputs/fake.pdf", null, null, runtimeContext());

        assertThat(result.getOutput().toString()).contains("不是有效的 PDF");
        verify(attachmentService, never())
                .storeGenerated(anyString(), any(Path.class), anyString(), anyString(), any(Path.class));
    }

    @Test
    void shouldRejectZipWithOnlyOfficeLikeEntryNames() throws Exception {
        byte[] fakeDocx = fakeOoxml("word/document.xml");
        when(filesystem.ls(any(), eq("outputs")))
                .thenReturn(LsResult.success(List.of(FileInfo.ofFile("outputs/fake.docx", fakeDocx.length, ""))));
        when(filesystem.downloadFiles(any(), any()))
                .thenReturn(List.of(FileDownloadResponse.success("outputs/fake.docx", fakeDocx)));

        ToolResultBlock result = tool.publish("outputs/fake.docx", null, null, runtimeContext());

        assertThat(result.getOutput().toString()).contains("不是有效的 DOCX");
        verify(attachmentService, never())
                .storeGenerated(anyString(), any(Path.class), anyString(), anyString(), any(Path.class));
    }

    @Test
    void shouldPublishWhenAgentScopeCannotProvideFileSize() throws Exception {
        byte[] pdf = validPdf();
        when(filesystem.ls(any(), eq("outputs")))
                .thenReturn(LsResult.success(List.of(FileInfo.ofFile("outputs/report.pdf", 0, ""))));
        when(filesystem.downloadFiles(any(), eq(List.of("outputs/report.pdf"))))
                .thenReturn(List.of(FileDownloadResponse.success("outputs/report.pdf", pdf)));
        when(attachmentService.storeGenerated(
                        eq("session-1"), any(Path.class), eq("report.pdf"), eq("application/pdf"), isNull()))
                .thenReturn(attachment("report.pdf", "application/pdf"));

        ToolResultBlock result = tool.publish("outputs/report.pdf", null, null, runtimeContext());

        assertThat(result.getOutput()).singleElement().isInstanceOf(DataBlock.class);
    }

    @Test
    void shouldRejectKnownOversizeWhenHostListingUsesVirtualRootPath() {
        long oversized = 101L * 1024 * 1024;
        when(filesystem.ls(any(), eq("outputs")))
                .thenReturn(LsResult.success(List.of(FileInfo.ofFile("/outputs/report.pdf", oversized, ""))));

        ToolResultBlock result = tool.publish("outputs/report.pdf", null, null, runtimeContext());

        assertThat(result.getOutput().toString()).contains("文件大小超过");
        verify(filesystem, never()).downloadFiles(any(), any());
        verify(attachmentService, never())
                .storeGenerated(anyString(), any(Path.class), anyString(), anyString(), any(Path.class));
    }

    @Test
    void shouldRejectMissingFileBeforeDownload() {
        when(filesystem.exists(any(), eq("outputs/report.pdf"))).thenReturn(false);

        ToolResultBlock result = tool.publish("outputs/report.pdf", null, null, runtimeContext());

        assertThat(result.getOutput().toString()).contains("未找到待发布文件");
        verify(filesystem, never()).ls(any(), anyString());
        verify(filesystem, never()).downloadFiles(any(), any());
        verify(attachmentService, never())
                .storeGenerated(anyString(), any(Path.class), anyString(), anyString(), any(Path.class));
    }

    private RuntimeContext runtimeContext() {
        return RuntimeContext.builder()
                .userId("user-1")
                .sessionId("session-1")
                .put(RuntimeProperty.KEY_TENANT_ID, "tenant-1")
                .put(RuntimeProperty.KEY_LF_SESSION_ID, "session-1")
                .put(AbstractFilesystem.class, filesystem)
                .put(WorkspacePathNormalizer.class, WorkspacePathNormalizer.of("/workspace"))
                .build();
    }

    private static ChatAttachmentEntity attachment() {
        ChatAttachmentEntity attachment = new ChatAttachmentEntity();
        attachment.setId("1001");
        attachment.setFileName("季度报告.docx");
        attachment.setMimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        attachment.setCategory("DOCUMENT");
        attachment.setPreviewStoragePath("chat/session-1/1001.preview.pdf");
        return attachment;
    }

    private static ChatAttachmentEntity attachment(String fileName, String mimeType) {
        ChatAttachmentEntity attachment = new ChatAttachmentEntity();
        attachment.setId("1001");
        attachment.setFileName(fileName);
        attachment.setMimeType(mimeType);
        attachment.setCategory("FILE");
        return attachment;
    }

    private static byte[] fakeOoxml(String payloadEntry) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(payloadEntry));
            zip.write("<document/>".getBytes());
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static byte[] validPdf() throws Exception {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] validOffice(String extension) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        switch (extension) {
            case "docx" -> {
                try (XWPFDocument document = new XWPFDocument()) {
                    document.createParagraph().createRun().setText("AgentScope DOCX");
                    document.write(output);
                }
            }
            case "xlsx" -> {
                try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                    workbook.createSheet("Sheet1").createRow(0).createCell(0).setCellValue("AgentScope XLSX");
                    workbook.write(output);
                }
            }
            case "pptx" -> {
                try (XMLSlideShow presentation = new XMLSlideShow()) {
                    presentation.createSlide();
                    presentation.write(output);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported Office extension: " + extension);
        }
        return output.toByteArray();
    }
}
