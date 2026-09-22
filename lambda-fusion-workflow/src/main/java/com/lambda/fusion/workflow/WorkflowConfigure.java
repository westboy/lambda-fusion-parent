package com.lambda.fusion.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Workflow 模块装配类
 *
 * <p>流程引擎（warm-flow）的 DAO/Service/MapperScan 由其官方 starter
 * {@code warm-flow-mybatis-plus-sb4-starter} 的 FlowAutoConfig 装配；本模块负责桥接层与业务出口。
 * 引擎 SPI（PermissionHandler / GlobalListener）按类型从 Spring 容器获取（FlowEngine.initBean），
 * 因此 bridge 包内实现以 @Component 声明、经组件扫描注册即可生效。
 *
 * @author jin
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(WorkflowProperties.class)
@ComponentScan(basePackageClasses = WorkflowConfigure.class)
public class WorkflowConfigure {}
