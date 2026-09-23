package com.lambda.fusion.workflow;

import com.lambda.fusion.authority.api.RemoteOrganizationService;
import com.lambda.fusion.authority.api.RemoteRoleService;
import com.lambda.fusion.authority.api.RemoteUserService;
import com.lambda.fusion.dict.service.DictInfoService;
import com.lambda.fusion.message.api.RemoteMessageService;
import com.lambda.fusion.workflow.bridge.AuthorityHandlerSelectService;
import com.lambda.fusion.workflow.bridge.DictCategoryService;
import com.lambda.fusion.workflow.bridge.RegistryFormPathService;
import com.lambda.fusion.workflow.bridge.WorkflowTaskNotifier;
import com.lambda.fusion.workflow.formpath.service.FormPathManagerService;
import lombok.extern.slf4j.Slf4j;
import org.dromara.warm.flow.orm.mapper.FlowInstanceMapper;
import org.dromara.warm.flow.orm.mapper.FlowTaskMapper;
import org.dromara.warm.flow.orm.mapper.FlowUserMapper;
import org.dromara.warm.flow.ui.service.CategoryService;
import org.dromara.warm.flow.ui.service.FormPathService;
import org.dromara.warm.flow.ui.service.HandlerSelectService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

import java.util.Objects;

/**
 * Workflow 模块装配类
 *
 * <p>流程引擎（warm-flow）的 DAO/Service/MapperScan 由其官方 starter
 * {@code warm-flow-mybatis-plus-sb4-starter} 的 FlowAutoConfig 装配；本模块负责桥接层与业务出口。
 * 引擎 SPI（PermissionHandler / GlobalListener）按类型从 Spring 容器获取（FlowEngine.initBean），
 * 因此 bridge 包内实现以 @Component 声明、经组件扫描注册即可生效。
 *
 * @author jin
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(WorkflowProperties.class)
@ComponentScan(basePackageClasses = WorkflowConfigure.class)
@MapperScan(basePackages = "com.lambda.fusion.workflow.**.mapper")
public class WorkflowConfigure {

    /**
     * 设计器办理人弹窗（用户/角色/部门页签）装配。
     *
     * <p>数据源为 authority 的三个远程契约接口：容器已有 Bean（单体中 authority 适配器组件）优先，
     * 缺失且 Dubbo 可用时自建引用（check=false），都没有时降级为空列表。
     * 条件注解保护 optional 依赖（authority-api / UI 插件不在 classpath 时不装配）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(
            name = {
                "com.lambda.fusion.authority.api.RemoteUserService",
                "org.dromara.warm.flow.ui.service.HandlerSelectService"
            })
    public static class AuthorityHandlerSelectConfiguration {

        @Bean
        public HandlerSelectService handlerSelectService(
                ObjectProvider<RemoteUserService> remoteUserServiceProvider,
                ObjectProvider<RemoteRoleService> remoteRoleServiceProvider,
                ObjectProvider<RemoteOrganizationService> remoteOrganizationServiceProvider) {
            return new AuthorityHandlerSelectService(
                    remoteUserServiceProvider.getIfAvailable(() -> Objects.requireNonNull(dubboReference(RemoteUserService.class))),
                    remoteRoleServiceProvider.getIfAvailable(() -> Objects.requireNonNull(dubboReference(RemoteRoleService.class))),
                    remoteOrganizationServiceProvider.getIfAvailable(
                            () -> Objects.requireNonNull(dubboReference(RemoteOrganizationService.class))));
        }
    }

    /**
     * 设计器流程类别装配：dictionary 模块 {@code workflow_category} 字典 → 设计器类别下拉。
     * 条件注解保护 optional 依赖（dictionary / UI 插件不在 classpath 时不装配）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(
            name = {"com.lambda.fusion.dict.service.DictInfoService", "org.dromara.warm.flow.ui.service.CategoryService"
            })
    public static class CategoryConfiguration {

        @Bean
        public CategoryService categoryService(DictInfoService dictInfoService) {
            return new DictCategoryService(dictInfoService);
        }
    }

    /**
     * 设计器自定义表单路径装配：本模块 la_workflow_form_path 注册表（启用项）→ 设计器下拉（§10.5）。
     * 条件注解保护 optional 依赖（UI 插件不在 classpath 时不装配）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.dromara.warm.flow.ui.service.FormPathService")
    public static class RegistryFormPathConfiguration {

        @Bean
        public FormPathService formPathService(FormPathManagerService formPathManagerService) {
            return new RegistryFormPathService(formPathManagerService);
        }
    }

    /**
     * 待办任务通知装配：ASSIGNMENT 事件 → message-api 站内信（§4.3）。
     * 数据源为 message 模块远程契约：容器已有 Bean（单体中 message 适配器组件）优先，
     * 缺失且 Dubbo 可用时自建引用（check=false），都没有时通知器空转降级。
     * 条件注解保护 optional 依赖（message-api 不在 classpath 时不装配）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.lambda.fusion.message.api.RemoteMessageService")
    public static class TaskNotifyConfiguration {

        @Bean
        public WorkflowTaskNotifier workflowTaskNotifier(
                ObjectProvider<RemoteMessageService> remoteMessageServiceProvider,
                FlowUserMapper flowUserMapper,
                FlowTaskMapper flowTaskMapper,
                FlowInstanceMapper flowInstanceMapper) {
            return new WorkflowTaskNotifier(
                    remoteMessageServiceProvider.getIfAvailable(() -> Objects.requireNonNull(dubboReference(RemoteMessageService.class))),
                    flowUserMapper,
                    flowTaskMapper,
                    flowInstanceMapper);
        }
    }

    /** Dubbo 不在 classpath 时返回 null，由使用方降级为空实现 */
    static <T> T dubboReference(Class<T> interfaceClass) {
        if (!ClassUtils.isPresent(
                "org.apache.dubbo.config.ReferenceConfig", WorkflowConfigure.class.getClassLoader())) {
            log.debug("classpath 无 Dubbo，{} 使用本地空降级", interfaceClass.getSimpleName());
            return null;
        }
        org.apache.dubbo.config.ReferenceConfig<T> reference = new org.apache.dubbo.config.ReferenceConfig<>();
        reference.setInterface(interfaceClass);
        reference.setCheck(false);
        return reference.get();
    }
}
