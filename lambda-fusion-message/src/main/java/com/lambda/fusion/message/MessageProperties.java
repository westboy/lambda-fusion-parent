package com.lambda.fusion.message;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Message 模块配置属性
 *
 * @author jin
 */
@Getter
@Setter
@SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "springboot properties class")
@ConfigurationProperties(prefix = "lambda.fusion.message")
public class MessageProperties {

    /**
     * 是否启用 SSE 推送（站内信投递后向在线用户推送刷新信号）；推送为增强能力，关闭或 SSE 缺失时降级为纯落库
     */
    private boolean pushEnabled = true;

    /**
     * 邮件渠道配置
     */
    @NestedConfigurationProperty
    private Email email = new Email();

    /**
     * 短信渠道配置
     */
    @NestedConfigurationProperty
    private Sms sms = new Sms();

    @Getter
    @Setter
    public static class Email {

        /**
         * 是否启用邮件渠道（需同时配置 spring.mail.*）；启用后投递记录写 la_message_deliveries
         */
        private boolean enabled = false;

        /**
         * 发件人显示名，空则使用 SMTP 默认
         */
        private String from;
    }

    @Getter
    @Setter
    public static class Sms {

        /**
         * 是否启用短信渠道（需同时配置 lambda.sms.*）；启用后投递记录写 la_message_deliveries
         */
        private boolean enabled = false;

        /**
         * 指定短信运营商（ALIYUN/TENCENT/MOCK），空则取容器内首个 SmsMessageSender
         */
        private String isp;
    }
}
