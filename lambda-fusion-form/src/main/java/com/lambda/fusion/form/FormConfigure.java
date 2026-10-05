package com.lambda.fusion.form;

import com.lambda.autoconfig.MybatisPlusExtendProperties;
import com.lambda.fusion.authority.resource.service.PermissionService;
import com.lambda.fusion.authority.resource.service.ResourceService;
import com.lambda.fusion.form.design.adapter.AuthorityFormRouteGateway;
import com.lambda.fusion.form.design.adapter.FormPathRegistry;
import com.lambda.fusion.form.design.adapter.FormRouteGateway;
import com.lambda.fusion.form.design.adapter.WorkflowFormPathRegistry;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.runtime.controller.FormUploadController;
import com.lambda.fusion.form.runtime.provider.FormApiPermissionProvider;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.permission.service.ApiPermissionProvider;
import com.lambda.fusion.permission.service.ApiPermissionRegistry;
import com.lambda.fusion.upload.service.AttachmentService;
import com.lambda.fusion.workflow.formpath.service.FormPathManagerService;
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

    /** 审批流注册表联动（workflow 模块在场时装配）：挂路由自动登记 la_workflow_form_path */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(FormPathManagerService.class)
    @ConditionalOnBean(FormPathManagerService.class)
    static class WorkflowIntegration {
        @Bean
        FormPathRegistry formPathRegistry(FormPathManagerService formPaths) {
            return new WorkflowFormPathRegistry(formPaths);
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

    /** FILE 字段上传（P1）：复用 OSS 附件体系；oss 模块缺失时不装配，FILE 退化为文本输入 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(AttachmentService.class)
    @ConditionalOnBean(AttachmentService.class)
    static class UploadIntegration {
        @Bean
        FormUploadController formUploadController(
                AttachmentService attachments, FormModelProvider modelProvider, FormProperties properties) {
            return new FormUploadController(attachments, modelProvider, properties);
        }
    }
}
