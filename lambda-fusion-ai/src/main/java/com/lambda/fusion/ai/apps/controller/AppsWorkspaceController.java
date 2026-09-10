package com.lambda.fusion.ai.apps.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.ai.AiConstants.AppType;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.apps.service.AppService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.runtime.workspace.WorkspaceFileEntry;
import com.lambda.fusion.ai.runtime.workspace.WorkspaceFileService;
import com.lambda.fusion.ai.runtime.workspace.entity.WorkspaceAuditEntity;
import com.lambda.fusion.ai.runtime.workspace.service.WorkspaceAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "应用 Workspace 管理")
@RestController
@RequestMapping("/v1/ai/apps/{appId}/workspace")
@RequiredArgsConstructor
public class AppsWorkspaceController {

    private final AppService appService;
    private final WorkspaceFileService workspaceFileService;
    private final WorkspaceAuditService workspaceAuditService;

    @SaCheckPermission(value = "ai:app-workspace:list")
    @Operation(summary = "列出 workspace 文件")
    @GetMapping("/files")
    public List<WorkspaceFileEntry> list(
            @Parameter(description = "应用ID", required = true) @PathVariable String appId,
            @Parameter(description = "租户ID(指定哪个运营商的 workspace)", required = true) @RequestParam String tenantId) {
        AppEntity app = appService.loadById(appId);
        assertWorkspace(app);
        try {
            return workspaceFileService.list(tenantId, app);
        } catch (IOException e) {
            throw new AiBusinessException(AiErrorCode.SYSTEM_ERROR, e);
        }
    }

    @SaCheckPermission(value = "ai:app-workspace:read")
    @Operation(summary = "读取 workspace 文件")
    @GetMapping("/file")
    public String read(
            @Parameter(description = "应用ID", required = true) @PathVariable String appId,
            @Parameter(description = "租户ID", required = true) @RequestParam String tenantId,
            @Parameter(description = "相对路径", required = true) @RequestParam String path) {
        AppEntity app = appService.loadById(appId);
        assertWorkspace(app);
        try {
            return workspaceFileService.read(tenantId, app, path);
        } catch (IOException e) {
            throw new AiBusinessException(AiErrorCode.SYSTEM_ERROR, e);
        }
    }

    @SaCheckPermission(value = "ai:app-workspace:write")
    @OperationLog(module = "AI", value = "写入 workspace 文件")
    @Operation(summary = "写入 workspace 文件")
    @PutMapping("/file")
    public void write(
            @Parameter(description = "应用ID", required = true) @PathVariable String appId,
            @Parameter(description = "租户ID", required = true) @RequestParam String tenantId,
            @Parameter(description = "相对路径", required = true) @RequestParam String path,
            @RequestBody String content) {
        AppEntity app = appService.loadById(appId);
        assertWorkspace(app);
        try {
            workspaceFileService.write(tenantId, app, path, content);
        } catch (IOException e) {
            throw new AiBusinessException(AiErrorCode.SYSTEM_ERROR, e);
        }
    }

    private void assertWorkspace(AppEntity app) {
        if (!AppType.WORKSPACE.getCode().equalsIgnoreCase(app.getAppType())) {
            throw new AiBusinessException(AiErrorCode.OPERATION_NOT_SUPPORTED, "非 WORKSPACE 型应用无 workspace");
        }
    }

    @SaCheckPermission(value = "ai:app-workspace:audit-list")
    @Operation(summary = "查询 workspace 自演化审计记录")
    @GetMapping("/audit")
    public List<WorkspaceAuditEntity> audit(
            @Parameter(description = "应用ID", required = true) @PathVariable String appId,
            @Parameter(description = "租户ID", required = true) @RequestParam String tenantId) {
        appService.loadById(appId);
        return workspaceAuditService.listByAppAndTenant(appId, tenantId);
    }
}
