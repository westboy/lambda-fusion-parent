package com.lambda.fusion.ai.chat.attachment;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.runtime.gateway.RuntimeProperty;
import com.lambda.fusion.core.utils.TenantUtils;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.rag.reader.PDFReader;
import io.agentscope.core.rag.reader.ReaderInput;
import io.agentscope.core.rag.reader.SplitStrategy;
import io.agentscope.core.rag.reader.TableFormat;
import io.agentscope.core.rag.reader.WordReader;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.tika.Tika;
import org.apache.tika.io.TikaInputStream;
import org.springframework.stereotype.Component;

/**
 * 将 AgentScope 工作区（宿主或沙箱）中的显式文件产物发布为当前对话附件。文件可通过工作区提供的
 * AgentScope Shell/Skill 创建，本工具只桥接受控下载、平台存储和消息附件引用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceArtifactPublishTool {

    public static final String TOOL_NAME = "publish_workspace_file";
    public static final String OUTPUT_DIRECTORY = "outputs";

    // 这里只复用 AgentScope Reader 完成结构解析，不消费 RAG 分块；单块可避免校验大文档时产生大量临时 Document。
    private static final int VALIDATION_CHUNK_SIZE = Integer.MAX_VALUE;
    private static final Map<String, String> ARTIFACT_MIME_TYPES = Map.ofEntries(
            Map.entry("pdf", ChatAttachmentPreviewPolicy.PDF_MIME_TYPE),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("txt", "text/plain"),
            Map.entry("md", "text/markdown"),
            Map.entry("csv", "text/csv"),
            Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml"),
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"));
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "csv", "json", "xml", "html", "htm");
    private static final Set<String> OFFICE_EXTENSIONS = Set.of("docx", "xlsx", "pptx");

    private final ChatAttachmentService attachmentService;
    private final ChatAttachmentBlockFactory attachmentBlockFactory;
    private final AiProperties aiProperties;

    @Tool(
            name = TOOL_NAME,
            description = "Publish one generated PDF, Office, or UTF-8 text artifact from the workspace outputs/ "
                    + "directory to the current chat. Supported text extensions: TXT, MD, CSV, JSON, XML, HTML, and "
                    + "HTM. Pass preview_path only for an Office file when a PDF preview was also generated. This tool "
                    + "does not create or convert files and never scans the workspace.")
    public ToolResultBlock publish(
            @ToolParam(
                            name = "path",
                            description =
                                    "Generated file path under outputs/; sandbox paths may use /workspace/outputs/")
                    String path,
            @ToolParam(
                            name = "display_name",
                            description = "Optional user-facing file name; extension must match path",
                            required = false)
                    String displayName,
            @ToolParam(
                            name = "preview_path",
                            description = "Optional generated PDF preview path under outputs/; sandbox paths may use "
                                    + "/workspace/outputs/",
                            required = false)
                    String previewPath,
            RuntimeContext context) {
        Path sourceTemp = null;
        Path previewTemp = null;
        try {
            PublishRuntime runtime = runtime(context);
            ResolvedArtifact source = resolveArtifact(path, displayName, runtime.pathNormalizer());
            byte[] sourceBytes = download(runtime, source.path());
            sourceTemp = writeTemp(source.extension(), sourceBytes);
            validateArtifact(source.extension(), sourceBytes, sourceTemp);

            if (StringUtils.isNotBlank(previewPath)) {
                if ("pdf".equals(source.extension())) {
                    throw new IllegalArgumentException("PDF 原件无需另传 preview_path。");
                }
                if (!OFFICE_EXTENSIONS.contains(source.extension())) {
                    throw new IllegalArgumentException("仅 DOCX、XLSX 或 PPTX 文件可以提供 PDF 预览。");
                }
                String normalizedPreview = normalizeOutputPath(previewPath, runtime.pathNormalizer());
                if (!"pdf".equals(extensionOf(normalizedPreview))) {
                    throw new IllegalArgumentException("preview_path 必须指向 PDF 文件。");
                }
                byte[] previewBytes = download(runtime, normalizedPreview);
                previewTemp = writeTemp("pdf", previewBytes);
                validateArtifact("pdf", previewBytes, previewTemp);
            }

            Path storedSource = sourceTemp;
            Path storedPreview = previewTemp;
            ChatAttachmentEntity attachment = TenantUtils.withTenant(
                    runtime.tenantId(),
                    () -> attachmentService.storeGenerated(
                            runtime.sessionId(),
                            storedSource,
                            source.fileName(),
                            ARTIFACT_MIME_TYPES.get(source.extension()),
                            storedPreview));
            return ToolResultBlock.builder()
                    .name(TOOL_NAME)
                    .output(List.of(attachmentBlockFactory.create(attachment)))
                    .build();
        } catch (IllegalArgumentException e) {
            return ToolResultBlock.error(e.getMessage());
        } catch (RuntimeException | IOException e) {
            log.warn("发布工作区文件失败: path={}, reason={}", path, e.getMessage());
            return ToolResultBlock.error("文件发布失败，请确认文件已生成到 outputs/ 目录且大小未超过平台限制。");
        } finally {
            deleteQuietly(sourceTemp);
            deleteQuietly(previewTemp);
        }
    }

    private PublishRuntime runtime(RuntimeContext context) {
        AbstractFilesystem filesystem = context == null ? null : context.get(AbstractFilesystem.class);
        WorkspacePathNormalizer pathNormalizer = context == null ? null : context.get(WorkspacePathNormalizer.class);
        String tenantId = RuntimeProperty.tenantId(context);
        String sessionId = RuntimeProperty._sessionId(context);
        if (filesystem == null || pathNormalizer == null || StringUtils.isAnyBlank(tenantId, sessionId)) {
            throw new IllegalArgumentException("当前运行没有可用的 AgentScope 工作区文件系统上下文。");
        }
        long maxBytes =
                aiProperties.getChat().getAttachment().getGeneratedMedia().getMaxFileSizeMb() * 1024L * 1024L;
        return new PublishRuntime(context, filesystem, pathNormalizer, tenantId, sessionId, maxBytes);
    }

    private ResolvedArtifact resolveArtifact(
            String rawPath, String displayName, WorkspacePathNormalizer pathNormalizer) {
        String path = normalizeOutputPath(rawPath, pathNormalizer);
        String extension = extensionOf(path);
        if (!ARTIFACT_MIME_TYPES.containsKey(extension)) {
            throw new IllegalArgumentException("仅支持发布 PDF、DOCX、XLSX、PPTX、TXT、MD、CSV、JSON、XML、HTML 或 HTM 文件。");
        }
        String sourceName = StringUtils.substringAfterLast("/" + path, "/");
        String fileName = normalizeFileName(StringUtils.defaultIfBlank(displayName, sourceName));
        String displayExtension = extensionOf(fileName);
        if (StringUtils.isBlank(displayExtension)) {
            fileName = fileName + "." + extension;
        } else if (!extension.equals(displayExtension)) {
            throw new IllegalArgumentException("display_name 的扩展名必须与 path 一致。");
        }
        return new ResolvedArtifact(path, fileName, extension);
    }

    private static String normalizeOutputPath(String rawPath, WorkspacePathNormalizer pathNormalizer) {
        if (StringUtils.isBlank(rawPath)) {
            throw new IllegalArgumentException("请提供待发布文件路径。");
        }
        String normalized = pathNormalizer.normalize(rawPath.trim()).replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        AbstractFilesystem.validatePath(normalized);
        if (!normalized.startsWith(OUTPUT_DIRECTORY + "/") || normalized.endsWith("/")) {
            throw new IllegalArgumentException("只能发布 outputs/ 目录下的显式文件。");
        }
        return normalized;
    }

    private byte[] download(PublishRuntime runtime, String path) {
        rejectMissingOrKnownOversize(runtime, path);
        List<FileDownloadResponse> responses = runtime.filesystem().downloadFiles(runtime.context(), List.of(path));
        if (responses.size() != 1
                || !responses.getFirst().isSuccess()
                || responses.getFirst().content() == null) {
            throw new IllegalArgumentException("未找到待发布文件，或工作区无法读取该文件。");
        }
        byte[] content = responses.getFirst().content();
        if (content.length == 0) {
            throw new IllegalArgumentException("不能发布空文件。");
        }
        if (content.length > runtime.maxBytes()) {
            throw new IllegalArgumentException("文件大小超过平台生成附件限制。");
        }
        return content;
    }

    private static void rejectMissingOrKnownOversize(PublishRuntime runtime, String path) {
        if (!runtime.filesystem().exists(runtime.context(), path)) {
            throw new IllegalArgumentException("未找到待发布文件。");
        }
        String parent = StringUtils.substringBeforeLast(path, "/");
        String fileName = StringUtils.substringAfterLast(path, "/");
        LsResult listed = runtime.filesystem().ls(runtime.context(), parent);
        if (!listed.isSuccess() || listed.entries() == null) {
            return;
        }
        for (FileInfo info : listed.entries()) {
            String listedPath = normalizeListedPath(runtime.pathNormalizer(), info.path());
            if (!path.equals(listedPath) && !fileName.equals(listedPath)) {
                continue;
            }
            if (info.isDirectory()) {
                throw new IllegalArgumentException("待发布路径必须是文件。");
            }
            if (info.size() > runtime.maxBytes()) {
                throw new IllegalArgumentException("文件大小超过平台生成附件限制。");
            }
            return;
        }
    }

    private static String normalizeListedPath(WorkspacePathNormalizer pathNormalizer, String path) {
        String normalized = pathNormalizer.normalize(path).replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static void validateArtifact(String extension, byte[] content, Path artifact) {
        if (TEXT_EXTENSIONS.contains(extension)) {
            validateUtf8Text(content);
            return;
        }
        validateStructuredArtifact(extension, artifact);
    }

    private static void validateStructuredArtifact(String extension, Path artifact) {
        try {
            validateDetectedMimeType(extension, artifact);
            switch (extension) {
                case "pdf" ->
                    new PDFReader(VALIDATION_CHUNK_SIZE, SplitStrategy.CHARACTER, 0)
                            .read(ReaderInput.fromPath(artifact))
                            .block();
                case "docx" ->
                    new WordReader(
                                    VALIDATION_CHUNK_SIZE,
                                    SplitStrategy.CHARACTER,
                                    0,
                                    false,
                                    false,
                                    TableFormat.MARKDOWN)
                            .read(ReaderInput.fromPath(artifact))
                            .block();
                case "xlsx" -> validateWorkbook(artifact);
                case "pptx" -> validatePresentation(artifact);
                default -> throw new IllegalArgumentException("不支持校验该文件类型。");
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("文件内容不是有效的 " + extension.toUpperCase(Locale.ROOT) + "。", e);
        }
    }

    private static void validateDetectedMimeType(String extension, Path artifact) throws IOException {
        try (TikaInputStream input = TikaInputStream.get(artifact)) {
            String detected = new Tika().detect(input);
            if (!ARTIFACT_MIME_TYPES.get(extension).equals(detected)) {
                throw new IllegalArgumentException("文件内容与 " + extension.toUpperCase(Locale.ROOT) + " 扩展名不匹配。");
            }
        }
    }

    private static void validateWorkbook(Path artifact) throws IOException {
        try (InputStream input = Files.newInputStream(artifact);
                XSSFWorkbook ignored = new XSSFWorkbook(input)) {
            // Apache POI 完整打开工作簿即完成结构校验。
        }
    }

    private static void validatePresentation(Path artifact) throws IOException {
        try (InputStream input = Files.newInputStream(artifact);
                XMLSlideShow ignored = new XMLSlideShow(input)) {
            // Apache POI 完整打开演示文稿即完成结构校验。
        }
    }

    private static void validateUtf8Text(byte[] content) {
        for (byte value : content) {
            if (value == 0) {
                throw new IllegalArgumentException("文本文件不能包含 NUL 字节。");
            }
        }
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("生成的文本文件必须使用 UTF-8 编码。", e);
        }
    }

    private static String normalizeFileName(String fileName) {
        String normalized = StringUtils.defaultString(fileName).replace('\\', '/');
        normalized = StringUtils.substringAfterLast("/" + normalized, "/").replaceAll("[\\p{Cntrl}]", "");
        normalized = StringUtils.left(normalized.trim(), 255);
        if (StringUtils.isBlank(normalized)) {
            throw new IllegalArgumentException("display_name 不能为空。");
        }
        return normalized;
    }

    private static String extensionOf(String path) {
        return StringUtils.substringAfterLast(path, ".").toLowerCase(Locale.ROOT);
    }

    private static Path writeTemp(String extension, byte[] content) throws IOException {
        Path temp = Files.createTempFile("workspace-artifact-", "." + extension);
        try {
            Files.write(temp, content);
            return temp;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temp);
            throw e;
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除工作区产物临时文件失败: {}, {}", path, e.getMessage());
        }
    }

    private record ResolvedArtifact(String path, String fileName, String extension) {}

    private record PublishRuntime(
            RuntimeContext context,
            AbstractFilesystem filesystem,
            WorkspacePathNormalizer pathNormalizer,
            String tenantId,
            String sessionId,
            long maxBytes) {}
}
