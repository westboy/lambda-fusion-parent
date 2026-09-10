package com.lambda.fusion.ai.rag.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.ai.rag.model.KnowledgeDocumentPage;
import com.lambda.fusion.ai.rag.model.entity.KnowledgeDocumentEntity;
import com.lambda.fusion.ai.rag.service.KnowledgeDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "知识库文档管理")
@RestController
@RequestMapping("/v1/ai/knowledge-bases/{kbId}/documents")
@RequiredArgsConstructor
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService knowledgeDocumentService;

    @SaCheckPermission(value = "ai:knowledge-document:page")
    @Operation(summary = "分页查询知识库文档")
    @GetMapping("/page")
    public Page<KnowledgeDocumentEntity> page(
            @Parameter(description = "知识库ID", required = true) @PathVariable String kbId,
            @Valid KnowledgeDocumentPage query) {
        query.setKbId(kbId);
        return knowledgeDocumentService.page(query);
    }

    @SaCheckPermission(value = "ai:knowledge-document:upload")
    @OperationLog
    @Operation(summary = "上传文档(异步解析切块入库, 立即返回 PENDING 行)")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KnowledgeDocumentEntity upload(
            @Parameter(description = "知识库ID", required = true) @PathVariable String kbId,
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "切割策略: AUTO/WHOLE/HEADING/PARAGRAPH/TOKEN")
                    @RequestParam(value = "chunkStrategy", defaultValue = "AUTO")
                    String chunkStrategy) {
        return knowledgeDocumentService.upload(kbId, file, chunkStrategy);
    }

    @SaCheckPermission(value = "ai:knowledge-document:delete")
    @OperationLog
    @Operation(summary = "删除文档(同步删除派生索引与原文件)")
    @DeleteMapping("/{documentId}")
    public void delete(
            @Parameter(description = "知识库ID", required = true) @PathVariable String kbId,
            @Parameter(description = "文档ID", required = true) @PathVariable String documentId) {
        knowledgeDocumentService.delete(kbId, documentId);
    }

    @SaCheckPermission(value = "ai:knowledge-document:reingest")
    @OperationLog
    @Operation(summary = "重新解析切块入库(复用原文件并异步替换派生索引)")
    @PostMapping("/{documentId}/reingest")
    public KnowledgeDocumentEntity reingest(
            @Parameter(description = "知识库ID", required = true) @PathVariable String kbId,
            @Parameter(description = "文档ID", required = true) @PathVariable String documentId,
            @Parameter(description = "新切割策略；不传则沿用文档当前策略") @RequestParam(value = "chunkStrategy", required = false)
                    String chunkStrategy) {
        return knowledgeDocumentService.reingest(kbId, documentId, chunkStrategy);
    }

    @SaCheckPermission(value = "ai:knowledge-document:download")
    @Operation(summary = "下载文档原文件")
    @GetMapping("/{documentId}/download")
    public void download(
            @Parameter(description = "知识库ID", required = true) @PathVariable String kbId,
            @Parameter(description = "文档ID", required = true) @PathVariable String documentId,
            HttpServletResponse response)
            throws IOException {
        KnowledgeDocumentEntity document = knowledgeDocumentService.get(kbId, documentId);
        String fileName = StringUtils.defaultIfBlank(document.getFileName(), documentId);
        // RFC 5987：文件名 URL 编码（UTF-8），避免中文名乱码
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded);
        knowledgeDocumentService.download(kbId, documentId, response.getOutputStream());
        response.flushBuffer();
    }
}
