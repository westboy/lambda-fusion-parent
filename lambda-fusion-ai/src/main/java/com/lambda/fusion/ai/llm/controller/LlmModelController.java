package com.lambda.fusion.ai.llm.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.ai.llm.model.CreateLlmModel;
import com.lambda.fusion.ai.llm.model.LlmModelPage;
import com.lambda.fusion.ai.llm.model.UpdateLlmModel;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import com.lambda.fusion.ai.llm.service.LlmModelService;
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

@Tag(name = "LLM 模型管理")
@RestController
@RequestMapping("/v1/ai/llm-models")
@RequiredArgsConstructor
public class LlmModelController {

    private final LlmModelService llmModelService;

    @SaCheckPermission(value = "ai:llm-model:page")
    @Operation(summary = "分页查询 LLM 模型")
    @GetMapping("/page")
    public Page<LlmModelEntity> page(@Valid LlmModelPage query) {
        return llmModelService.page(query);
    }

    @SaCheckPermission(value = "ai:llm-model:get")
    @Operation(summary = "查询 LLM 模型详情")
    @GetMapping("/{id}")
    public LlmModelEntity get(@Parameter(description = "模型ID", required = true) @PathVariable String id) {
        return llmModelService.get(id);
    }

    @SaCheckPermission(value = "ai:llm-model:add")
    @OperationLog(module = "AI", value = "新增 LLM 模型")
    @Operation(summary = "新增 LLM 模型")
    @PostMapping
    public LlmModelEntity create(@RequestBody @Valid CreateLlmModel dto) {
        return llmModelService.create(dto);
    }

    @SaCheckPermission(value = "ai:llm-model:update")
    @OperationLog(module = "AI", value = "更新 LLM 模型")
    @Operation(summary = "更新 LLM 模型")
    @PutMapping("/{id}")
    public void update(
            @Parameter(description = "模型ID", required = true) @PathVariable String id,
            @RequestBody @Valid UpdateLlmModel dto) {
        llmModelService.update(id, dto);
    }

    @SaCheckPermission(value = "ai:llm-model:delete")
    @OperationLog(module = "AI", value = "删除 LLM 模型")
    @Operation(summary = "删除 LLM 模型")
    @DeleteMapping("/{id}")
    public void delete(@Parameter(description = "模型ID", required = true) @PathVariable String id) {
        llmModelService.delete(id);
    }
}
