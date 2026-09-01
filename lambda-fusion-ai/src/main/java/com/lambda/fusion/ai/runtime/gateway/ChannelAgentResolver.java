package com.lambda.fusion.ai.runtime.gateway;

import com.lambda.fusion.ai.runtime.AgentFactory;
import io.agentscope.harness.agent.gateway.channel.ChannelBinding;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 将平台可读的应用 Agent 引用适配为 AgentScope Gateway 使用的运行时 ID。
 *
 * <p>解析 {@code app:{appId}:t:{tenantId}} 时由 {@link AgentFactory} 构建不含 ChatRun 专属工具的
 * Channel Agent，并返回该实例已经注册到 Gateway 的稳定 ID。其他 ID 原样透传。
 *
 * @author Jin
 */
@Component
@RequiredArgsConstructor
public class ChannelAgentResolver {

    private static final String APP_PREFIX = "app:";
    private static final String TENANT_SEPARATOR = ":t:";

    private final AgentFactory agentFactory;

    public ChannelConfig resolve(ChannelConfig configured) {
        List<ChannelBinding> bindings =
                configured.bindings().stream().map(this::resolveBinding).toList();
        return new ChannelConfig(
                configured.channelId(), resolveAgentId(configured.defaultAgentId()), configured.dmScope(), bindings);
    }

    private ChannelBinding resolveBinding(ChannelBinding configured) {
        return new ChannelBinding(
                resolveAgentId(configured.agentId()),
                configured.peer(),
                configured.parentPeer(),
                configured.guild(),
                configured.roles(),
                configured.team(),
                configured.account(),
                configured.channel(),
                configured.sessionScope());
    }

    private String resolveAgentId(String configuredId) {
        if (StringUtils.isBlank(configuredId) || !configuredId.startsWith(APP_PREFIX)) {
            return configuredId;
        }
        int tenantSeparator = configuredId.indexOf(TENANT_SEPARATOR, APP_PREFIX.length());
        if (tenantSeparator < 0) {
            return configuredId;
        }
        String appId = configuredId.substring(APP_PREFIX.length(), tenantSeparator);
        String tenantId = configuredId.substring(tenantSeparator + TENANT_SEPARATOR.length());
        if (StringUtils.isAnyBlank(appId, tenantId)) {
            return configuredId;
        }
        return agentFactory.getOrBuildForChannel(appId, tenantId).getAgentId();
    }
}
