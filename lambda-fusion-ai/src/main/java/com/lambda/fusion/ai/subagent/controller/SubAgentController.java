package com.lambda.fusion.ai.subagent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.ai.subagent.model.CreateSubAgent;
import com.lambda.fusion.ai.subagent.model.SubAgentPage;
import com.lambda.fusion.ai.subagent.model.UpdateSubAgent;
import com.lambda.fusion.ai.subagent.model.entity.SubAgentEntity;
import com.lambda.fusion.ai.subagent.service.SubAgentService;
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

@Tag(name = "子代理管理")
@RestController
@RequestMapping("/v1/ai/sub-agents")
@RequiredArgsConstructor
public class SubAgentController {

    private final SubAgentService subAgentService;

    @SaCheckPermission(value = "ai:sub-agent:page")
    @Operation(summary = "分页查询子代理")
    @GetMapping("/page")
    public Page<SubAgentEntity> page(@Valid SubAgentPage query) {
        return subAgentService.page(query);
    }

    @SaCheckPermission(value = "ai:sub-agent:get")
    @Operation(summary = "查询子代理详情")
    @GetMapping("/{id}")
    public SubAgentEntity get(@Parameter(description = "子代理ID", required = true) @PathVariable String id) {
        return subAgentService.get(id);
    }

    @SaCheckPermission(value = "ai:sub-agent:add")
    @OperationLog
    @Operation(summary = "新增子代理")
    @PostMapping
    public SubAgentEntity create(@RequestBody @Valid CreateSubAgent dto) {
        return subAgentService.create(dto);
    }

    @SaCheckPermission(value = "ai:sub-agent:update")
    @OperationLog
    @Operation(summary = "更新子代理")
    @PutMapping("/{id}")
    public void update(
            @Parameter(description = "子代理ID", required = true) @PathVariable String id,
            @RequestBody @Valid UpdateSubAgent dto) {
        subAgentService.update(id, dto);
    }

    @SaCheckPermission(value = "ai:sub-agent:delete")
    @OperationLog
    @Operation(summary = "删除子代理")
    @DeleteMapping("/{id}")
    public void delete(@Parameter(description = "子代理ID", required = true) @PathVariable String id) {
        subAgentService.delete(id);
    }
}
