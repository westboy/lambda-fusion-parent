package com.lambda.fusion.autoconfig;

import com.lambda.fusion.logger.LoggerConfigure;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

/**
 * Logger 模块自动配置入口
 *
 * @author zx
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "lambda.fusion.logger", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import(LoggerConfigure.class)
public class LoggerAutoConfiguration {}
