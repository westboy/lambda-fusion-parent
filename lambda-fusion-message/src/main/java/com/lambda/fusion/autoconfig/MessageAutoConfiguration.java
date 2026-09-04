package com.lambda.fusion.autoconfig;

import com.lambda.fusion.message.MessageConfigure;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

/**
 * Message 模块自动配置入口
 *
 * @author jin
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "lambda.fusion.message", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import(MessageConfigure.class)
public class MessageAutoConfiguration {}
