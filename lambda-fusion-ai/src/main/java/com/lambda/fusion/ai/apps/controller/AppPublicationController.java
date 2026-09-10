package com.lambda.fusion.ai.apps.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.ai.apps.model.AppPublication;
import com.lambda.fusion.ai.apps.service.AppPublicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用发布管理。仅承载发布/下线/查询发布状态，与运行配置管理（{@code AppsController}）分离；
 * 返回 {@link AppPublication} 窄视图，不暴露任何运行配置。
 *
 * @author Jin
 */
@Tag(name = "智能应用发布管理")
@RestController
@RequestMapping("/v1/ai/apps/{appId}/publication")
@RequiredArgsConstructor
public class AppPublicationController {

    private final AppPublicationService appPublicationService;

    @SaCheckPermission(value = "ai:app-publication:get")
    @Operation(summary = "查询应用发布状态与代码")
    @GetMapping
    public AppPublication get(@Parameter(description = "应用ID", required = true) @PathVariable String appId) {
        return appPublicationService.get(appId);
    }

    @SaCheckPermission(value = "ai:app-publication:publish")
    @OperationLog
    @Operation(summary = "发布应用(幂等,首次生成稳定链接)")
    @PutMapping
    public AppPublication publish(@Parameter(description = "应用ID", required = true) @PathVariable String appId) {
        return appPublicationService.publish(appId);
    }

    @SaCheckPermission(value = "ai:app-publication:unpublish")
    @OperationLog
    @Operation(summary = "下线应用(幂等,保留链接)")
    @DeleteMapping
    public AppPublication unpublish(@Parameter(description = "应用ID", required = true) @PathVariable String appId) {
        return appPublicationService.unpublish(appId);
    }
}
