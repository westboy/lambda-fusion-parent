package com.lambda.fusion.ai.channel.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.fusion.ai.channel.model.ChannelConfigPage;
import com.lambda.fusion.ai.channel.model.ChannelDefinition;
import com.lambda.fusion.ai.channel.model.CreateChannelConfig;
import com.lambda.fusion.ai.channel.model.UpdateChannelConfig;
import com.lambda.fusion.ai.channel.model.entity.ChannelConfigEntity;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.util.List;

/**
 * 通道路由配置服务：CRUD + 按 channelId 解析为 harness {@link ChannelConfig} 供下发，
 * + 加载/解析 {@link ChannelDefinition} 供 {@code ChannelBootstrap} 构造 channel。
 *
 * @author Jin
 */
public interface ChannelConfigService {

    Page<ChannelConfigEntity> page(ChannelConfigPage query);

    ChannelConfigEntity get(String id);

    ChannelConfigEntity getByChannelId(String channelId);

    ChannelConfigEntity create(CreateChannelConfig dto);

    void update(String id, UpdateChannelConfig dto);

    void delete(String id);

    ChannelConfigEntity loadById(String id);

    /** 返回启用的 AgentScope 运行时路由配置，并按需注册其中引用的平台应用 Agent；不存在或禁用时返回 null。 */
    ChannelConfig resolve(String channelId);

    /** 加载启用渠道的构造视图，包含已解密凭证和持久化的可读 Agent 引用。 */
    List<ChannelDefinition> loadEnabledDefinitions();

    /** 返回单个启用渠道的构造视图；不存在或禁用时返回 null。 */
    ChannelDefinition resolveDefinition(String channelId);
}
