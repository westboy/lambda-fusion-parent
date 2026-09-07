package com.lambda.fusion.logger;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Logger 模块配置属性
 *
 * @author zx
 */
@Getter
@Setter
@SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "springboot properties class")
@ConfigurationProperties(prefix = "lambda.fusion.logger")
public class LoggerProperties {

    /** 存储后端类型：MYSQL（默认），预留 KAFKA / ES。 */
    private String storageType = "MYSQL";

    /** 是否异步落库（默认 true，异步线程池执行，避免阻塞业务线程）。 */
    private boolean async = true;

    /**
     * 异步落库线程池配置
     */
    @NestedConfigurationProperty
    private Executor executor = new Executor();

    @Getter
    @Setter
    public static class Executor {

        /** 核心线程数。 */
        private int corePoolSize = 5;

        /** 最大线程数。 */
        private int maxPoolSize = 10;

        /** 队列容量。 */
        private int queueCapacity = 1000;
    }
}
