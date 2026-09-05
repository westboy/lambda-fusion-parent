package com.lambda.fusion.message;

import com.lambda.cloud.sms.SmsMessageSender;
import com.lambda.cloud.sse.service.SseService;
import com.lambda.fusion.authority.api.RemoteUserService;
import com.lambda.fusion.message.api.RemoteMessageService;
import com.lambda.fusion.message.channel.bot.BotMessageChannel;
import com.lambda.fusion.message.channel.email.EmailMessageChannel;
import com.lambda.fusion.message.channel.inbox.SseNotifier;
import com.lambda.fusion.message.channel.sms.SmsMessageChannel;
import com.lambda.fusion.message.dispatch.adapter.RemoteMessageServiceAdapter;
import com.lambda.fusion.message.dispatch.resolver.MessageContactResolver;
import com.lambda.fusion.message.dispatch.resolver.MessageRecipientResolver;
import com.lambda.fusion.message.dispatch.resolver.impl.RemoteContactResolver;
import com.lambda.fusion.message.dispatch.resolver.impl.RemoteScopeRecipientResolver;
import com.lambda.fusion.message.dispatch.service.MessageDeliveryService;
import com.lambda.fusion.message.template.service.MessageTemplateService;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.annotation.DubboService;
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
     * 远程消息发送服务：仅当 classpath 存在 message-api 且 Dubbo 可用时经 Dubbo 暴露，
     * 供其他服务（如 ai）远程发送消息（对齐 authority 侧 RemoteUserService 暴露模式）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.lambda.fusion.message.api.RemoteMessageService")
    public static class RemoteMessageServiceConfigure {

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(name = "org.apache.dubbo.config.spring.ServiceBean")
        public static class DubboRemoteMessageConfiguration {

            @Bean
            @DubboService(interfaceClass = RemoteMessageService.class)
            public RemoteMessageService remoteMessageService(RemoteMessageServiceAdapter adapter) {
                return adapter;
            }
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

        @Bean
        public MessageRecipientResolver orgRecipientResolver(
                ObjectProvider<RemoteUserService> remoteUserService, ObjectMapper objectMapper) {
            return RemoteScopeRecipientResolver.orgResolver(remoteUserService, objectMapper);
        }

        @Bean
        public MessageRecipientResolver roleRecipientResolver(
                ObjectProvider<RemoteUserService> remoteUserService, ObjectMapper objectMapper) {
            return RemoteScopeRecipientResolver.roleResolver(remoteUserService, objectMapper);
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
                MessageProperties properties,
                com.lambda.fusion.message.notification.mapper.MessageMapper messageMapper) {
            return new EmailMessageChannel(
                    mailSender, contactResolvers.stream().toList(), deliveryService, properties, messageMapper);
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

    /**
     * 机器人 webhook 渠道：仅当渠道开关开启且至少配置了一个 webhook 时装配
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "lambda.fusion.message.bot", name = "enabled", havingValue = "true")
    public static class BotChannelConfigure {

        @Bean
        public BotMessageChannel botMessageChannel(
                MessageProperties properties, MessageDeliveryService deliveryService, ObjectMapper objectMapper) {
            if (properties.getBot().getWebhooks() == null
                    || properties.getBot().getWebhooks().isEmpty()) {
                throw new IllegalStateException("启动机器人渠道须至少配置一个 lambda.fusion.message.bot.webhooks");
            }
            return new BotMessageChannel(properties.getBot(), deliveryService, objectMapper);
        }
    }
}
