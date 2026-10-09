package com.lambda.fusion.form;

import com.lambda.autoconfig.MybatisPlusExtendProperties;
import com.lambda.fusion.authority.api.RemoteOrganizationService;
import com.lambda.fusion.authority.api.RemoteRoleService;
import com.lambda.fusion.authority.api.RemoteUserService;
import com.lambda.fusion.authority.resource.service.PermissionService;
import com.lambda.fusion.authority.resource.service.ResourceService;
import com.lambda.fusion.form.design.adapter.AuthorityFormRouteGateway;
import com.lambda.fusion.form.design.adapter.FormRouteGateway;
import com.lambda.fusion.form.design.adapter.WorkflowProcessFormSource;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.design.support.TableMetadataReader;
import com.lambda.fusion.form.integration.biz.RemoteBizDataProvider;
import com.lambda.fusion.form.runtime.biz.BizDataProvider;
import com.lambda.fusion.form.runtime.controller.FormUploadController;
import com.lambda.fusion.form.runtime.integration.DynamicFormBusinessProvider;
import com.lambda.fusion.form.runtime.integration.DynamicFormFlowStatusListener;
import com.lambda.fusion.form.runtime.mapper.DynamicTableMapper;
import com.lambda.fusion.form.runtime.provider.FormApiPermissionProvider;
import com.lambda.fusion.form.runtime.service.DynamicDataService;
import com.lambda.fusion.form.runtime.service.FormRuntimeService;
import com.lambda.fusion.form.runtime.submit.WorkflowSubmitService;
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
     * 流程表单提交（workflow 模块在场时装配）：submit 端点依赖 InstanceService，
     * 裁剪部署下不装配、端点经 ObjectProvider 降级为 FORM_WORKFLOW_UNAVAILABLE。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.lambda.fusion.workflow.instance.service.InstanceService")
    static class WorkflowSubmitIntegration {
        @Bean
        WorkflowSubmitService workflowSubmitService(
                FormRuntimeService formRuntimeService,
                ObjectProvider<com.lambda.fusion.workflow.instance.service.InstanceService> instanceServices) {
            return new WorkflowSubmitService(formRuntimeService, instanceServices);
        }
    }

    /**
     * 系统主数据集成（authority 契约在场时装配）：ORG/USER/ROLE 字段选择器的机构树/用户检索/角色清单数据源。
     * 经 {@code lambda-fusion-authority-api} 远程契约取数（工程契约 §7.3，不引用实现类）：
     * 容器已有 Bean（单体中 authority 适配器）优先，缺失且 Dubbo 可用时自建引用（check=false），
     * 都没有时注入服务为 null 的 provider，端点经 ObjectProvider 降级为空列表。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.lambda.fusion.authority.api.RemoteUserService")
    static class BizDataIntegration {
        @Bean
        BizDataProvider bizDataProvider(
                ObjectProvider<RemoteUserService> userServices,
                ObjectProvider<RemoteOrganizationService> organizationServices,
                ObjectProvider<RemoteRoleService> roleServices) {
            return new RemoteBizDataProvider(
                    organizationServices.getIfAvailable(
                            () -> FormConfigure.dubboReference(RemoteOrganizationService.class)),
                    userServices.getIfAvailable(() -> FormConfigure.dubboReference(RemoteUserService.class)),
                    roleServices.getIfAvailable(() -> FormConfigure.dubboReference(RemoteRoleService.class)));
        }
    }

    /** Dubbo 缺席时返回 null（provider 内部按服务缺席降级），不阻断启动 */
    private static <T> T dubboReference(Class<T> interfaceClass) {
        if (!org.springframework.util.ClassUtils.isPresent(
                "org.apache.dubbo.config.ReferenceConfig", FormConfigure.class.getClassLoader())) {
            log.debug("classpath 无 Dubbo，form biz 数据源 {} 降级为空", interfaceClass.getSimpleName());
            return null;
        }
        org.apache.dubbo.config.ReferenceConfig<T> reference = new org.apache.dubbo.config.ReferenceConfig<>();
        reference.setInterface(interfaceClass);
        reference.setCheck(false);
        return reference.get();
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
