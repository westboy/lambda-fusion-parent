package com.lambda.fusion.ai.rag.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.ai.rag.model.CreateKnowledgeBase;
import com.lambda.fusion.ai.rag.model.KnowledgeBasePage;
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

@SaCheckRole("ROLE_DEV")
@Tag(name = "知识库管理")
@RestController
@RequestMapping("/v1/ai/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @Operation(summary = "分页查询知识库")
    @GetMapping("/page")
    public Page<KnowledgeBaseEntity> page(@Valid KnowledgeBasePage query) {
        return knowledgeBaseService.page(query);
    }

    @Operation(summary = "查询知识库详情")
    @GetMapping("/{id}")
    public KnowledgeBaseEntity get(@Parameter(description = "知识库ID", required = true) @PathVariable String id) {
        return knowledgeBaseService.get(id);
    }

    @OperationLog
    @Operation(summary = "新增知识库")
    @PostMapping
    public KnowledgeBaseEntity create(@RequestBody @Valid CreateKnowledgeBase dto) {
        return knowledgeBaseService.create(dto);
    }

    @OperationLog
    @Operation(summary = "更新知识库")
    @PutMapping("/{id}")
    public void update(
            @Parameter(description = "知识库ID", required = true) @PathVariable String id,
            @RequestBody @Valid UpdateKnowledgeBase dto) {
        knowledgeBaseService.update(id, dto);
    }

    @OperationLog
    @Operation(summary = "删除知识库(级联删文档与向量数据)")
    @DeleteMapping("/{id}")
    public void delete(@Parameter(description = "知识库ID", required = true) @PathVariable String id) {
        knowledgeBaseService.delete(id);
    }

    @Operation(summary = "测试知识库检索召回")
    @PostMapping("/{id}/retrieval-test")
    public RetrievalProbe testRetrieval(
            @Parameter(description = "知识库ID", required = true) @PathVariable String id,
            @RequestBody @Valid TestKnowledgeRetrieval command) {
        return knowledgeBaseService.testRetrieval(id, command);
    }
}
