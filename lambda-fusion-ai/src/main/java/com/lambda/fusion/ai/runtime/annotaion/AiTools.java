package com.lambda.fusion.ai.runtime.annotaion;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.stereotype.Component;

/**
 * 标注类为全局共享的本地 AI 工具 Bean：组合 {@code @Component} 注册为 Spring Bean，
 * 方法上标 AgentScope {@code @Tool}，由 {@code ToolkitAssembler} 启动时扫描注册到各应用。
 * 类级整批 HITL 确认用 {@link #requireConfirm()}；单个工具确认用 {@link RequireConfirm}。
 * 按应用绑定、不参与全局扫描的工具（如 {@code KnowledgeRetrievalTool}）不加本注解。
 *
 * @author Jin
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface AiTools {

    /**
     * 类级 HITL 确认开关：作用于该类所有 {@code @Tool} 方法，方法级 {@link RequireConfirm} 单独生效。
     *
     * @return {@code true} 表示该类全部工具调用前需人工确认
     */
    boolean requireConfirm() default false;
}
