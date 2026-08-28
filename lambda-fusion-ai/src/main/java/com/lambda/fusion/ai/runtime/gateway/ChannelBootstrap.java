package com.lambda.fusion.ai.runtime.gateway;

import com.lambda.fusion.ai.channel.model.ChannelDefinition;
import com.lambda.fusion.ai.channel.service.ChannelConfigService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelFactory;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * 根据数据库配置启动和重建消息渠道。应用就绪时加载 {@code ai_channel_config} 中的启用记录，
 * 由对应类型的 {@link ChannelFactory} 创建渠道并注册到 {@link ChannelManager}。单个渠道缺少工厂或启动失败时
 * 仅跳过该渠道，不影响其他渠道及应用启动。本组件只在 Harness 网关启用时装配。
 *
 * @author Jin
 */
@Slf4j
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class ChannelBootstrap {

    private final ChannelManager channelManager;
    private final HarnessGateway gateway;
    private final ChannelConfigService channelConfigService;
    private final Map<String, ChannelFactory> factories;
    private final ChannelAgentResolver channelAgentResolver;

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapAll() {
        List<ChannelDefinition> defs = channelConfigService.loadEnabledDefinitions();
        if (defs.isEmpty()) {
            return;
        }
        log.info("启动 DB 渠道: {} 个", defs.size());
        for (ChannelDefinition def : defs) {
            build(def);
        }
    }

    /** 先停止并注销旧渠道，再按最新数据库配置重建；配置已禁用或删除时仅执行注销。 */
    public void rebuild(String channelId) {
        stopAndUnregister(channelId);
        ChannelDefinition def = channelConfigService.resolveDefinition(channelId);
        if (def == null) {
            return;
        }
        build(def);
    }

    private void build(ChannelDefinition def) {
        ChannelFactory factory = factories.get(def.type());
        if (factory == null) {
            log.warn("渠道 {} 的适配器类型 {} 无可用工厂(扩展未引入)，跳过", def.channelId(), def.type());
            return;
        }
        try {
            Channel channel =
                    factory.create(def.channelId(), channelAgentResolver.resolve(def.routing()), def.properties());
            channelManager.register(channel);
            channel.init(gateway);
            channel.start();
            log.info("渠道已启动: channelId={}, type={}", def.channelId(), def.type());
        } catch (Exception e) {
            log.warn("渠道 {} 构造失败: {}", def.channelId(), e.getMessage(), e);
        }
    }

    private void stopAndUnregister(String channelId) {
        channelManager.getChannel(channelId).ifPresent(old -> {
            try {
                old.stop();
            } catch (Exception e) {
                log.warn("渠道 {} 停止失败: {}", channelId, e.getMessage());
            }
            channelManager.unregister(channelId);
        });
    }
}
