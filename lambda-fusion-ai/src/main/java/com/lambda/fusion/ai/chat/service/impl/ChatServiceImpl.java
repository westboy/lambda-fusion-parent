package com.lambda.fusion.ai.chat.service.impl;

import com.lambda.cloud.core.utils.Assert;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.model.ChatRun;
import com.lambda.fusion.ai.chat.model.ConfirmToolCall;
import com.lambda.fusion.ai.chat.model.ConfirmTransition;
import com.lambda.fusion.ai.chat.model.RunContext;
import com.lambda.fusion.ai.chat.model.SendMessage;
import com.lambda.fusion.ai.chat.model.SubmitToolInput;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.ChatExecutionService;
import com.lambda.fusion.ai.chat.runtime.agui.AguiBootstrapModel;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEvent;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEventStore;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEventSubscription;
import com.lambda.fusion.ai.chat.service.ChatRunService;
import com.lambda.fusion.ai.chat.service.ChatService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 唯一的对话入口。SSE 只订阅 Run 事件，连接断开不会取消后台 Agent。 */
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ChatRunService chatRunService;
    private final ChatExecutionService chatExecutionService;
    private final ChatRunEventStore eventStore;
    private final AiProperties properties;
    /** SSE 心跳发送器：单线程守护池，仅写注释行，阻塞或失败由 detach 机制摘除任务。 */
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "chat-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public SseEmitter streamChat(String sessionId, SendMessage message) {
        Assert.isTrue(message.isContentOrAttachmentPresent(), "消息内容与附件不能同时为空");
        RunContext context = chatRunService.createOrLoad(sessionId, message);
        if (context.created()) {
            chatExecutionService.start(context.run(), context.session());
        }
        return openRunEventStream(context.run(), context.session());
    }

    @Override
    public Optional<ChatRun> activeRun(String sessionId) {
        return chatRunService.getActiveOwned(sessionId);
    }

    @Override
    public ChatRun getRun(String sessionId, String runId) {
        return chatRunService.getOwned(sessionId, runId);
    }

    @Override
    public SseEmitter resume(String sessionId, String runId) {
        RunContext context = chatRunService.loadOwned(sessionId, runId);
        return openRunEventStream(context.run(), context.session());
    }

    @Override
    public SseEmitter confirm(String sessionId, String runId, ConfirmToolCall command) {
        RunContext context = chatRunService.loadOwned(sessionId, runId);
        ConfirmTransition transition = chatExecutionService.confirm(context.run(), context.session(), command);
        return openRunEventStream(transition.run(), transition.session());
    }

    @Override
    public SseEmitter submitInput(String sessionId, String runId, SubmitToolInput command) {
        RunContext context = chatRunService.loadOwned(sessionId, runId);
        ConfirmTransition transition = chatExecutionService.submitInput(context.run(), context.session(), command);
        return openRunEventStream(transition.run(), transition.session());
    }

    @Override
    public void stop(String sessionId, String runId) {
        RunContext context = chatRunService.loadOwned(sessionId, runId);
        chatExecutionService.stop(context.run(), context.session());
    }

    private SseEmitter openRunEventStream(ChatRunEntity chatRunEntity, ChatSessionEntity chatSessionEntity) {
        long timeout = properties.getChat().getRun().getConnectionTimeoutSeconds() * 1000;
        SseEmitter emitter = new SseEmitter(timeout);
        AtomicBoolean detached = new AtomicBoolean();
        AtomicReference<ChatRunEventSubscription> subscription = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
        Runnable runnable = () -> {
            if (!detached.compareAndSet(false, true)) {
                return;
            }
            cancelHeartbeat(heartbeat);
            ChatRunEventSubscription current = subscription.getAndSet(null);
            if (current != null) {
                current.close();
            }
        };
        emitter.onCompletion(runnable);
        emitter.onTimeout(runnable);
        emitter.onError(error -> runnable.run());
        scheduleHeartbeat(emitter, runnable, heartbeat);

        try {
            AguiBootstrapModel aguiBootstrapModel = chatExecutionService.bootstrap(chatRunEntity, chatSessionEntity);
            for (String event : aguiBootstrapModel.events()) {
                emitter.send(SseEmitter.event().data(event));
            }
            if (aguiBootstrapModel.phaseClosed()) {
                emitter.complete();
                return emitter;
            }
            ChatRunEventSubscription attached = eventStore.subscribe(
                    chatRunEntity.getId(),
                    aguiBootstrapModel.cursor(),
                    event -> send(emitter, event),
                    emitter::completeWithError);
            subscription.set(attached);
            if (detached.get()) {
                attached.close();
            }
        } catch (RuntimeException | IOException error) {
            runnable.run();
            emitter.completeWithError(error);
        }
        return emitter;
    }

    /** 按配置间隔向订阅连接发送 SSE 注释心跳；发送失败仅解除订阅并取消后续心跳，不影响 Run 执行。 */
    private void scheduleHeartbeat(SseEmitter emitter, Runnable detach, AtomicReference<ScheduledFuture<?>> heartbeat) {
        long intervalSeconds = properties.getChat().getRun().getHeartbeatIntervalSeconds();
        if (intervalSeconds <= 0) {
            return;
        }
        ScheduledFuture<?> task = heartbeatExecutor.scheduleWithFixedDelay(
                () -> {
                    try {
                        emitter.send(SseEmitter.event().comment("ping"));
                    } catch (IOException | IllegalStateException disconnected) {
                        detach.run();
                    }
                },
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS);
        heartbeat.set(task);
    }

    /** 取消未执行的心跳任务并清空引用；允许重复调用。 */
    private static void cancelHeartbeat(AtomicReference<ScheduledFuture<?>> heartbeat) {
        ScheduledFuture<?> task = heartbeat.getAndSet(null);
        if (task != null) {
            task.cancel(false);
        }
    }

    /** 停机时中断心跳调度线程池；连接断开与终态不再需要心跳。 */
    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    private static void send(SseEmitter emitter, ChatRunEvent event) {
        try {
            emitter.send(SseEmitter.event().data(event.data()));
            if ("RUN_FINISHED".equals(event.type()) || "RUN_ERROR".equals(event.type())) {
                emitter.complete();
            }
        } catch (IOException | IllegalStateException disconnected) {
            emitter.completeWithError(disconnected);
            throw new SubscriberDisconnectedException(disconnected);
        }
    }

    private static final class SubscriberDisconnectedException extends RuntimeException {
        private SubscriberDisconnectedException(Throwable cause) {
            super(cause);
        }
    }
}
