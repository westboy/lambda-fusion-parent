package com.lambda.fusion.ai.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.ChatRunCapacityLimiter.Permit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ChatRunCapacityLimiterTest {

    @Test
    void shouldReleaseGlobalAndPerUserCapacityWithPermit() {
        AiProperties properties = new AiProperties();
        properties.getChat().getRun().setMaxActiveRuns(2);
        properties.getChat().getRun().setMaxActiveRunsPerUser(1);
        ChatRunCapacityLimiter limiter = new ChatRunCapacityLimiter(properties);

        Permit first = limiter.tryAcquire(session("tenant-1", "user-1")).orElseThrow();
        assertThat(limiter.tryAcquire(session("tenant-1", "user-1"))).isEmpty();
        Permit second = limiter.tryAcquire(session("tenant-1", "user-2")).orElseThrow();
        assertThat(limiter.tryAcquire(session("tenant-1", "user-3"))).isEmpty();

        first.close();
        first.close();
        Permit reacquired = limiter.tryAcquire(session("tenant-1", "user-1")).orElseThrow();
        second.close();
        reacquired.close();
    }

    @Test
    void shouldAcquireSingleGlobalPermitAtomically() throws Exception {
        AiProperties properties = new AiProperties();
        properties.getChat().getRun().setMaxActiveRuns(1);
        ChatRunCapacityLimiter limiter = new ChatRunCapacityLimiter(properties);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<Optional<Permit>>> attempts = List.of(
                    attemptAcquire(executor, limiter, session("tenant-1", "user-1"), ready, start),
                    attemptAcquire(executor, limiter, session("tenant-1", "user-2"), ready, start));
            ready.await();
            start.countDown();

            List<Optional<Permit>> results =
                    attempts.stream().map(CompletableFuture::join).toList();
            assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
            results.forEach(result -> result.ifPresent(Permit::close));
        } finally {
            executor.shutdownNow();
        }
    }

    private static CompletableFuture<Optional<Permit>> attemptAcquire(
            ExecutorService executor,
            ChatRunCapacityLimiter limiter,
            ChatSessionEntity session,
            CountDownLatch ready,
            CountDownLatch start) {
        return CompletableFuture.supplyAsync(
                () -> {
                    ready.countDown();
                    try {
                        start.await();
                        return limiter.tryAcquire(session);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                },
                executor);
    }

    private static ChatSessionEntity session(String tenantId, String userId) {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setTenantId(tenantId);
        session.setUserId(userId);
        return session;
    }
}
