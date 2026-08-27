package com.lambda.fusion.ai.chat.controller;

import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.attachment.ChatAttachmentPreviewPolicy;
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

    @Operation(summary = "查询当前用户的对话附件")
    @GetMapping("/{id}")
    public ChatAttachmentView detail(@Parameter(description = "附件ID", required = true) @PathVariable String id) {
        return chatAttachmentService.toView(chatAttachmentService.loadOwned(id));
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
        writeOriginal(attachment, request, response, false);
    }

    @Operation(summary = "下载附件原件(签名直链，无需登录)")
    @GetMapping("/{id}/file")
    public void signedDownload(
            @Parameter(description = "附件ID", required = true) @PathVariable String id,
            @RequestParam long expires,
            @RequestParam String sig,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        chatAttachmentPreviewTokenService.validateDownload(id, expires, sig);
        ChatAttachmentEntity attachment = chatAttachmentService.loadByIdForPreview(id);
        writeOriginal(attachment, request, response, true);
    }

    @Operation(summary = "预览安全媒体、PDF 或 Office 派生 PDF(签名直链，无需登录)")
    @GetMapping("/{id}/preview")
    public void preview(
            @Parameter(description = "附件ID", required = true) @PathVariable String id,
            @RequestParam long expires,
            @RequestParam String sig,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        chatAttachmentPreviewTokenService.validatePreview(id, expires, sig);
        ChatAttachmentEntity attachment = chatAttachmentService.loadByIdForPreview(id);
        if (!ChatAttachmentPreviewPolicy.canPreview(attachment)) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        Duration ttl = Duration.ofSeconds(
                aiProperties.getChat().getAttachment().getPreview().getTtlSeconds());
        if (ChatAttachmentPreviewPolicy.hasDerivedPreview(attachment)) {
            var redirect = chatAttachmentService.temporaryPreviewReadUrl(attachment, ttl);
            if (redirect.isPresent()) {
                response.sendRedirect(redirect.get());
                return;
            }
            String originalName = StringUtils.defaultIfBlank(attachment.getFileName(), attachment.getId());
            String previewName =
                    StringUtils.defaultIfBlank(StringUtils.substringBeforeLast(originalName, "."), originalName)
                            + ".pdf";
            writeResource(
                    previewName,
                    ChatAttachmentPreviewPolicy.PDF_MIME_TYPE,
                    true,
                    chatAttachmentService.openPreviewContent(attachment),
                    request,
                    response);
            return;
        }
        var redirect = chatAttachmentService.temporaryReadUrl(attachment, ttl);
        if (redirect.isPresent()) {
            response.sendRedirect(redirect.get());
            return;
        }
        writeOriginal(attachment, request, response, false);
    }

    private void writeOriginal(
            ChatAttachmentEntity attachment,
            HttpServletRequest request,
            HttpServletResponse response,
            boolean forceDownload)
            throws IOException {
        String id = attachment.getId();
        String fileName = StringUtils.defaultIfBlank(attachment.getFileName(), id);
        boolean inline = !forceDownload && ChatAttachmentPreviewPolicy.isSafeOriginalPreview(attachment);
        String contentType = inline
                ? ChatAttachmentPreviewPolicy.previewMimeType(attachment)
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        writeResource(fileName, contentType, inline, chatAttachmentService.openContent(attachment), request, response);
    }

    private static void writeResource(
            String fileName,
            String contentType,
            boolean inline,
            AiFileResource resource,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        long contentLength = resource.contentLength();
        if (inline) {
            response.setContentType(contentType);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded);
        } else {
            response.setContentType(contentType);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded);
        }
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        try (resource) {
            List<HttpRange> ranges = parseRanges(request.getHeader(HttpHeaders.RANGE));
            if (ranges.isEmpty()) {
                response.setContentLengthLong(contentLength);
                resource.content().transferTo(response.getOutputStream());
            } else if (ranges.size() == 1 && contentLength > 0) {
                writeRange(resource, ranges.get(0), response);
            } else {
                rangeNotSatisfiable(contentLength, response);
                return;
            }
        } catch (IllegalArgumentException e) {
            rangeNotSatisfiable(contentLength, response);
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
}
