package com.lambda.fusion.message;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Message 模块配置属性
 *
 * @author jin
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "lambda.fusion.message")
public class MessageProperties {

    /**
     * 是否启用 SSE 推送（站内信投递后向在线用户推送刷新信号）；推送为增强能力，关闭或 SSE 缺失时降级为纯落库
     */
    private boolean pushEnabled = true;
}
