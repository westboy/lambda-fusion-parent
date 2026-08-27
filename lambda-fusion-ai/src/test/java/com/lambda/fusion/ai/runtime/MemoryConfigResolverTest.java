package com.lambda.fusion.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.lambda.fusion.ai.AiProperties;
import io.agentscope.harness.agent.memory.MemoryConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 验证系统级记忆刷新配置到 AgentScope MemoryConfig 的映射。 */
class MemoryConfigResolverTest {

    @Test
    void defaultsToTenMinuteThrottle() {
        MemoryConfig config = AgentFactory.resolveMemoryConfig(new AiProperties());

        assertThat(config.flushTrigger().mode()).isEqualTo(MemoryConfig.FlushMode.THROTTLED);
        assertThat(config.flushTrigger().minGap()).isEqualTo(Duration.ofMinutes(10));
        assertThat(config.model()).isNull();
    }

    @Test
    void mapsAlwaysMode() {
        AiProperties properties = properties(AiProperties.Memory.Flush.Mode.ALWAYS, Duration.ofMinutes(3));

        MemoryConfig config = AgentFactory.resolveMemoryConfig(properties);

        assertThat(config.flushTrigger()).isSameAs(MemoryConfig.FlushTrigger.always());
    }

    @Test
    void mapsNeverMode() {
        AiProperties properties = properties(AiProperties.Memory.Flush.Mode.NEVER, Duration.ofMinutes(3));

        MemoryConfig config = AgentFactory.resolveMemoryConfig(properties);

        assertThat(config.flushTrigger()).isSameAs(MemoryConfig.FlushTrigger.never());
    }

    @Test
    void mapsConfiguredThrottleGap() {
        AiProperties properties = properties(AiProperties.Memory.Flush.Mode.THROTTLED, Duration.ofMinutes(30));

        MemoryConfig config = AgentFactory.resolveMemoryConfig(properties);

        assertThat(config.flushTrigger().mode()).isEqualTo(MemoryConfig.FlushMode.THROTTLED);
        assertThat(config.flushTrigger().minGap()).isEqualTo(Duration.ofMinutes(30));
    }

    private static AiProperties properties(AiProperties.Memory.Flush.Mode mode, Duration minGap) {
        AiProperties properties = new AiProperties();
        properties.getMemory().getFlush().setMode(mode);
        properties.getMemory().getFlush().setMinGap(minGap);
        return properties;
    }
}
