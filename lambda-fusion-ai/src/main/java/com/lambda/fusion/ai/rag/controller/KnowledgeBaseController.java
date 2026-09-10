package com.lambda.fusion.ai.rag.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.ai.rag.model.CreateKnowledgeBase;
import com.lambda.fusion.ai.rag.model.KnowledgeBasePage;
import com.lambda.fusion.ai.rag.model.KnowledgeIndexRebuildResult;
import com.lambda.fusion.ai.rag.model.TestKnowledgeRetrieval;
import com.lambda.fusion.ai.rag.model.UpdateKnowledgeBase;
import com.lambda.fusion.ai.rag.model.entity.KnowledgeBaseEntity;
import com.lambda.fusion.ai.rag.runtime.RetrievalProbe;
import com.lambda.fusion.ai.rag.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "知识库管理")
@RestController
@RequestMapping("/v1/ai/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @SaCheckPermission(value = "ai:knowledge-base:page")
    @Operation(summary = "分页查询知识库")
    @GetMapping("/page")
    public Page<KnowledgeBaseEntity> page(@Valid KnowledgeBasePage query) {
        return knowledgeBaseService.page(query);
    }

    @SaCheckPermission(value = "ai:knowledge-base:get")
    @Operation(summary = "查询知识库详情")
    @GetMapping("/{id}")
    public KnowledgeBaseEntity get(@Parameter(description = "知识库ID", required = true) @PathVariable String id) {
        return knowledgeBaseService.get(id);
    }

    @SaCheckPermission(value = "ai:knowledge-base:add")
    @OperationLog(module = "AI", value = "新增知识库")
    @Operation(summary = "新增知识库")
    @PostMapping
    public KnowledgeBaseEntity create(@RequestBody @Valid CreateKnowledgeBase dto) {
        return knowledgeBaseService.create(dto);
    }

    @SaCheckPermission(value = "ai:knowledge-base:update")
    @OperationLog(module = "AI", value = "更新知识库")
    @Operation(summary = "更新知识库")
    @PutMapping("/{id}")
    public void update(
            @Parameter(description = "知识库ID", required = true) @PathVariable String id,
            @RequestBody @Valid UpdateKnowledgeBase dto) {
        knowledgeBaseService.update(id, dto);
    }

    @SaCheckPermission(value = "ai:knowledge-base:delete")
    @OperationLog(module = "AI", value = "删除知识库(级联删除文档、派生索引与原文件)")
    @Operation(summary = "删除知识库(级联删除文档、派生索引与原文件)")
    @DeleteMapping("/{id}")
    public void delete(@Parameter(description = "知识库ID", required = true) @PathVariable String id) {
        knowledgeBaseService.delete(id);
    }

    @SaCheckPermission(value = "ai:knowledge-base:retrieval-test")
    @Operation(summary = "测试知识库检索召回")
    @PostMapping("/{id}/retrieval-test")
    public RetrievalProbe testRetrieval(
            @Parameter(description = "知识库ID", required = true) @PathVariable String id,
            @RequestBody @Valid TestKnowledgeRetrieval command) {
        return knowledgeBaseService.testRetrieval(id, command);
    }

    @SaCheckPermission(value = "ai:knowledge-base:index-rebuild")
    @OperationLog(module = "AI", value = "重建知识库派生索引(复用原文件并异步重新入库)")
    @Operation(summary = "重建知识库派生索引(复用原文件并异步重新入库)")
    @PostMapping("/{id}/indexes/rebuild")
    public KnowledgeIndexRebuildResult rebuildIndexes(
            @Parameter(description = "知识库ID", required = true) @PathVariable String id) {
        return knowledgeBaseService.rebuildIndexes(id);
    }
}
