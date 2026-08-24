package com.lambda.fusion.authority;

import cn.dev33.satoken.listener.SaTokenListener;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.lambda.cloud.mybatis.handler.EntityMetaFiller;
import com.lambda.cloud.sse.listener.SseEventListener;
import com.lambda.fusion.authority.api.RemoteAuthenticationService;
import com.lambda.fusion.authority.api.RemoteUserService;
import com.lambda.fusion.authority.application.service.ApplicationService;
import com.lambda.fusion.authority.application.service.impl.DbPermissionTokenVerifier;
import com.lambda.fusion.authority.authentication.adapter.RemoteAuthenticationServiceAdapter;
import com.lambda.fusion.authority.authentication.adapter.RemoteUserServiceAdapter;
import com.lambda.fusion.authority.authentication.provider.alipay.AlipayMaLoginAdapter;
import com.lambda.fusion.authority.authentication.provider.alipay.AlipayMaLoginHandler;
import com.lambda.fusion.authority.authentication.provider.dingtalk.DingTalkLoginAdapter;
import com.lambda.fusion.authority.authentication.provider.dingtalk.DingTalkLoginHandler;
import com.lambda.fusion.authority.authentication.provider.wechat.WechatMaLoginAdapter;
import com.lambda.fusion.authority.authentication.provider.wechat.WechatMaLoginHandler;
import com.lambda.fusion.authority.authentication.provider.wechat.WechatOpenLoginAdapter;
import com.lambda.fusion.authority.authentication.provider.wechat.WechatOpenLoginHandler;
import com.lambda.fusion.authority.tenant.interceptor.TenantContextInterceptor;
import com.lambda.fusion.authority.user.listener.UserOnlineLogListener;
import com.lambda.fusion.authority.user.listener.UserSeeEventListener;
import com.lambda.fusion.authority.user.service.UserOnlineLogService;
import com.lambda.fusion.core.tree.filter.DefaultTreeDataFilter;
import com.lambda.fusion.core.tree.filter.TreeDataFilter;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.permission.service.PermissionTokenVerifier;
import com.lambda.security.service.ThirdPartyLoginService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SuppressFBWarnings("EI_EXPOSE_REP2")
@Slf4j
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = {"com.lambda.fusion.authority.**.mapper"})
@EnableConfigurationProperties({AuthorityProperties.class})
@ComponentScan(basePackageClasses = AuthorityConfigure.class)
public class AuthorityConfigure implements WebMvcConfigurer {

    public AuthorityConfigure() {
        log.trace("Authority Configuration init");
    }

    private TenantContextInterceptor tenantContextInterceptor;

    @Autowired
    public void setTenantContextInterceptor(@Lazy TenantContextInterceptor tenantContextInterceptor) {
        this.tenantContextInterceptor = tenantContextInterceptor;
    }

    @Slf4j
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.apache.dubbo.config.spring.ServiceBean")
    public static class DubboServiceConfiguration {

        @Bean
        @DubboService(interfaceClass = RemoteAuthenticationService.class)
        public RemoteAuthenticationService remoteAuthenticationService(
                RemoteAuthenticationServiceAdapter remoteAuthenticationServiceAdapter) {
            return remoteAuthenticationServiceAdapter;
        }

        @Bean
        @DubboService(interfaceClass = RemoteUserService.class)
        public RemoteUserService remoteUserService(RemoteUserServiceAdapter remoteUserServiceAdapter) {
            return remoteUserServiceAdapter;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "lambda.fusion.authorize.third-part", name = "enabled", havingValue = "true")
    public static class ThirdPartLoginConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public AlipayMaLoginAdapter alipayMaLoginAdapter(AuthorityProperties authorityProperties) {
            return new AlipayMaLoginAdapter(authorityProperties.getThirdPart());
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(AlipayMaLoginAdapter.class)
        public AlipayMaLoginHandler alipayMaLoginHandler(
                ThirdPartyLoginService thirdPartyLoginService, AlipayMaLoginAdapter alipayMaLoginAdapter) {
            return new AlipayMaLoginHandler(thirdPartyLoginService, alipayMaLoginAdapter);
        }

