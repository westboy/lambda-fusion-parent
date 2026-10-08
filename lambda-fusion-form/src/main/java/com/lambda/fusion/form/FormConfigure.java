package com.lambda.fusion.form;

import com.lambda.autoconfig.MybatisPlusExtendProperties;
import com.lambda.fusion.authority.resource.service.PermissionService;
import com.lambda.fusion.authority.resource.service.ResourceService;
import com.lambda.fusion.form.design.adapter.AuthorityFormRouteGateway;
import com.lambda.fusion.form.design.adapter.FormRouteGateway;
import com.lambda.fusion.form.design.adapter.WorkflowProcessFormSource;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.design.support.TableMetadataReader;
import com.lambda.fusion.form.integration.biz.AuthorityBizDataProvider;
import com.lambda.fusion.form.runtime.biz.BizDataProvider;
import com.lambda.fusion.form.runtime.controller.FormUploadController;
import com.lambda.fusion.form.runtime.integration.DynamicFormBusinessProvider;
import com.lambda.fusion.form.runtime.integration.DynamicFormFlowStatusListener;
import com.lambda.fusion.form.runtime.mapper.DynamicTableMapper;
import com.lambda.fusion.form.runtime.provider.FormApiPermissionProvider;
import com.lambda.fusion.form.runtime.service.DynamicDataService;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.permission.service.ApiPermissionProvider;
import com.lambda.fusion.permission.service.ApiPermissionRegistry;
import com.lambda.fusion.upload.service.AttachmentService;
import com.lambda.fusion.workflow.integration.ProcessFormSource;
import com.lambda.fusion.workflow.integration.WorkflowBusinessProvider;
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

    /** 流程集成（workflow 模块在场时装配）：设计器直连数据源 + 待办标题解析 + 流程状态回写 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ProcessFormSource.class)
    static class WorkflowIntegration {
        @Bean
        ProcessFormSource processFormSource(FormDefMapper forms) {
            return new WorkflowProcessFormSource(forms);
        }

        @Bean
        WorkflowBusinessProvider dynamicFormBusinessProvider(
                FormDefMapper forms, FormModelProvider modelProvider, DynamicDataService dynamicDataService) {
            return new DynamicFormBusinessProvider(forms, modelProvider, dynamicDataService);
        }

        @Bean
        DynamicFormFlowStatusListener dynamicFormFlowStatusListener(
                FormDefMapper forms,
                FormModelProvider modelProvider,
                DynamicTableMapper dynamicTableMapper,
                TableMetadataReader metadataReader) {
            return new DynamicFormFlowStatusListener(forms, modelProvider, dynamicTableMapper, metadataReader);
        }
    }

    /**
     * 系统主数据集成（authority 模块在场时装配）：ORG/USER 字段选择器的机构树与用户检索数据源。
     * 缺席（裁剪部署）时 FormBizController 经 ObjectProvider 降级为空列表。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.lambda.fusion.authority.organization.service.OrganizationService")
    static class BizDataIntegration {
        @Bean
        BizDataProvider bizDataProvider(
                com.lambda.fusion.authority.organization.service.OrganizationService organizationService,
                com.lambda.fusion.authority.user.service.UserInfoService userInfoService) {
            return new AuthorityBizDataProvider(organizationService, userInfoService);
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
