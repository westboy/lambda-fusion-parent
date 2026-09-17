package com.lambda.fusion.ai.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.ChatRunFailureCode;
import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.model.ChatRunFinalizationResult;
import com.lambda.fusion.ai.chat.model.ConfirmToolCall;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEventStore;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;
import com.lambda.fusion.ai.chat.service.ChatRunStateService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.runtime.workspace.WorkspaceAuditRecorder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 执行实例并发语义测试：终态 DB 提交与检查点写入已移出实例锁（实例 monitor 内不做数据库 I/O），
 * 锁外提交窗口内 confirm/requestStop 被 finalizeCommitInProgress 拦截为状态冲突。
 */
class ChatExecutionInstanceTest {

    private ChatRunStateService runService;
    private ScheduledExecutorService scheduler;
    private ChatExecutionInstance instance;
    private ChatRunEntity run;

    @BeforeEach
    void setUp() {
        runService = mock(ChatRunStateService.class);
        when(runService.loadCurrentOrIdentity(any())).thenAnswer(invocation -> invocation.getArgument(0));
        scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.isShutdown()).thenReturn(false);
        when(scheduler.schedule(any(Runnable.class), anyLong(), any()))
                .thenAnswer(invocation -> mock(ScheduledFuture.class));
        run = new ChatRunEntity();
        run.setId("run-1");
        run.setSessionId("session-1");
        run.setAguiRunId("agui-1");
        run.setPhaseNo(1);
        run.setStatus(ChatRunStatus.RUNNING.name());
        instance = new ChatExecutionInstance(
                runService,
                mock(ChatRunEventStore.class),
                new AiProperties(),
                scheduler,
                mock(WorkspaceAuditRecorder.class),
                run,
                session(),
                null,
                new ChatExecutionSnapshotBuilder(ChatRunSnapshot.empty("run-1", "agui-1", 1)));
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            // 实例不拥有传入的调度器；测试内手动关闭避免线程泄漏。
            scheduler.shutdownNow();
        }
    }

    @Test
    void shouldCommitTerminalOutsideInstanceMonitor() {
        // 记录提交期间调用线程是否仍持有实例 monitor（回归为锁内提交则为 true，用例失败）
        AtomicBoolean monitorHeldDuringCommit = new AtomicBoolean(true);
        when(runService.finalizeExecution(any(), any())).thenAnswer(invocation -> {
            monitorHeldDuringCommit.set(Thread.holdsLock(instance));
            return committed(ChatRunStatus.FAILED.name());
        });

        instance.finalizeFailed(ChatRunFailureCode.ERROR, "boom");

        assertThat(monitorHeldDuringCommit).isFalse();
        assertThat(run.getStatus()).isEqualTo(ChatRunStatus.FAILED.name());
    }

    @Test
    void shouldRejectConfirmAndStopDuringFinalizeCommitWindow() throws Exception {
        CountDownLatch commitEntered = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        when(runService.finalizeExecution(any(), any())).thenAnswer(invocation -> {
            commitEntered.countDown();
            if (!releaseCommit.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("commit latch timeout");
            }
            return committed(ChatRunStatus.COMPLETED.name());
        });

        Thread finalizer = new Thread(() -> instance.runInTenant(instance::finalizeCompleted));
        finalizer.start();
        if (!commitEntered.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("finalize did not enter commit");
        }

        // 锁外提交窗口：confirm 与 requestStop 均被 finalizeCommitInProgress 拦截，不再发起第二笔提交
        ConfirmToolCall command = new ConfirmToolCall();
        command.setPhaseNo(1);
        assertThatThrownBy(() -> instance.confirm(command)).isInstanceOf(AiBusinessException.class);
        assertThat(instance.requestStop()).isFalse();

        releaseCommit.countDown();
        finalizer.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(run.getStatus()).isEqualTo(ChatRunStatus.COMPLETED.name());
        verify(runService, times(1)).finalizeExecution(any(), any());
    }

    @Test
    void shouldRetryFinalizeWithLimitThenDegradeToMinimalFailure() {
        // 前三次完整投影提交失败（每次重试经调度器内联执行），第 4 次为最小失败终结并成功收敛
        AtomicInteger attempts = new AtomicInteger();
        when(runService.finalizeExecution(any(), any())).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() <= 3) {
                throw new RuntimeException("db transient failure");
            }
            return committed(ChatRunStatus.FAILED.name());
        });
        when(scheduler.schedule(any(Runnable.class), anyLong(), any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return mock(ScheduledFuture.class);
        });

        instance.finalizeFailed(ChatRunFailureCode.ERROR, "boom");

        assertThat(attempts.get()).isEqualTo(4);
        assertThat(run.getStatus()).isEqualTo(ChatRunStatus.FAILED.name());
        verify(scheduler, times(2)).schedule(any(Runnable.class), anyLong(), any());
    }

    @Test
    void shouldDegradeImmediatelyOnPermanentFinalizeFailure() {
        // cause 链含 IllegalStateException 属确定性错误：不重试，直接最小失败终结
        AtomicInteger attempts = new AtomicInteger();
        when(runService.finalizeExecution(any(), any())).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RuntimeException("projection conflict", new IllegalStateException("conflict"));
            }
            return committed(ChatRunStatus.FAILED.name());
        });

        instance.finalizeFailed(ChatRunFailureCode.ERROR, "boom");

        assertThat(attempts.get()).isEqualTo(2);
        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(), any());
    }

    @Test
    void shouldCheckpointOutsideInstanceMonitor() {
        AtomicBoolean monitorHeldDuringCheckpoint = new AtomicBoolean(true);
        when(runService.checkpoint(eq(run), any(), anyLong())).thenAnswer(invocation -> {
            monitorHeldDuringCheckpoint.set(Thread.holdsLock(instance));
            return true;
        });

        instance.checkpointNow();

        assertThat(monitorHeldDuringCheckpoint).isFalse();
        verify(runService).checkpoint(eq(run), any(), eq(0L));
    }

    @Test
    void shouldAdoptExternalTerminalWhenCheckpointRejected() {
        when(runService.checkpoint(eq(run), any(), anyLong())).thenReturn(false);
        ChatRunEntity persisted = new ChatRunEntity();
        persisted.setId("run-1");
        persisted.setStatus(ChatRunStatus.STOPPED.name());
        when(runService.loadCurrentOrIdentity(run)).thenReturn(persisted);

        instance.checkpointNow();

        assertThat(run.getStatus()).isEqualTo(ChatRunStatus.STOPPED.name());
    }

    @Test
    void shouldRefreshVersionAndRetryWhenCheckpointCollides() {
        // 版本碰撞：第一次写入被拒（非终态），实例刷新版本后按新版本重写成功。
        ChatRunEntity persisted = new ChatRunEntity();
        persisted.setId("run-1");
        persisted.setStatus(ChatRunStatus.RUNNING.name());
        persisted.setSnapshotVersion(5L);
        when(runService.checkpoint(eq(run), any(), eq(0L))).thenReturn(false);
        when(runService.checkpoint(eq(run), any(), eq(5L))).thenReturn(true);
        when(runService.loadCurrentOrIdentity(run)).thenReturn(persisted);

        instance.checkpointNow();
        instance.checkpointNow();

        assertThat(run.getStatus()).isEqualTo(ChatRunStatus.RUNNING.name());
        verify(runService).checkpoint(eq(run), any(), eq(0L));
        verify(runService).checkpoint(eq(run), any(), eq(5L));
    }

    @Test
    void shouldRequestStopWithoutPreStopCheckpointWhenNoActiveSource() {
        when(runService.finalizeExecution(any(), any())).thenReturn(committed(ChatRunStatus.STOPPED.name()));

        boolean interruptRequired = instance.requestStop();

        assertThat(interruptRequired).isFalse();
        verify(runService, never()).checkpoint(any(), any(), anyLong());
        assertThat(run.getStatus()).isEqualTo(ChatRunStatus.STOPPED.name());
        assertThat(run.getFinishReason()).isNull();
    }

    private static ChatRunFinalizationResult committed(String status) {
        return new ChatRunFinalizationResult(true, status, null, null, null);
    }

    private static ChatSessionEntity session() {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setTenantId("tenant-1");
        session.setUserId("zhangsan");
        return session;
    }
}
