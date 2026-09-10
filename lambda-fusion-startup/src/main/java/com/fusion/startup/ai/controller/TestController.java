package com.fusion.startup.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "测试")
@RestController
@RequestMapping("/test")
@RequiredArgsConstructor
public class TestController {


    @SaCheckPermission("aaaaa")
    @Operation(summary = "测试接口")
    @GetMapping("/")
    public String hi() {
        return "hi";
    }

}
