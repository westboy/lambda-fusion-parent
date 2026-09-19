package com.lambda.fusion.ai.chat.runtime.event;

import com.lambda.fusion.ai.chat.runtime.agui.AguiEventJsonCodec;
import io.agentscope.core.agui.event.AguiEvent;
import java.io.Serial;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 单个 Run 的进程内事件窗口。游标只用于衔接“快照之后”的本地事件，不进入数据库或协议载荷。
 *
 * @author Jin
 */
final class ChatRunEventBuffer {

    private final String runId;
    private final int maxEvents;
    private final long maxBytes;
    private final int subscriberQueueSize;
    private final Executor senderExecutor;
    private final ArrayDeque<ChatRunEvent> events = new ArrayDeque<>();
    private final Map<String, QueuedEventSubscription> subscribers = new HashMap<>();

    private ChatRunEvent terminal;
    private long nextCursor = 1;
    private long bytes;
    private long expiresAt;

    /**
     * 创建运行缓冲。
     *
     * @param runId 运行标识
     * @param maxEvents 窗口最大事件数
     * @param maxBytes 窗口最大字节数
     * @param subscriberQueueSize 单个订阅者的实时事件队列容量
     * @param senderExecutor 订阅者事件发送执行器（由多个缓冲共享）
     */
    ChatRunEventBuffer(String runId, int maxEvents, long maxBytes, int subscriberQueueSize, Executor senderExecutor) {
        this.runId = runId;
        this.maxEvents = maxEvents;
        this.maxBytes = maxBytes;
        this.subscriberQueueSize = subscriberQueueSize;
        this.senderExecutor = senderExecutor;
    }

    /**
     * 追加一批标准化 AG-UI 事件：分配递增游标、编码为 JSON 后入窗口并发布。
     *
     * @param aguiEvents 标准化 AG-UI 事件批次；空批次直接忽略
     * @param aguiRunId AG-UI 运行标识
     */
    synchronized void append(List<AguiEvent> aguiEvents, String aguiRunId) {
        if (aguiEvents == null || aguiEvents.isEmpty()) {
            return;
        }
        List<ChatRunEvent> appended = new ArrayList<>(aguiEvents.size());
        long cursor = nextCursor;
        for (AguiEvent event : aguiEvents) {
            appended.add(new ChatRunEvent(
                    cursor, event.getType().name(), AguiEventJsonCodec.encodeRunEvent(event, runId, aguiRunId)));
            cursor++;
        }
        commit(appended, false);
    }

    /**
     * 幂等补写终态事件：已有终态时返回既有事件，否则编码、补充业务元数据后入窗口并标记为终态。
     *
     * @param aguiEvent 终态 AG-UI 事件
     * @param aguiRunId AG-UI 运行标识
     * @param chatRunStatus 业务终态（写入事件元数据）
     * @param finishReason 结束原因（写入事件元数据）
     * @return 写入或已存在的终态事件
     */
    synchronized ChatRunEvent appendTerminal(
            AguiEvent aguiEvent, String aguiRunId, String chatRunStatus, String finishReason) {
        if (terminal != null) {
            return terminal;
        }
        // encodeRunEvent 已保证 runId/chatRunId 元数据齐全，这里只补终态元数据，避免重复 JSON 键。
        String json = AguiEventJsonCodec.withTerminalMetadata(
                AguiEventJsonCodec.encodeRunEvent(aguiEvent, runId, aguiRunId), chatRunStatus, finishReason);
        ChatRunEvent appended = new ChatRunEvent(nextCursor, aguiEvent.getType().name(), json);
        commit(List.of(appended), true);
        return appended;
    }

    /**
     * 事件入窗口的公共提交：校验单事件大小、推进游标、发布给订阅者并收缩窗口。
     *
     * @param appended 待写入的事件批次
     * @param terminalEvent 本批次是否包含终态事件
     * @throws IllegalStateException 单个事件超过窗口字节上限时抛出
     */
    private void commit(List<ChatRunEvent> appended, boolean terminalEvent) {
        long appendedBytes = 0;
        for (ChatRunEvent event : appended) {
            int size = eventBytes(event);
            if (size > maxBytes) {
                throw new IllegalStateException("单个 Run 事件超过本地缓冲容量: " + runId);
            }
            appendedBytes += size;
        }
        events.addAll(appended);
        bytes += appendedBytes;
        nextCursor = appended.getLast().cursor() + 1;
        if (terminalEvent) {
            terminal = appended.getFirst();
        }
        publish(appended);
        trimWindow();
    }

