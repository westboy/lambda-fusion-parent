package com.lambda.fusion.config.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.lambda.fusion.config.ConfigConstants;
import com.lambda.fusion.config.model.QueryConfigList;
import com.lambda.fusion.config.model.entity.ConfigEntity;
import com.lambda.fusion.config.service.ConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公共配置查询端点：把 {@code public} 命名空间（application='public'，见
 * {@link ConfigConstants#PUBLIC_APPLICATION}）的配置以 HTTP 形式开放给前端等登录消费方。
 *
 * <p>public 命名空间是既有设计：其配置已随 {@code DatabaseBasedProperties} 流入每个应用的
 * Spring Environment；本端点补齐同一命名空间的前端读取通道（如控制台助手应用 id）。
 * 配置由开发者经配置管理页维护（application 手填 public），变更经既有刷新机制生效。
 *
 * <p>安全边界：登录即可读（无角色门槛），public 命名空间即暴露面白名单——敏感值不应放入
 * public 命名空间；未来出现「值敏感又必须下发」的场景走服务端解密（复用
 * {@code lambda-cloud-starter-crypto}），前端解密是伪安全，不做。
 *
 * @author zx
 */
@Tag(name = "公共配置查询")
@RestController
@RequestMapping("/config/public")
@RequiredArgsConstructor
public class PublicConfigController {

    private final ConfigService configService;

    /**
     * 查询公共配置全量（application='public'），键值对形式；同名键取首条。
     *
     * @return key → value 映射（键空白或值为 null 的记录被过滤）
     */
    @SaCheckLogin
    @GetMapping
    @Operation(summary = "查询公共配置", description = "返回 public 命名空间的全部配置（键值对），登录即可读")
    public Map<String, String> publicConfigs() {
        QueryConfigList query = new QueryConfigList();
        query.setApplication(ConfigConstants.PUBLIC_APPLICATION);
        return configService.listConfigs(query).stream()
                .filter(item -> StringUtils.isNotBlank(item.getKey()))
                .collect(Collectors.toMap(
                        ConfigEntity::getKey,
                        item -> StringUtils.defaultString(item.getValue()),
                        (first, second) -> first));
    }
}
