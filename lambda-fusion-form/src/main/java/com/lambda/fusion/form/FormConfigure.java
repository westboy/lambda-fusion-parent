package com.lambda.fusion.form;

import com.lambda.autoconfig.MybatisPlusExtendProperties;
import com.lambda.fusion.authority.resource.service.PermissionService;
import com.lambda.fusion.authority.resource.service.ResourceService;
import com.lambda.fusion.form.design.adapter.AuthorityFormRouteGateway;
import com.lambda.fusion.form.design.adapter.FormRouteGateway;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.runtime.provider.FormApiPermissionProvider;
import com.lambda.fusion.permission.service.ApiPermissionProvider;
import com.lambda.fusion.permission.service.ApiPermissionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.ObjectMapper;

/**
 * Form 模块装配类
 *
 * <p>元数据四表的标准 CRUD 走 AbstractCrudService，运行时数据读写经通用 DynamicTableMapper；
 * Dubbo 广播失效与 oss 等可选依赖由 @ConditionalOnClass 保护（契约 §4.1）。
 *
 * @author Zx
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(FormProperties.class)
@ComponentScan(basePackageClasses = FormConfigure.class)
@MapperScan(basePackages = "com.lambda.fusion.form.**.mapper")
public class FormConfigure {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ResourceService.class)
    @ConditionalOnBean({ResourceService.class, PermissionService.class})
    static class AuthorityIntegration {
        @Bean
        FormRouteGateway formRouteGateway(ResourceService resources, PermissionService permissions, ObjectMapper json) {
            return new AuthorityFormRouteGateway(resources, permissions, json);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ApiPermissionRegistry.class)
    @ConditionalOnBean(ApiPermissionRegistry.class)
    static class PermissionIntegration {
        @Bean
        ApiPermissionProvider formApiPermissionProvider(
                FormDefMapper forms,
                ObjectProvider<RequestMappingHandlerMapping> mappings,
                Environment environment,
                MybatisPlusExtendProperties mybatisProperties) {
            return new FormApiPermissionProvider(
                    forms,
                    mappings,
                    environment.getProperty("spring.application.name", "unknown-app"),
                    mybatisProperties);
        }
    }
}
