package com.lambda.fusion.ai.chat.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.model.RunContext;
import com.lambda.fusion.ai.chat.model.SendMessage;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.ChatExecutionService;
import com.lambda.fusion.ai.chat.runtime.agui.AguiBootstrapModel;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEventStore;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEventSubscription;
import com.lambda.fusion.ai.chat.service.ChatRunService;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** SSE 心跳线程模型测试：心跳写盘卸载到虚拟线程，不占调度线程与请求线程。 */
class ChatServiceImplHeartbeatTest {

    private final ChatRunService runService = mock(ChatRunService.class);
    private final ChatExecutionService chatExecutionService = mock(ChatExecutionService.class);
    private final ChatRunEventStore eventStore = mock(ChatRunEventStore.class);
    private final AiProperties properties = new AiProperties();
    private ChatServiceImpl chatService;

    @AfterEach
    void tearDown() {
        if (chatService != null) {
            chatService.shutdown();
        }
    }

    @Test
    void shouldSendHeartbeatOnOffloadedVirtualThread() throws Exception {
        properties.getChat().getRun().setHeartbeatIntervalSeconds(1);
        AtomicReference<Thread> heartbeatThread = new AtomicReference<>();
        CountDownLatch heartbeatSent = new CountDownLatch(1);
        SseEmitter capturingEmitter = new SseEmitter(0L) {
            @Override
            public void send(SseEventBuilder builder) {
                heartbeatThread.set(Thread.currentThread());
                heartbeatSent.countDown();
            }
        };
        chatService = new ChatServiceImpl(runService, chatExecutionService, eventStore, properties) {
            @Override
            SseEmitter newSseEmitter(long timeoutMillis) {
                return capturingEmitter;
            }
        };
        ChatRunEntity run = run();
        ChatSessionEntity session = session();
        SendMessage message = new SendMessage();
        message.setClientRequestId("req-1");
        message.setContent("hi");
        when(runService.createOrLoad("session-1", message)).thenReturn(new RunContext(run, session, true));
        when(chatExecutionService.bootstrap(run, session)).thenReturn(new AguiBootstrapModel(0L, List.of(), false));
        when(eventStore.subscribe(eq("run-1"), anyLong(), any(), any()))
                .thenReturn(mock(ChatRunEventSubscription.class));

        chatService.streamChat("session-1", message);

        assertThat(heartbeatSent.await(3, TimeUnit.SECONDS)).isTrue();
        Thread thread = heartbeatThread.get();
        assertThat(thread).isNotNull();
        // 心跳写盘在虚拟线程上执行，不在心跳调度线程（chat-sse-heartbeat）上。
        assertThat(thread.isVirtual()).isTrue();
        assertThat(thread.getName()).isNotEqualTo("chat-sse-heartbeat");
    }

    private static ChatRunEntity run() {
        ChatRunEntity run = new ChatRunEntity();
        run.setId("run-1");
        run.setSessionId("session-1");
        run.setStatus(ChatRunStatus.RUNNING.name());
        run.setPhaseNo(1);
        run.setAguiRunId("agui-1");
        return run;
    }

    private static ChatSessionEntity session() {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        session.setAppId("app-1");
        session.setTenantId("tenant-1");
        return session;
    }
}