        @Bean
        @ConditionalOnMissingBean
        public WechatMaLoginAdapter wechatMaLoginAdapter(AuthorityProperties authorityProperties) {
            return new WechatMaLoginAdapter(authorityProperties.getThirdPart());
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(WechatMaLoginAdapter.class)
        public WechatMaLoginHandler wechatMaLoginHandler(
                ThirdPartyLoginService thirdPartyLoginService, WechatMaLoginAdapter wechatMaLoginAdapter) {
            return new WechatMaLoginHandler(thirdPartyLoginService, wechatMaLoginAdapter);
        }

        @Bean
        @ConditionalOnMissingBean
        public WechatOpenLoginAdapter wechatOpenLoginAdapter(AuthorityProperties authorityProperties) {
            return new WechatOpenLoginAdapter(authorityProperties.getThirdPart());
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(WechatOpenLoginAdapter.class)
        public WechatOpenLoginHandler wechatOpenLoginHandler(
                ThirdPartyLoginService thirdPartyLoginService, WechatOpenLoginAdapter wechatOpenLoginAdapter) {
            return new WechatOpenLoginHandler(thirdPartyLoginService, wechatOpenLoginAdapter);
        }

        @Bean
        @ConditionalOnMissingBean
        public DingTalkLoginAdapter dingTalkLoginAdapter(AuthorityProperties authorityProperties) {
            return new DingTalkLoginAdapter(authorityProperties.getThirdPart());
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(DingTalkLoginAdapter.class)
        public DingTalkLoginHandler dingTalkLoginHandler(
                ThirdPartyLoginService thirdPartyLoginService, DingTalkLoginAdapter dingTalkLoginAdapter) {
            return new DingTalkLoginHandler(thirdPartyLoginService, dingTalkLoginAdapter);
        }
    }

    @Bean
    public EntityMetaFiller entityMetaFiller() {
        return new EntityMetaFiller() {
            @Override
            public void insertFill(MetaObjectHandler handler, MetaObject metaObject) {
                handler.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
                handler.strictInsertFill(
                        metaObject,
                        "createdBy",
                        String.class,
                        AuthUtils.getUser().getUsername());
            }

            @Override
            public void updateFill(MetaObjectHandler handler, MetaObject metaObject) {
                handler.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
                handler.strictUpdateFill(
                        metaObject,
                        "updatedBy",
                        String.class,
                        AuthUtils.getUser().getUsername());
            }
        };
    }

    @Bean
    public Executor operationLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setMaxPoolSize(10);
        executor.setCorePoolSize(5);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("OperationLogExecutor-");
        executor.setKeepAliveSeconds(30);
        /*
         * 拒绝处理策略
         * CallerRunsPolicy()：交由调用方线程运行，比如 main 线程。
         * AbortPolicy()：直接抛出异常。
         * DiscardPolicy()：直接丢弃。
         * DiscardOldestPolicy()：丢弃队列中最老的任务。
         */
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }

    @Bean
    @ConditionalOnClass(SaTokenListener.class)
    public SaTokenListener userOnlineLogListener(
            @Autowired(required = false) UserOnlineLogService userOnlineLogService) {
        return new UserOnlineLogListener(userOnlineLogService);
    }

    @Bean
    @ConditionalOnClass(SseEventListener.class)
    public SseEventListener userSeeEventListener(
            @Autowired(required = false) UserOnlineLogService userOnlineLogService) {
        return new UserSeeEventListener(userOnlineLogService);
    }

    @Bean
    @ConditionalOnMissingBean
    public TreeDataFilter defaultTreeDataFilter() {
        return new DefaultTreeDataFilter();
    }

    /**
     * 权限同步令牌校验器：按服务注册表(la_applications)密钥校验上报方身份。
     *
     * <p>仅在 permission server 模式下注册，并以 @Primary 覆盖 permission 模块的默认静态令牌实现。
     */
    @Bean
    @Primary
    @ConditionalOnClass(PermissionTokenVerifier.class)
    @ConditionalOnProperty(name = "lambda.fusion.permission.mode", havingValue = "server")
    public PermissionTokenVerifier dbPermissionTokenVerifier(ApplicationService applicationService) {
        return new DbPermissionTokenVerifier(applicationService);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantContextInterceptor)
                .addPathPatterns("/**")
                .order(Integer.MIN_VALUE + 100); // 较高优先级执行，确保上下文尽早设置
    }
}
