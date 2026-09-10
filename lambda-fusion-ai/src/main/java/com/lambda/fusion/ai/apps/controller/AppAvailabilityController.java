package com.lambda.fusion.ai.apps.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lambda.fusion.ai.apps.model.AvailableApp;
import com.lambda.fusion.ai.apps.service.AppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可用应用查询（登录即可，无 ROLE_DEV 限制）。供 B/C 端用户查看自己能用的智能体。
 * 返回安全视图 {@link AvailableApp}，不暴露系统提示词、模型、工具等内部运行配置。
 *
 * @author Jin
 */
@Tag(name = "可用智能应用")
@RestController
@RequestMapping("/v1/ai/apps")
@RequiredArgsConstructor
public class AppAvailabilityController {

    private final AppService appService;

    @SaCheckPermission(value = "ai:app:available")
    @Operation(summary = "列出当前用户可用的应用（按 audience+角色 / owner 过滤，安全视图）")
    @GetMapping("/available")
    public List<AvailableApp> available() {
        return appService.listAvailableView();
    }
}
