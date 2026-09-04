package com.lambda.fusion.message;

import com.lambda.cloud.sms.SmsMessageSender;
import com.lambda.cloud.sse.service.SseService;
import com.lambda.fusion.authority.api.RemoteUserService;
import com.lambda.fusion.message.channel.email.EmailMessageChannel;
import com.lambda.fusion.message.channel.inbox.SseNotifier;
import com.lambda.fusion.message.channel.sms.SmsMessageChannel;
import com.lambda.fusion.message.dispatch.resolver.MessageContactResolver;
import com.lambda.fusion.message.dispatch.resolver.impl.RemoteContactResolver;
import com.lambda.fusion.message.dispatch.service.MessageDeliveryService;
import com.lambda.fusion.message.template.service.MessageTemplateService;
import org.apache.dubbo.config.ReferenceConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import tools.jackson.databind.ObjectMapper;

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

    /**
     * 联系方式解析器：仅当 classpath 存在 lambda-fusion-authority-api 时装配，
     * 经 RemoteUserService 解析手机号/邮箱；Dubbo 存在时自建引用（对齐 ai 模块先例，关闭启动检查）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.lambda.fusion.authority.api.RemoteUserService")
    public static class AuthorityApiConfigure {

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(name = "org.apache.dubbo.config.spring.ReferenceConfig")
        public static class DubboRemoteUserConfiguration {

            @Bean
            public RemoteUserService messageRemoteUserService() {
                // 关闭启动检查；authority 未就绪时由解析器在实际调用阶段降级处理。
                ReferenceConfig<RemoteUserService> reference = new ReferenceConfig<>();
                reference.setInterface(RemoteUserService.class);
                reference.setCheck(false);
                return reference.get();
            }
        }

        @Bean
        public MessageContactResolver mobileContactResolver(ObjectProvider<RemoteUserService> remoteUserService) {
            return new RemoteContactResolver("mobile", remoteUserService);
        }

        @Bean
        public MessageContactResolver emailContactResolver(ObjectProvider<RemoteUserService> remoteUserService) {
            return new RemoteContactResolver("email", remoteUserService);
        }
    }

    /**
     * 邮件渠道：仅当 spring-boot-starter-mail 存在且渠道开关开启时装配；
     * JavaMailSender 经 ObjectProvider 注入，未配置 spring.mail.* 时投递记录失败原因
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.mail.javamail.JavaMailSender")
    @ConditionalOnProperty(prefix = "lambda.fusion.message.email", name = "enabled", havingValue = "true")
    public static class EmailChannelConfigure {

        @Bean
        public EmailMessageChannel emailMessageChannel(
                ObjectProvider<JavaMailSender> mailSender,
                ObjectProvider<MessageContactResolver> contactResolvers,
                MessageDeliveryService deliveryService,
                MessageProperties properties) {
            return new EmailMessageChannel(mailSender, contactResolvers.stream().toList(), deliveryService, properties);
        }
    }

    /**
     * 短信渠道：仅当 lambda-cloud-starter-sms 存在且渠道开关开启时装配；
     * SmsMessageSender 经 ObjectProvider 注入（兼容多运营商同时启用，按配置 isp 挑选）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.lambda.cloud.sms.SmsMessageSender")
    @ConditionalOnProperty(prefix = "lambda.fusion.message.sms", name = "enabled", havingValue = "true")
    public static class SmsChannelConfigure {

        @Bean
        public SmsMessageChannel smsMessageChannel(
                ObjectProvider<SmsMessageSender> senders,
                ObjectProvider<MessageContactResolver> contactResolvers,
                MessageDeliveryService deliveryService,
                MessageTemplateService templateService,
                MessageProperties properties,
                ObjectMapper objectMapper) {
            return new SmsMessageChannel(
                    senders,
                    contactResolvers.stream().toList(),
                    deliveryService,
                    templateService,
                    properties,
                    objectMapper);
        }
    }
}
