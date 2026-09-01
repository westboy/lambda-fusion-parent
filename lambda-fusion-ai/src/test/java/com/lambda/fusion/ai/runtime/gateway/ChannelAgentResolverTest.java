package com.lambda.fusion.ai.runtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.runtime.AgentFactory;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.ChannelBinding;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 验证平台应用 Agent 引用进入 AgentScope Gateway 前的按需注册和稳定 ID 解析。 */
class ChannelAgentResolverTest {

    private final AgentFactory agentFactory = mock(AgentFactory.class);
    private final ChannelAgentResolver resolver = new ChannelAgentResolver(agentFactory);

    @Test
    void resolvesLogicalApplicationReferences() {
        HarnessAgent channelAgent = mock(HarnessAgent.class);
        when(channelAgent.getAgentId()).thenReturn("stable-agent-id-channel");
        when(agentFactory.getOrBuildForChannel("1", "2")).thenReturn(channelAgent);
        ChannelBinding binding =
                new ChannelBinding("app:1:t:2", "peer", null, null, Set.of(), null, null, null, DmScope.PER_PEER);
        ChannelConfig configured = new ChannelConfig("c", "app:1:t:2", DmScope.PER_CHANNEL_PEER, List.of(binding));

        ChannelConfig resolved = resolver.resolve(configured);

        assertThat(resolved.defaultAgentId()).isEqualTo("stable-agent-id-channel");
        assertThat(resolved.bindings())
                .singleElement()
                .extracting(ChannelBinding::agentId)
                .isEqualTo("stable-agent-id-channel");
        assertThat(resolved.dmScope()).isEqualTo(DmScope.PER_CHANNEL_PEER);
        verify(agentFactory, times(2)).getOrBuildForChannel("1", "2");
    }

    @Test
    void preservesExternalAndMalformedAgentIds() {
        ChannelBinding binding =
                new ChannelBinding("app:missing-tenant", "peer", null, null, Set.of(), null, null, null, DmScope.MAIN);
        ChannelConfig configured = new ChannelConfig("c", "external-agent", DmScope.MAIN, List.of(binding));

        ChannelConfig resolved = resolver.resolve(configured);

        assertThat(resolved.defaultAgentId()).isEqualTo("external-agent");
        assertThat(resolved.bindings())
                .singleElement()
                .extracting(ChannelBinding::agentId)
                .isEqualTo("app:missing-tenant");
        verifyNoInteractions(agentFactory);
    }
}
