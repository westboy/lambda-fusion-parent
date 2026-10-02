package com.lambda.fusion.permission;

import com.lambda.fusion.permission.api.PermissionSyncApi;
import com.lambda.fusion.permission.client.PermissionClientInitializer;
import com.lambda.fusion.permission.client.PermissionPushClient;
import com.lambda.fusion.permission.loader.LocalPermissionLoader;
import com.lambda.fusion.permission.server.PermissionSyncApiImpl;
import com.lambda.fusion.permission.service.ApiPermissionMatcher;
import com.lambda.fusion.permission.service.ApiPermissionProvider;
import com.lambda.fusion.permission.service.ApiPermissionRegistry;
import com.lambda.fusion.permission.service.PermissionTokenVerifier;
import com.lambda.fusion.permission.service.StaticPermissionTokenVerifier;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(PermissionProperties.class)
public class PermissionConfigure {
    @Bean
    @ConditionalOnMissingBean
    public ApiPermissionRegistry permissionRegistry(ObjectProvider<ApiPermissionProvider> providers) {
        return new ApiPermissionRegistry(providers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiPermissionMatcher permissionMatchService() {
        return new ApiPermissionMatcher();
    }

    @Bean
    @ConditionalOnMissingBean
    public LocalPermissionLoader localPermissionLoader(PermissionProperties properties, ObjectMapper objectMapper) {
        return new LocalPermissionLoader(properties, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = PermissionConstants.MODE_PROPERTY, havingValue = PermissionConstants.MODE_CLIENT)
    public PermissionPushClient permissionPushClient(
            PermissionProperties properties, PermissionSyncApi permissionSyncApi) {
        return new PermissionPushClient(properties, permissionSyncApi);
    }

    @Bean
    @ConditionalOnProperty(name = PermissionConstants.MODE_PROPERTY, havingValue = PermissionConstants.MODE_CLIENT)
    public PermissionClientInitializer permissionClientInitializer(
            PermissionProperties properties,
            LocalPermissionLoader localPermissionLoader,
            ApiPermissionRegistry apiPermissionRegistry,
            PermissionPushClient permissionPushClient,
            @Value("${spring.application.name:unknown-app}") String applicationName) {
        return new PermissionClientInitializer(
                properties, localPermissionLoader, apiPermissionRegistry, permissionPushClient, applicationName);
    }

    @Bean
    @ConditionalOnProperty(name = PermissionConstants.MODE_PROPERTY, havingValue = PermissionConstants.MODE_SERVER)
    public PermissionSyncApi permissionSyncApiImpl(
            ApiPermissionRegistry apiPermissionRegistry,
            LocalPermissionLoader localPermissionLoader,
            PermissionTokenVerifier permissionTokenVerifier,
            @Value("${spring.application.name:unknown-app}") String applicationName) {
        return new PermissionSyncApiImpl(
                apiPermissionRegistry, permissionTokenVerifier, localPermissionLoader, applicationName);
    }

    @Bean
    @ConditionalOnMissingBean(PermissionTokenVerifier.class)
    @ConditionalOnProperty(name = PermissionConstants.MODE_PROPERTY, havingValue = PermissionConstants.MODE_SERVER)
    public PermissionTokenVerifier staticPermissionTokenVerifier(PermissionProperties properties) {
        return new StaticPermissionTokenVerifier(properties);
    }

    @Slf4j
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.apache.dubbo.config.spring.ReferenceBean")
    @ConditionalOnProperty(name = PermissionConstants.MODE_PROPERTY, havingValue = PermissionConstants.MODE_CLIENT)
    public static class DubboClientConfiguration {

        @Bean
        @ConditionalOnMissingBean(PermissionSyncApi.class)
        public PermissionSyncApi remotePermissionSyncApiBean() {
            ReferenceConfig<PermissionSyncApi> reference = new ReferenceConfig<>();
            reference.setInterface(PermissionSyncApi.class);
            reference.setVersion(PermissionConstants.DUBBO_VERSION);
            reference.setGroup(PermissionConstants.DUBBO_GROUP);
            reference.setCheck(false);
            reference.setTimeout(5000);
            return reference.get();
        }
    }

    @Slf4j
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.apache.dubbo.config.spring.ServiceBean")
    @ConditionalOnProperty(name = PermissionConstants.MODE_PROPERTY, havingValue = PermissionConstants.MODE_SERVER)
    public static class DubboServiceConfiguration {

        @Bean
        @DubboService(
                interfaceClass = PermissionSyncApi.class,
                group = PermissionConstants.DUBBO_GROUP,
                version = PermissionConstants.DUBBO_VERSION)
        public PermissionSyncApi remotePermissionSyncApi(PermissionSyncApi permissionSyncApi) {
            return permissionSyncApi;
        }
    }
}
