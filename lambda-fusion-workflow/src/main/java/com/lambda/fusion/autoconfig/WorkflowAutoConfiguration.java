package com.lambda.fusion.autoconfig;

import com.lambda.fusion.workflow.WorkflowConfigure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Workflow 模块自动配置入口
 *
 * @author jin
 */
@Slf4j
@AutoConfiguration
@Import(WorkflowConfigure.class)
public class WorkflowAutoConfiguration {}
