package com.lambda.fusion.autoconfig;

import com.lambda.fusion.form.FormConfigure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

/**
 * Form 模块自动配置入口
 *
 * @author Zx
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(prefix = "lambda.fusion.form", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import(FormConfigure.class)
public class FormAutoConfiguration {}
