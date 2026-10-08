package com.lambda.fusion.form.runtime.controller;

import com.lambda.fusion.form.runtime.biz.BizDataProvider;
import com.lambda.fusion.form.runtime.interceptor.FormPermission;
import com.lambda.fusion.form.runtime.support.FormAction;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 表单运行时系统主数据 API（机构/用户选择器的数据源）。
 *
 * <p>权限与数据读一致（form:page）：运行时表单页的调用方不经过系统管理权限；
 * authority 模块缺席（BizDataProvider 无实现）时降级为空列表，表单不报错。
 *
 * @author Zx
 */
@RestController
@RequestMapping("/v1/form")
@Tag(name = "动态表单运行时")
@RequiredArgsConstructor
public class FormBizController {

    private final ObjectProvider<BizDataProvider> bizDataProviders;

    @GetMapping("{formKey}/biz/organizations")
    @FormPermission(FormAction.PAGE)
    @Operation(summary = "机构树（ORG 字段选择器数据源，仅启用机构）")
    public List<Map<String, Object>> organizations(@PathVariable String formKey) {
        BizDataProvider provider = bizDataProviders.getIfAvailable();
        return provider == null ? List.of() : provider.organizationTree();
    }

    @GetMapping("{formKey}/biz/users")
    @FormPermission(FormAction.PAGE)
    @Operation(summary = "用户检索（USER 字段选择器数据源，昵称/账号模糊匹配）")
    public List<Map<String, Object>> users(
            @PathVariable String formKey,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "20") int pageSize) {
        BizDataProvider provider = bizDataProviders.getIfAvailable();
        if (provider == null) {
            return List.of();
        }
        return provider.searchUsers(keyword, pageSize);
    }
}
