package com.lambda.fusion.autoconfig;

import com.lambda.cloud.logger.service.OperationService;
import com.lambda.fusion.logger.api.OperationLogApi;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Logger 客户端自动配置：分布式部署下装配 {@link OperationLogApi} 的 Dubbo 引用，
 * 以 {@link OperationService} 类型顶替 starter 默认实现；单体部署时 logger 模块已注册本地 bean，
 * 本配置不产生任何 bean。
 *
 * @author zx
 */
@AutoConfiguration
public class LoggerClientAutoConfiguration {

    @Configuration
    @ConditionalOnClass(name = "org.apache.dubbo.config.spring.ReferenceBean")
    public static class DubboServiceConfiguration {

        @Bean
        @ConditionalOnMissingBean(OperationService.class)
        public ReferenceBean<OperationLogApi> operationServiceReference() {
            ReferenceBean<OperationLogApi> referenceBean = new ReferenceBean<>();
            referenceBean.setInterfaceClass(OperationLogApi.class);
            return referenceBean;
        }
    }
}
