package com.lambda.fusion.ai.chat.runtime;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** 当前节点 Agent 执行阶段的容量限制器；不保存 Run，也不参与业务状态判断。 */
final class ChatRunCapacityLimiter {

    private final Semaphore globalPermits;
    private final int maxPerUser;
    private final ConcurrentHashMap<UserKey, Integer> userCounts = new ConcurrentHashMap<>();

    ChatRunCapacityLimiter(AiProperties properties) {
        AiProperties.Chat.Run run = properties.getChat().getRun();
        globalPermits = new Semaphore(run.getMaxActiveRuns(), true);
        maxPerUser = run.getMaxActiveRunsPerUser();
    }

    Optional<Permit> tryAcquire(ChatSessionEntity session) {
        if (!globalPermits.tryAcquire()) {
            return Optional.empty();
        }
        UserKey key = new UserKey(session.getTenantId(), session.getUserId());
        AcquireDecision decision = new AcquireDecision();
        userCounts.compute(key, (ignored, current) -> {
            int count = current == null ? 0 : current;
            if (count >= maxPerUser) {
                return current;
            }
            decision.acquired = true;
            return count + 1;
        });
        if (!decision.acquired) {
            globalPermits.release();
            return Optional.empty();
        }
        return Optional.of(new Permit(this, key));
    }

    private void release(UserKey key) {
        userCounts.computeIfPresent(key, (ignored, count) -> count == 1 ? null : count - 1);
        globalPermits.release();
    }

    private static final class AcquireDecision {

        private boolean acquired;
    }

    private record UserKey(String tenantId, String userId) {

        private UserKey {
            tenantId = Objects.toString(tenantId, "");
            userId = Objects.toString(userId, "");
        }
    }

    static final class Permit implements AutoCloseable {

        private final ChatRunCapacityLimiter owner;
        private final UserKey key;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(ChatRunCapacityLimiter owner, UserKey key) {
            this.owner = owner;
            this.key = key;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(key);
            }
        }
    }
}
