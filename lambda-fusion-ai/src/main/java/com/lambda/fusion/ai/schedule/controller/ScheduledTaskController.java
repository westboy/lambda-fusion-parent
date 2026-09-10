package com.lambda.fusion.ai.schedule.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.ai.schedule.model.CreateScheduledTask;
import com.lambda.fusion.ai.schedule.model.ScheduledTaskLogPage;
import com.lambda.fusion.ai.schedule.model.ScheduledTaskPage;
import com.lambda.fusion.ai.schedule.model.UpdateScheduledTask;
import com.lambda.fusion.ai.schedule.model.entity.ScheduledTaskLogEntity;
import com.lambda.fusion.ai.schedule.service.ScheduledTaskService;
import com.lambda.fusion.ai.subagent.model.entity.SubAgentEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quartz.Trigger.TriggerState;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定时任务管理。
 *
 * @author Jin
 */
@Tag(name = "定时任务管理")
@RestController
@RequestMapping("/v1/ai/scheduled-tasks")
@RequiredArgsConstructor
public class ScheduledTaskController {

    private final ScheduledTaskService scheduledTaskService;

    @SaCheckPermission(value = "ai:scheduled-task:page")
    @Operation(summary = "分页查询定时任务")
    @GetMapping("/page")
    public Page<SubAgentEntity> page(@Valid ScheduledTaskPage query) {
        return scheduledTaskService.page(query);
    }

    @SaCheckPermission(value = "ai:scheduled-task:get")
    @Operation(summary = "查询定时任务详情")
    @GetMapping("/{id}")
    public SubAgentEntity get(@Parameter(description = "任务ID", required = true) @PathVariable String id) {
        return scheduledTaskService.get(id);
    }

    @SaCheckPermission(value = "ai:scheduled-task:status")
    @Operation(summary = "查询定时任务调度状态")
    @GetMapping("/{id}/status")
    public TriggerState status(@Parameter(description = "任务ID", required = true) @PathVariable String id) {
        return scheduledTaskService.status(id);
    }

    @SaCheckPermission(value = "ai:scheduled-task:log-page")
    @Operation(summary = "分页查询定时任务执行记录")
    @GetMapping("/{id}/logs/page")
    public Page<ScheduledTaskLogEntity> pageLogs(
            @Parameter(description = "任务ID", required = true) @PathVariable String id,
            @Valid ScheduledTaskLogPage query) {
        return scheduledTaskService.pageLogs(id, query);
    }

    @SaCheckPermission(value = "ai:scheduled-task:add")
    @OperationLog
    @Operation(summary = "新增定时任务")
    @PostMapping
    public SubAgentEntity create(@RequestBody @Valid CreateScheduledTask dto) {
        return scheduledTaskService.create(dto);
    }

    @SaCheckPermission(value = "ai:scheduled-task:update")
    @OperationLog
    @Operation(summary = "更新定时任务")
    @PutMapping("/{id}")
    public void update(
            @Parameter(description = "任务ID", required = true) @PathVariable String id,
            @RequestBody @Valid UpdateScheduledTask dto) {
        scheduledTaskService.update(id, dto);
    }

    @SaCheckPermission(value = "ai:scheduled-task:delete")
    @OperationLog
    @Operation(summary = "删除定时任务")
    @DeleteMapping("/{id}")
    public void delete(@Parameter(description = "任务ID", required = true) @PathVariable String id) {
        scheduledTaskService.delete(id);
    }

    @SaCheckPermission(value = "ai:scheduled-task:pause")
    @OperationLog
    @Operation(summary = "暂停定时任务调度")
    @PostMapping("/{id}/pause")
    public void pause(@Parameter(description = "任务ID", required = true) @PathVariable String id) {
        scheduledTaskService.pause(id);
    }

    @SaCheckPermission(value = "ai:scheduled-task:resume")
    @OperationLog
    @Operation(summary = "恢复定时任务调度")
    @PostMapping("/{id}/resume")
    public void resume(@Parameter(description = "任务ID", required = true) @PathVariable String id) {
        scheduledTaskService.resume(id);
    }

    @SaCheckPermission(value = "ai:scheduled-task:trigger")
    @OperationLog
    @Operation(summary = "立即触发一次定时任务")
    @PostMapping("/{id}/trigger")
    public void trigger(@Parameter(description = "任务ID", required = true) @PathVariable String id) {
        scheduledTaskService.trigger(id);
    }
}
