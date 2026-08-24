package com.lambda.fusion.ai.chat.controller;

import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.attachment.ChatAttachmentPreviewTokenService;
import com.lambda.fusion.ai.chat.model.ChatAttachmentView;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.storage.AiFileResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "对话附件")
@RestController
@RequestMapping("/v1/ai/chat/attachments")
@RequiredArgsConstructor
public class ChatAttachmentController {

    private static final Set<String> INLINE_CATEGORIES = Set.of("IMAGE", "AUDIO", "VIDEO");
    private static final Set<String> SAFE_INLINE_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "image/avif",
            "image/bmp",
            "audio/mpeg",
            "audio/mp4",
            "audio/wav",
            "audio/ogg",
            "audio/webm",
            "audio/aac",
            "audio/flac",
            "video/mp4",
            "video/webm",
            "video/quicktime",
            "video/ogg",
            "video/mpeg");

    private final ChatAttachmentService chatAttachmentService;
    private final ChatAttachmentPreviewTokenService chatAttachmentPreviewTokenService;
    private final AiProperties aiProperties;

    @OperationLog
    @Operation(summary = "上传对话附件(图片/文档)")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatAttachmentView upload(
            @Parameter(description = "会话ID", required = true) @RequestParam("sessionId") String sessionId,
            @RequestParam("file") MultipartFile file) {
        return chatAttachmentService.toView(chatAttachmentService.upload(sessionId, file));
    }

    @OperationLog
    @Operation(summary = "删除未发送附件")
    @DeleteMapping("/{id}")
    public void delete(@Parameter(description = "附件ID", required = true) @PathVariable String id) {
        chatAttachmentService.delete(id);
    }

    @Operation(summary = "下载/预览附件原文件")
    @GetMapping("/{id}/download")
    public void download(
            @Parameter(description = "附件ID", required = true) @PathVariable String id,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        ChatAttachmentEntity attachment = chatAttachmentService.loadOwned(id);
        writeContent(attachment, request, response);
    }

    @Operation(summary = "预览图片/音频/视频附件(签名直链，无需登录)")
    @GetMapping("/{id}/preview")
    public void preview(
            @Parameter(description = "附件ID", required = true) @PathVariable String id,
            @RequestParam long expires,
            @RequestParam String sig,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        chatAttachmentPreviewTokenService.validate(id, expires, sig);
        ChatAttachmentEntity attachment = chatAttachmentService.loadByIdForPreview(id);
        if (isInline(attachment)) {
            Duration ttl = Duration.ofSeconds(
                    aiProperties.getChat().getAttachment().getPreview().getTtlSeconds());
            var redirect = chatAttachmentService.temporaryReadUrl(attachment, ttl);
            if (redirect.isPresent()) {
                response.sendRedirect(redirect.get());
                return;
            }
        }
        writeContent(attachment, request, response);
    }

    private void writeContent(ChatAttachmentEntity attachment, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String id = attachment.getId();
        String fileName = StringUtils.defaultIfBlank(attachment.getFileName(), id);
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        if (isInline(attachment)) {
            response.setContentType(attachment.getMimeType());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded);
        } else {
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded);
        }
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        try (AiFileResource resource = chatAttachmentService.openContent(attachment)) {
            List<HttpRange> ranges = parseRanges(request.getHeader(HttpHeaders.RANGE));
            if (ranges.isEmpty()) {
                response.setContentLengthLong(resource.contentLength());
                resource.content().transferTo(response.getOutputStream());
            } else if (ranges.size() == 1 && resource.contentLength() > 0) {
                writeRange(resource, ranges.get(0), response);
            } else {
                rangeNotSatisfiable(resource.contentLength(), response);
                return;
            }
        } catch (IllegalArgumentException e) {
            rangeNotSatisfiable(attachment.getSizeBytes() == null ? 0 : attachment.getSizeBytes(), response);
            return;
        }
        response.flushBuffer();
    }

    private static List<HttpRange> parseRanges(String rangeHeader) {
        return StringUtils.isBlank(rangeHeader) ? List.of() : HttpRange.parseRanges(rangeHeader);
    }

    private static void writeRange(AiFileResource resource, HttpRange range, HttpServletResponse response)
            throws IOException {
        long total = resource.contentLength();
        long start = range.getRangeStart(total);
        long end = range.getRangeEnd(total);
        if (start < 0 || start >= total || end < start) {
            rangeNotSatisfiable(total, response);
            return;
        }
        long length = end - start + 1;
        response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
        response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total);
        response.setContentLengthLong(length);
        resource.content().skipNBytes(start);
        copy(resource, response, length);
    }

    private static void copy(AiFileResource resource, HttpServletResponse response, long length) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int read = resource.content().read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            response.getOutputStream().write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void rangeNotSatisfiable(long total, HttpServletResponse response) {
        response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
        response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + total);
    }

    private static boolean isInline(ChatAttachmentEntity attachment) {
        String mimeType = StringUtils.substringBefore(StringUtils.defaultString(attachment.getMimeType()), ";")
                .trim()
                .toLowerCase(Locale.ROOT);
        return INLINE_CATEGORIES.contains(attachment.getCategory()) && SAFE_INLINE_MIME_TYPES.contains(mimeType);
    }
}
