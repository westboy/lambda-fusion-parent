package com.lambda.fusion.form;

import com.lambda.autoconfig.MybatisPlusExtendProperties;
import com.lambda.cloud.datasource.dynamic.DynamicDataSourceService;
import com.lambda.fusion.authority.api.RemoteOrganizationService;
import com.lambda.fusion.authority.api.RemoteResourceService;
import com.lambda.fusion.authority.api.RemoteRoleService;
import com.lambda.fusion.authority.api.RemoteUserService;
import com.lambda.fusion.datasource.api.RemoteDataSourceApi;
import com.lambda.fusion.form.design.adapter.FormRouteGateway;
import com.lambda.fusion.form.design.adapter.RemoteFormRouteGateway;
import com.lambda.fusion.form.design.adapter.WorkflowProcessFormSource;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.design.support.DataSourceCatalog;
import com.lambda.fusion.form.design.support.RemoteDataSourceCatalog;
import com.lambda.fusion.form.design.support.RuntimeDataSourceResolver;
import com.lambda.fusion.form.design.support.TableMetadataReader;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
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

    /**
     * 挂载路由集成（authority 资源契约在场时装配）：表单菜单挂载经 RemoteResourceService 远程契约。
     * 容器已有 Bean（单体中 authority 适配器）优先，缺失且 Dubbo 可用时自建引用（check=false），
     * 都没有时装配为空（NullBean），FormRouteServiceImpl 按网关缺席降级（设计/预览/发布仍可用）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.lambda.fusion.authority.api.RemoteResourceService")
    static class AuthorityIntegration {
        @Bean
        FormRouteGateway formRouteGateway(ObjectProvider<RemoteResourceService> resourceServices, ObjectMapper json) {
            RemoteResourceService service =
                    resourceServices.getIfAvailable(() -> FormConfigure.dubboReference(RemoteResourceService.class));
            if (service == null) {
                log.info("authority 资源服务缺席，挂载路由能力降级不可用");
                return null;
            }
            return new RemoteFormRouteGateway(service, json);
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

    /**
     * 动态数据源集成（datasource 组件在场时装配，契约 §4.1 可选依赖优雅降级）：
     * 设计器数据源下拉经 {@code RemoteDataSourceApi} 契约枚举（容器已有 Bean 优先，单体 server
     * 模式即本地实现；api 缺席或 Dubbo 不可用时枚举降级为空清单——仅主库可映射。跨进程引用
     * 需先抽 datasource-api 模块并按 §7.3 装配，当前单体部署不预建）；
     * 运行时数据源解析按注册 id 经基础框架 {@code DynamicDataSourceService} 取连接池，
     * 路由未启用（无 dynamic primary）时解析端口降级为报错口径。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.lambda.fusion.datasource.api.RemoteDataSourceApi")
    static class DataSourceIntegration {
        @Bean
        DataSourceCatalog dataSourceCatalog(ObjectProvider<RemoteDataSourceApi> remoteApis) {
            return new RemoteDataSourceCatalog(remoteApis.getIfAvailable());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.lambda.cloud.datasource.dynamic.DynamicDataSourceService")
    static class DynamicRoutingIntegration {
        @Bean
        RuntimeDataSourceResolver runtimeDataSourceResolver(ObjectProvider<DynamicDataSourceService> services) {
            return datasourceId -> {
                DynamicDataSourceService service = services.getIfAvailable();
                if (service == null) {
                    throw RuntimeDataSourceResolver.missing();
                }
                javax.sql.DataSource resolved = service.getDataSource(datasourceId);
                if (resolved == null) {
                    throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "数据源不存在或未启用：" + datasourceId);
                }
                return resolved;
            };
        }
    }

    /**
     * Dubbo 缺席时返回 null（provider/网关按服务缺席降级），不阻断启动。
     * 同时探测线程上下文类加载器：当前执行上下文不可见 Dubbo（如测试的 FilteredClassLoader）视为缺席。
     */
    private static <T> T dubboReference(Class<T> interfaceClass) {
        if (!org.springframework.util.ClassUtils.isPresent(
                        "org.apache.dubbo.config.ReferenceConfig", FormConfigure.class.getClassLoader())
                || !org.springframework.util.ClassUtils.isPresent(
                        "org.apache.dubbo.config.ReferenceConfig",
                        org.springframework.util.ClassUtils.getDefaultClassLoader())) {
            log.debug("classpath 无 Dubbo，form 集成 {} 降级为空", interfaceClass.getSimpleName());
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
