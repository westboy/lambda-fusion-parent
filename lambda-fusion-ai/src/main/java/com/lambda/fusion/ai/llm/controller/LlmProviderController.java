package com.lambda.fusion.ai.llm.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.ai.llm.model.CreateLlmProvider;
import com.lambda.fusion.ai.llm.model.LlmProviderPageQuery;
import com.lambda.fusion.ai.llm.model.UpdateLlmProvider;
import com.lambda.fusion.ai.llm.model.entity.LlmProviderEntity;
import com.lambda.fusion.ai.llm.service.LlmProviderService;
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

@Tag(name = "LLM 提供方管理")
@RestController
@RequestMapping("/v1/ai/llm-providers")
@RequiredArgsConstructor
public class LlmProviderController {

    private final LlmProviderService llmProviderService;

    @SaCheckPermission(value = "ai:llm-provider:page")
    @Operation(summary = "分页查询 LLM 提供方")
    @GetMapping("/page")
    public Page<LlmProviderEntity> page(@Valid LlmProviderPageQuery query) {
        return llmProviderService.page(query);
    }

    @SaCheckPermission(value = "ai:llm-provider:get")
    @Operation(summary = "查询 LLM 提供方详情")
    @GetMapping("/{id}")
    public LlmProviderEntity get(@Parameter(description = "提供方ID", required = true) @PathVariable String id) {
        return llmProviderService.get(id);
    }

    @SaCheckPermission(value = "ai:llm-provider:add")
    @OperationLog(module = "AI", value = "新增 LLM 提供方")
    @Operation(summary = "新增 LLM 提供方")
    @PostMapping
    public LlmProviderEntity create(@RequestBody @Valid CreateLlmProvider dto) {
        return llmProviderService.create(dto);
    }

    @SaCheckPermission(value = "ai:llm-provider:update")
    @OperationLog(module = "AI", value = "更新 LLM 提供方")
    @Operation(summary = "更新 LLM 提供方")
    @PutMapping("/{id}")
    public void update(
            @Parameter(description = "提供方ID", required = true) @PathVariable String id,
            @RequestBody @Valid UpdateLlmProvider dto) {
        llmProviderService.update(id, dto);
    }

    @SaCheckPermission(value = "ai:llm-provider:delete")
    @OperationLog(module = "AI", value = "删除 LLM 提供方")
    @Operation(summary = "删除 LLM 提供方")
    @DeleteMapping("/{id}")
    public void delete(@Parameter(description = "提供方ID", required = true) @PathVariable String id) {
        llmProviderService.delete(id);
    }
}