    /**
     * 把事件发布给全部订阅者；实时队列满（消费过慢）的订阅者被注销并回调失败，
     * 已关闭的订阅者一并摘除，且同一批次内不再向二者继续投递。
     *
     * @param appended 刚提交的事件批次
     */
    private void publish(List<ChatRunEvent> appended) {
        List<QueuedEventSubscription> slowSubscribers = new ArrayList<>();
        List<QueuedEventSubscription> closedSubscribers = new ArrayList<>();
        for (ChatRunEvent event : appended) {
            for (QueuedEventSubscription subscriber : subscribers.values()) {
                if (slowSubscribers.contains(subscriber) || closedSubscribers.contains(subscriber)) {
                    continue;
                }
                switch (subscriber.offer(event)) {
                    case FULL -> slowSubscribers.add(subscriber);
                    case CLOSED -> closedSubscribers.add(subscriber);
                    default -> {
                        // ACCEPTED：正常投递。
                    }
                }
            }
        }
        for (QueuedEventSubscription subscriber : closedSubscribers) {
            subscribers.remove(subscriber.id(), subscriber);
        }
        for (QueuedEventSubscription subscriber : slowSubscribers) {
            subscribers.remove(subscriber.id(), subscriber);
            subscriber.fail(new SlowEventSubscriberException(runId));
        }
    }

    /** 按事件数与字节上限从窗口头部淘汰旧事件，始终至少保留最新一条。 */
    private void trimWindow() {
        while ((events.size() > maxEvents || bytes > maxBytes) && events.size() > 1) {
            ChatRunEvent evicted = events.removeFirst();
            bytes -= eventBytes(evicted);
        }
    }

    /**
     * 创建订阅：游标按窗口范围收敛后，取窗口内该游标之后的事件作为回放批次，
     * 新订阅者由 {@link QueuedEventSubscription} 保证回放先于实时事件。
     *
     * @param afterCursor 起始游标（不含）
     * @param consumer 事件消费者
     * @param failureConsumer 发送失败或消费过慢时的失败消费者
     * @return 事件订阅句柄
     */
    synchronized ChatRunEventSubscription subscribe(
            long afterCursor, Consumer<ChatRunEvent> consumer, Consumer<Throwable> failureConsumer) {
        long latestCursor = nextCursor - 1;
        long cursor = Math.max(0, Math.min(afterCursor, latestCursor));
        List<ChatRunEvent> replay =
                events.stream().filter(event -> event.cursor() > cursor).toList();
        String subscriptionId = UUID.randomUUID().toString();
        QueuedEventSubscription subscriber = new QueuedEventSubscription(
                subscriptionId, runId, subscriberQueueSize, replay, consumer, failureConsumer, this, senderExecutor);
        subscribers.put(subscriptionId, subscriber);
        return subscriber;
    }

    /**
     * 查询最新已分配游标。
     *
     * @return 最新游标
     */
    synchronized long latestCursor() {
        return nextCursor - 1;
    }

    /**
     * 设置缓冲过期时间戳。
     *
     * @param value 过期时间戳（毫秒）
     */
    synchronized void markExpiresAt(long value) {
        expiresAt = value;
    }

    /**
     * 判断缓冲是否已过期（已设置过期时间且到期）。
     *
     * @param now 当前时间戳（毫秒）
     * @return 已过期返回 {@code true}
     */
    synchronized boolean expired(long now) {
        return expiresAt > 0 && expiresAt <= now;
    }

    /** 清空缓冲：断开全部订阅者、丢弃窗口事件并重置终态。 */
    synchronized void clear() {
        List<QueuedEventSubscription> current = List.copyOf(subscribers.values());
        subscribers.clear();
        events.clear();
        terminal = null;
        bytes = 0;
        current.forEach(QueuedEventSubscription::closeWithoutDetach);
    }

    /**
     * 注销订阅（按标识与实例双匹配，防止注销误删新订阅）。
     *
     * @param subscriptionId 订阅标识
     * @param identity 订阅实例
     */
    synchronized void detach(String subscriptionId, QueuedEventSubscription identity) {
        subscribers.remove(subscriptionId, identity);
    }

    /** 计算事件 JSON 的字节计量（构造时已预算好，直接读字段）。 */
    private static int eventBytes(ChatRunEvent event) {
        return event.byteSize();
    }

    /** 订阅者实时队列溢出时用于断开并回调失败的内部异常。 */
    private static final class SlowEventSubscriberException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private SlowEventSubscriberException(String runId) {
            super("Run 订阅者消费过慢，已断开: " + runId);
        }
    }
}
