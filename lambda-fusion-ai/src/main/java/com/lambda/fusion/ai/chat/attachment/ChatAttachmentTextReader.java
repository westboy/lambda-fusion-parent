package com.lambda.fusion.ai.chat.attachment;

import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.runtime.document.DocumentTextExtractor;
import com.lambda.fusion.ai.storage.AiFileResource;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 对话附件文本读取器。原文件仍由 {@link ChatAttachmentService} 统一访问，本类负责纯文本解码；二进制文档
 * 才临时落盘并复用 AgentScope Reader 驱动的 {@link DocumentTextExtractor}，避免消息内联与附件工具各自实现
 * 一套解析流程。
 *
 * @author Jin
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatAttachmentTextReader {

    private static final Set<String> TEXT_TYPES = Set.of("txt", "md", "csv", "json", "xml", "html", "htm");
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final int MAX_PROBE_BYTES_PER_CHAR = 4;
    private static final int MAX_BOM_BYTES = 4;

    private final ChatAttachmentService attachmentService;

    /**
     * 有界探测纯文本附件。最多读取 {@code maxChars * 4 + BOM} 字节；只有确认全文完整且不超过字符预算时才返回，
     * 长文本不会为了判断长度而下载全文。PDF、Word 等二进制文档直接交给按需工具处理。
     */
    public Optional<String> readInlineIfComplete(ChatAttachmentEntity attachment, int maxChars) throws IOException {
        if (maxChars <= 0 || !isText(attachment.getFileType())) {
            return Optional.empty();
        }
        int maxProbeBytes =
                (int) Math.min(Integer.MAX_VALUE - 1L, (long) maxChars * MAX_PROBE_BYTES_PER_CHAR + MAX_BOM_BYTES);
        try (AiFileResource resource = attachmentService.openContent(attachment)) {
            byte[] probe = resource.content().readNBytes(maxProbeBytes + 1);
            if (probe.length > maxProbeBytes) {
                return Optional.empty();
            }
            String text = decodeText(probe);
            return text.length() <= maxChars ? Optional.of(text) : Optional.empty();
        }
    }

    /**
     * 按需抽取附件全文。纯文本直接从统一存储读取并规范为 Java 字符串；二进制文档临时落盘后复用
     * AgentScope Reader。调用方须先完成附件归属校验，临时文件始终在当前调用结束时清理。
     *
     * @param attachment 已完成访问校验的附件
     * @return 抽取后的完整文本
     * @throws IOException 临时文件或文本编码读取失败
     */
    public String readAll(ChatAttachmentEntity attachment) throws IOException {
        if (isText(attachment.getFileType())) {
            try (AiFileResource resource = attachmentService.openContent(attachment)) {
                return decodeText(resource.content().readAllBytes());
            }
        }
        String extension = StringUtils.defaultIfBlank(attachment.getFileType(), "tmp");
        Path tempFile = Files.createTempFile("chat-doc-", "." + extension);
        try {
            try (OutputStream out = Files.newOutputStream(tempFile)) {
                attachmentService.writeTo(attachment, out);
            }
            return DocumentTextExtractor.extractText(attachment.getFileType(), tempFile);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.warn("删除文档附件临时文件失败: {}", tempFile, e.getMessage());
            }
        }
    }

    private static boolean isText(String fileType) {
        return TEXT_TYPES.contains(StringUtils.lowerCase(StringUtils.defaultString(fileType)));
    }

    private static String decodeText(byte[] bytes) {
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ignored) {
            return new String(bytes, GB18030);
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (Byte.toUnsignedInt(bytes[i]) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
