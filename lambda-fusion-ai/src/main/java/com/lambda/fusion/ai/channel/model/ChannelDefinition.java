package com.lambda.fusion.ai.channel.model;

import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.util.Map;

/**
 * 渠道运行时定义，包含渠道标识、适配器类型、已解密的平台凭证和路由配置。
 *
 * @param channelId 通道标识
 * @param type 适配器类型（dingtalk/feishu/wecom）
 * @param properties 已解密的平台凭证
 * @param routing 持久化的 Harness 路由配置；平台应用引用在渠道构建前解析为运行时 Agent ID
 */
public record ChannelDefinition(String channelId, String type, Map<String, Object> properties, ChannelConfig routing) {}
