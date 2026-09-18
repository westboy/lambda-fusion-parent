package com.lambda.fusion.ai.chat.runtime.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.exception.AiBusinessException;
import io.agentscope.core.agui.event.AguiEvent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ChatRunEventStoreTest {

    private ChatRunEventStore store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.shutdown();
        }
    }

    @Test
    void shouldAllowSubscriptionBeforeFirstEvent() {
        store = newStore(64, 64);

        store.registerLocalRun("run-1");

        ChatRunEventSubscription subscription = store.subscribe("run-1", 0, ignored -> {}, ignored -> {});
        assertThat(subscription).isNotNull();
        subscription.close();
    }

    @Test
    void shouldReplayThenContinueWithLiveEventsWithoutGap() throws Exception {
        store = newStore(64, 64);
        append("a");
        append("b");

        List<ChatRunEvent> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        ChatRunEventSubscription subscription = store.subscribe(
                "run-1",
                1,
                event -> {
                    received.add(event);
                    latch.countDown();
                },
                error -> {});
        append("c");

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).extracting(ChatRunEvent::cursor).containsExactly(2L, 3L);
        assertThat(received.getLast().data())
                .contains("\"chatRunId\":\"run-1\"")
                .contains("\"runId\":\"phase-1\"")
                .doesNotContain("\"seq\"");
        subscription.close();
    }

    @Test
    void shouldDeliverEachLiveEventOnceAfterReplayBecomesIdle() throws Exception {
        store = newStore(64, 64);
        append("a");
        List<Long> received = new CopyOnWriteArrayList<>();
        CountDownLatch replayed = new CountDownLatch(1);
        ChatRunEventSubscription subscription = store.subscribe(
                "run-1",
                0,
                event -> {
                    received.add(event.cursor());
                    replayed.countDown();
                },
                error -> {});
        assertThat(replayed.await(2, TimeUnit.SECONDS)).isTrue();

        append("b");
        append("c");
        awaitSize(received, 3);

        assertThat(received).containsExactly(1L, 2L, 3L);
        subscription.close();
    }

    @Test
    void shouldNotifyAndDetachSlowSubscriberWhenQueueIsFull() throws Exception {
        store = newStore(64, 1);
        append("seed");
        CountDownLatch consumerEntered = new CountDownLatch(1);
        CountDownLatch releaseConsumer = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ChatRunEventSubscription subscription = store.subscribe(
                "run-1",
                1,
                event -> {
                    consumerEntered.countDown();
                    try {
                        releaseConsumer.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                },
                error -> {
                    failure.set(error);
                    failed.countDown();
                });

        try {
            append("a");
            assertThat(consumerEntered.await(2, TimeUnit.SECONDS)).isTrue();
            append("b");
            append("c");
            assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).hasMessageContaining("run-1");
        } finally {
            releaseConsumer.countDown();
            subscription.close();
        }
    }

    @Test
    void shouldAppendTerminalOnlyOnce() {
        store = newStore(64, 64);
        AguiEvent terminalEvent =
                new AguiEvent.RunFinished("thread-1", "phase-1", null, new AguiEvent.RunFinishedSuccessOutcome());

        ChatRunEvent first = store.appendTerminalIfAbsent("run-1", "phase-1", terminalEvent, "COMPLETED", "SUCCESS");
        ChatRunEvent retried = store.appendTerminalIfAbsent("run-1", "phase-1", terminalEvent, "COMPLETED", "SUCCESS");

        assertThat(retried).isEqualTo(first);
        assertThat(store.latestCursor("run-1")).isEqualTo(1L);
    }

    @Test
    void shouldWriteTerminalEventWithoutDuplicatedMetadataKeys() {
        store = newStore(64, 64);
        AguiEvent terminalEvent =
                new AguiEvent.RunFinished("thread-1", "phase-1", null, new AguiEvent.RunFinishedSuccessOutcome());

        ChatRunEvent appended = store.appendTerminalIfAbsent("run-1", "phase-1", terminalEvent, "COMPLETED", "SUCCESS");

        // runId/chatRunId 已由 encodeRunEvent 写入，终态包装不得重复追加同名字段（重复键属非法 JSON）。
        assertThat(occurrences(appended.data(), "\"chatRunId\"")).isEqualTo(1);
        assertThat(occurrences(appended.data(), "\"runId\"")).isEqualTo(1);
        assertThat(appended.data())
                .contains("\"chatRunId\":\"run-1\"")
                .contains("\"runId\":\"phase-1\"")
                .contains("\"chatRunStatus\":\"COMPLETED\"")
                .contains("\"finishReason\":\"SUCCESS\"");
    }

    @Test
    void shouldBoundLocalReplayWindowWithoutDatabaseWatermark() throws Exception {
        store = newStore(2, 64);
        append("a");
        append("b");
        append("c");

        List<Long> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        ChatRunEventSubscription subscription = store.subscribe(
                "run-1",
                0,
                event -> {
                    received.add(event.cursor());
                    latch.countDown();
                },
                error -> {});

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).containsExactly(2L, 3L);
        subscription.close();
    }

    @Test
    void shouldTreatCursorAheadOfWindowAsLiveOnly() throws Exception {
        store = newStore(64, 64);
        append("a");
        List<Long> received = new CopyOnWriteArrayList<>();
        ChatRunEventSubscription subscription =
                store.subscribe("run-1", 99, event -> received.add(event.cursor()), error -> {});

        append("b");
        awaitSize(received, 1);

        assertThat(received).containsExactly(2L);
        subscription.close();
    }

    @Test
    void shouldRejectSubscribeWhenBufferIsMissing() {
        store = newStore(2, 64);

        assertThatThrownBy(() -> store.subscribe("missing", 0, event -> {}, error -> {}))
                .isInstanceOf(AiBusinessException.class);
    }

    @Test
    void shouldPurgeTerminalBufferAfterRetention() throws Exception {
        store = newStore(64, 64);
        append("a");
        store.markTerminal("run-1", Duration.ofMillis(1));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (store.contains("run-1") && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }

        assertThat(store.contains("run-1")).isFalse();
    }

    private void append(String delta) {
        store.appendAll(
                "run-1", "phase-1", List.of(new AguiEvent.TextMessageContent("session-1", "phase-1", "m-1", delta)));
    }

    private static ChatRunEventStore newStore(int maxEvents, int queueSize) {
        AiProperties properties = new AiProperties();
        properties.getChat().getRun().setMaxEvents(maxEvents);
        properties.getChat().getRun().setMaxBytes(65_536);
        properties.getChat().getRun().setSubscriberQueueSize(queueSize);
        return new ChatRunEventStore(properties);
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + token.length())) {
            count++;
        }
        return count;
    }

    private static void awaitSize(List<?> values, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (values.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(values).hasSize(expected);
    }
}
