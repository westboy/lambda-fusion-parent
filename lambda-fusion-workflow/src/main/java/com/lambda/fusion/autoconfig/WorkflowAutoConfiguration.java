package com.lambda.fusion.autoconfig;

import com.lambda.fusion.workflow.WorkflowConfigure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

/**
 * Workflow 模块自动配置入口
 *
 * @author jin
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(prefix = "lambda.fusion.workflow", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import(WorkflowConfigure.class)
public class WorkflowAutoConfiguration {}
