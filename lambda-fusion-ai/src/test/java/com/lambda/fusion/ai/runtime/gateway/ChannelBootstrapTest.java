package com.lambda.fusion.ai.runtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.fusion.ai.channel.model.ChannelConfigPage;
import com.lambda.fusion.ai.channel.model.ChannelDefinition;
import com.lambda.fusion.ai.channel.model.CreateChannelConfig;
import com.lambda.fusion.ai.channel.model.UpdateChannelConfig;
import com.lambda.fusion.ai.channel.model.entity.ChannelConfigEntity;
import com.lambda.fusion.ai.channel.service.ChannelConfigService;
import com.lambda.fusion.ai.runtime.AgentFactory;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelFactory;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 验证 {@link ChannelBootstrap}：按 type 查找工厂构造 channel 注册进 {@link ChannelManager}，
 * 无定义/无工厂时正确跳过/清理。
 *
 * @author Jin
 */
class ChannelBootstrapTest {

    @Test
    void bootstrapAllRegistersChannels() {
        StubChannel channel = new StubChannel("c1");
        ChannelFactory factory = (channelId, routing, properties) -> channel;
        Map<String, ChannelDefinition> defs = new HashMap<>();
        defs.put("c1", new ChannelDefinition("c1", "stub", Map.of(), ChannelConfig.of("c1")));
        StubService service = new StubService(defs);
        ChannelManager cm = new ChannelManager();
        ChannelBootstrap bootstrap = new ChannelBootstrap(
                cm,
                HarnessGateway.create(cm),
                service,
                Map.of("stub", factory),
                new ChannelAgentResolver(mock(AgentFactory.class)));

        bootstrap.bootstrapAll();

        assertThat(cm.getChannel("c1")).contains(channel);
        assertThat(channel.started).isTrue();
    }

    @Test
    void rebuildRemovesChannelWhenDefinitionAbsent() {
        StubChannel channel = new StubChannel("c1");
        ChannelManager cm = new ChannelManager();
        cm.register(channel);
        channel.started = true;
        StubService service = new StubService(new HashMap<>()); // resolveDefinition returns null
        ChannelBootstrap bootstrap = new ChannelBootstrap(
                cm, HarnessGateway.create(cm), service, Map.of(), new ChannelAgentResolver(mock(AgentFactory.class)));

        bootstrap.rebuild("c1");

        assertThat(cm.getChannel("c1")).isEmpty();
        assertThat(channel.stopped).isTrue();
    }

    @Test
    void rebuildSkipsWhenNoFactoryForType() {
        ChannelDefinition def = new ChannelDefinition("c1", "unsupported", Map.of(), ChannelConfig.of("c1"));
        Map<String, ChannelDefinition> defs = new HashMap<>();
        defs.put("c1", def);
        StubService service = new StubService(defs);
        ChannelManager cm = new ChannelManager();
        ChannelBootstrap bootstrap = new ChannelBootstrap(
                cm, HarnessGateway.create(cm), service, Map.of(), new ChannelAgentResolver(mock(AgentFactory.class)));

        bootstrap.rebuild("c1");

        assertThat(cm.getChannel("c1")).isEmpty();
    }

    @Test
    void bootstrapAllIsolatesAgentResolutionFailurePerChannel() {
        AgentFactory agentFactory = mock(AgentFactory.class);
        when(agentFactory.getOrBuildForChannel("bad", "tenant"))
                .thenThrow(new IllegalStateException("app unavailable"));
        Map<String, ChannelDefinition> defs = new LinkedHashMap<>();
        defs.put(
                "bad-channel",
                new ChannelDefinition(
                        "bad-channel",
                        "stub",
                        Map.of(),
                        new ChannelConfig("bad-channel", "app:bad:t:tenant", DmScope.MAIN, List.of())));
        defs.put(
                "good-channel",
                new ChannelDefinition(
                        "good-channel",
                        "stub",
                        Map.of(),
                        new ChannelConfig("good-channel", "external-agent", DmScope.MAIN, List.of())));
        ChannelFactory factory = (channelId, routing, properties) -> new StubChannel(channelId);
        StubService service = new StubService(defs);
        ChannelManager cm = new ChannelManager();
        ChannelBootstrap bootstrap = new ChannelBootstrap(
                cm,
                HarnessGateway.create(cm),
                service,
                Map.of("stub", factory),
                new ChannelAgentResolver(agentFactory));

        bootstrap.bootstrapAll();

        assertThat(cm.getChannel("bad-channel")).isEmpty();
        assertThat(cm.getChannel("good-channel")).isPresent();
    }

    // 最小 Channel 桩：记录 start/stop
    static final class StubChannel implements Channel {
        private final String id;
        boolean started;
        boolean stopped;

        StubChannel(String id) {
            this.id = id;
        }

        @Override
        public String channelId() {
            return id;
        }

        @Override
        public ChannelConfig config() {
            return ChannelConfig.of(id);
        }

        @Override
        public Mono<Msg> dispatch(InboundMessage message) {
            return Mono.empty();
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }

    // 只实现 loadEnabledDefinitions/resolveDefinition，其余抛 UnsupportedOperationException
    static final class StubService implements ChannelConfigService {
        private final Map<String, ChannelDefinition> defs;

        StubService(Map<String, ChannelDefinition> defs) {
            this.defs = defs;
        }

        @Override
        public List<ChannelDefinition> loadEnabledDefinitions() {
            return List.copyOf(defs.values());
        }

        @Override
        public ChannelDefinition resolveDefinition(String channelId) {
            return defs.get(channelId);
        }

        @Override
        public Page<ChannelConfigEntity> page(ChannelConfigPage query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelConfigEntity get(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelConfigEntity getByChannelId(String channelId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelConfigEntity create(CreateChannelConfig dto) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update(String id, UpdateChannelConfig dto) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelConfigEntity loadById(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelConfig resolve(String channelId) {
            throw new UnsupportedOperationException();
        }
    }
}
