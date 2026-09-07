package com.lambda.fusion.logger;

import com.lambda.fusion.logger.api.OperationLogApi;
import com.lambda.fusion.logger.service.OperationLogService;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.apache.dubbo.config.annotation.DubboService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Logger 模块装配类
 *
 * @author zx
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(LoggerProperties.class)
@MapperScan(basePackages = {"com.lambda.fusion.logger.**.mapper"})
@ComponentScan(basePackageClasses = LoggerConfigure.class)
public class LoggerConfigure {

    /**
     * 日志异步落库线程池。CallerRunsPolicy：队列满时回退调用方线程，保证日志不丢且反压。
     */
    @Bean
    public Executor loggerExecutor(LoggerProperties properties) {
        LoggerProperties.Executor config = properties.getExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(config.getCorePoolSize());
        executor.setMaxPoolSize(config.getMaxPoolSize());
        executor.setQueueCapacity(config.getQueueCapacity());
        executor.setThreadNamePrefix("LoggerExecutor-");
        executor.setKeepAliveSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }

    /**
     * 操作日志落库服务经 Dubbo 暴露：分布式部署下，未部署 logger 实现的服务通过 logger-api 的
     * {@code LoggerClientAutoConfiguration} 装配 {@link OperationLogApi} 远程引用，
     * 其 {@code @OperationLog} 切面采集的日志经 Dubbo 落到本实现统一落库（单体部署不经远程）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.apache.dubbo.config.spring.ServiceBean")
    public static class DubboOperationServiceConfigure {

        @Bean
        @DubboService(interfaceClass = OperationLogApi.class)
        public OperationLogApi remoteOperationService(OperationLogService operationServiceImpl) {
            return operationServiceImpl;
        }
    }
}
