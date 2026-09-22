package com.lambda.fusion.workflow;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Workflow 模块配置属性
 *
 * <p>设计器开关沿用引擎自身的 {@code warm-flow.ui}（默认 true），不在本模块重复定义，
 * 避免双开关漂移（单一事实来源）。
 *
 * @author jin
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "lambda.fusion.workflow")
public class WorkflowProperties {

    /**
     * 模块总开关：关闭后不再装配 REST 出口与桥接 Bean（引擎本体由 warm-flow.enabled 独立控制）
     */
    private boolean enabled = true;
}
