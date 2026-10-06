package com.lambda.fusion.workflow.launch.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lambda.fusion.workflow.launch.model.LaunchGroupView;
import com.lambda.fusion.workflow.launch.service.LaunchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发起中心（已发布流程定义按分类分组的卡片墙数据）
 *
 * @author Zx
 */
@RestController
@RequestMapping("/workflow/launch")
@Tag(name = "发起中心")
@RequiredArgsConstructor
public class LaunchController {

    private final LaunchService launchService;

    @GetMapping("groups")
    @SaCheckPermission("workflow_launch_list")
    @Operation(summary = "按分类分组查询当前用户可发起的流程")
    public List<LaunchGroupView> groups() {
        return launchService.groups();
    }
}
