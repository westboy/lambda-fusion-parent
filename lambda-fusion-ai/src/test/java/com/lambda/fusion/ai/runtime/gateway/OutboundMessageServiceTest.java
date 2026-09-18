package com.lambda.fusion.ai.runtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.chat.model.SendOutbound;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 出站投递服务的参数校验与路由测试。 */
class OutboundMessageServiceTest {

    private final HarnessGateway gateway = mock(HarnessGateway.class);
    private final ChannelManager channelManager = mock(ChannelManager.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<HarnessGateway> gatewayProvider = mock(ObjectProvider.class);

    private OutboundMessageService serviceWithGateway() {
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        return new OutboundMessageService(gatewayProvider);
    }

    @Test
    void shouldRejectEmptyMessages() {
        OutboundMessageService service = serviceWithGateway();
        SendOutbound req = new SendOutbound();
        req.setSessionId("session-1");
        req.setMessages(List.of());

        assertThat(service.send(req).error()).isEqualTo("messages 不能为空");
    }

    @Test
    void shouldFailWhenGatewayUnavailable() {
        when(gatewayProvider.getIfAvailable()).thenReturn(null);
        OutboundMessageService service = new OutboundMessageService(gatewayProvider);
        SendOutbound req = new SendOutbound();
        req.setSessionId("session-1");
        req.setMessages(List.of("hello"));

        assertThat(service.send(req).error()).contains("Gateway 未启用");
    }

    @Test
    void shouldRejectAmbiguousOrMissingTarget() {
        OutboundMessageService service = serviceWithGateway();
        SendOutbound both = new SendOutbound();
        both.setSessionId("session-1");
        both.setChannelId("channel-1");
        both.setTo("dingtalk:GROUP:cid");
        both.setMessages(List.of("hello"));
        assertThat(service.send(both).error()).contains("二选一");

        SendOutbound neither = new SendOutbound();
        neither.setMessages(List.of("hello"));
        assertThat(service.send(neither).error()).contains("二选一");
    }

    @Test
    void shouldDeliverToSession() {
        OutboundMessageService service = serviceWithGateway();
        when(gateway.deliverToSession(anyString(), anyList())).thenReturn(true);
        SendOutbound req = new SendOutbound();
        req.setSessionId("session-1");
        req.setMessages(List.of("hello"));

        OutboundMessageService.OutboundDelivery result = service.send(req);

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.matchedBy()).isEqualTo("session:session-1");
        verify(gateway).deliverToSession(eq("session-1"), anyList());
    }

    @Test
    void shouldReportSkippedWhenSessionNotMatched() {
        OutboundMessageService service = serviceWithGateway();
        when(gateway.deliverToSession(anyString(), anyList())).thenReturn(false);
        SendOutbound req = new SendOutbound();
        req.setSessionId("session-1");
        req.setMessages(List.of("hello"));

        assertThat(service.send(req).status()).isEqualTo("skipped");
    }

    @Test
    void shouldFailChannelDeliveryWhenChannelManagerMissing() {
        OutboundMessageService service = serviceWithGateway();
        when(gateway.channelManager()).thenReturn(null);
        SendOutbound req = new SendOutbound();
        req.setChannelId("channel-1");
        req.setTo("dingtalk:GROUP:cid");
        req.setMessages(List.of("hello"));

        assertThat(service.send(req).error()).contains("ChannelManager 未配置");
    }

    @Test
    void shouldDeliverToExplicitChannelAddress() {
        OutboundMessageService service = serviceWithGateway();
        when(gateway.channelManager()).thenReturn(channelManager);
        SendOutbound req = new SendOutbound();
        req.setChannelId("channel-1");
        req.setTo("dingtalk:GROUP:cid");
        req.setMessages(List.of("hello"));

        OutboundMessageService.OutboundDelivery result = service.send(req);

        assertThat(result.status()).isEqualTo("ok");
        verify(channelManager).deliver(any(OutboundAddress.class), anyList());
        verify(gateway, never()).deliverToSession(anyString(), anyList());
    }
}
