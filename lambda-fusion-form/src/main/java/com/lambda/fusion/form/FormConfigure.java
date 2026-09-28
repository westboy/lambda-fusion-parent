package com.lambda.fusion.form;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Form 模块装配类
 *
 * <p>元数据四表的标准 CRUD 走 AbstractCrudService，运行时数据读写经通用 DynamicTableMapper；
 * Dubbo 广播失效与 oss 等可选依赖由 @ConditionalOnClass 保护（契约 §4.1）。
 *
 * @author Zx
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(FormProperties.class)
@ComponentScan(basePackageClasses = FormConfigure.class)
@MapperScan(basePackages = "com.lambda.fusion.form.**.mapper")
public class FormConfigure {}
