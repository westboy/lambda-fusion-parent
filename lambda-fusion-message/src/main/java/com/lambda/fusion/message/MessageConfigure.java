package com.lambda.fusion.message;

import com.lambda.cloud.sse.service.SseService;
import com.lambda.fusion.message.channel.inbox.SseNotifier;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Message 模块装配类
 *
 * @author jin
 */
@Configuration
@EnableConfigurationProperties(MessageProperties.class)
@MapperScan(basePackages = {"com.lambda.fusion.message.**.mapper"})
@ComponentScan(basePackageClasses = MessageConfigure.class)
public class MessageConfigure {

    /**
     * SSE 推送通知器：仅当 classpath 存在 lambda-cloud-starter-sse 且推送开关开启时装配，
     * 缺失时站内信渠道自动降级为纯落库（用户进入页面拉取补偿）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.lambda.cloud.sse.service.SseService")
    @ConditionalOnProperty(prefix = "lambda.fusion.message", name = "push-enabled", matchIfMissing = true)
    public static class SsePushConfigure {

        @Bean
        public SseNotifier sseNotifier(ObjectProvider<SseService> sseService) {
            return new SseNotifier(sseService);
        }
    }
}
