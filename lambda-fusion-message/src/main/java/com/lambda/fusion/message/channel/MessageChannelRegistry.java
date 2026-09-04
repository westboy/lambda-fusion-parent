package com.lambda.fusion.message.channel;

import com.lambda.fusion.message.exception.MessageBusinessException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 消息渠道路由：收集容器内所有 MessageChannel，按渠道标识分发
 *
 * @author jin
 */
@Component
public class MessageChannelRegistry {

    private final Map<String, MessageChannel> channels;

    public MessageChannelRegistry(List<MessageChannel> channelList) {
        this.channels = channelList.stream()
                .collect(Collectors.toUnmodifiableMap(MessageChannel::channel, Function.identity()));
    }

    /**
     * 按渠道标识获取渠道
     *
     * @param channel 渠道标识
     * @return 渠道实现
     */
    public MessageChannel get(String channel) {
        MessageChannel target = channels.get(channel);
        if (target == null) {
            throw MessageBusinessException.channelNotFound(channel);
        }
        return target;
    }
}
