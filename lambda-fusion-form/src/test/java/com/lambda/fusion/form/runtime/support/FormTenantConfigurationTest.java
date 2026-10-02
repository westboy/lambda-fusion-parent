package com.lambda.fusion.form.runtime.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.lambda.autoconfig.MyBatisAutoConfiguration;
import com.lambda.autoconfig.MybatisPlusExtendProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 表单使用的租户开关必须与 Cloud 实际装配插件的开关一致。 */
class FormTenantConfigurationTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cloudSwitchControlsBothBoundSettingsAndActualPlugin(boolean enabled) {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class, MyBatisAutoConfiguration.TenantConfig.class)
                .withPropertyValues("mybatis-plus.tenant.enabled=" + enabled)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MybatisPlusExtendProperties.class)
                                    .getTenant()
                                    .getEnabled())
                            .isEqualTo(enabled);
                    assertThat(context.getBeansOfType(TenantLineInnerInterceptor.class))
                            .hasSize(enabled ? 1 : 0);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MybatisPlusExtendProperties.class)
    static class PropertiesConfiguration {}
}
