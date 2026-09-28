package com.lambda.fusion.ai.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lambda.cloud.mybatis.tenant.TenantContextHolder;
import com.lambda.fusion.ai.AiConstants.TaskTriggerType;
import com.lambda.fusion.ai.schedule.service.ScheduledTaskLogService;
import com.lambda.fusion.ai.subagent.mapper.SubAgentMapper;
import com.lambda.fusion.ai.subagent.model.entity.SubAgentEntity;
import java.util.Date;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;

/**
 * 验证 {@link AgentExecutionJobListener}：执行前按任务主键恢复租户上下文，
 * 执行后按主键回源实体通知并清理上下文。
 *
 * @author Jin
 */
class AgentExecutionJobListenerTest {

    private final ScheduledTaskLogService logService = mock(ScheduledTaskLogService.class);
    private final SubAgentMapper subAgentMapper = mock(SubAgentMapper.class);
    private final ScheduledTaskNotifier taskNotifier = mock(ScheduledTaskNotifier.class);
    private final AgentExecutionJobListener listener =
            new AgentExecutionJobListener(logService, subAgentMapper, taskNotifier);

    @AfterEach
    void clearTenant() {
        TenantContextHolder.getInstance().close();
    }

    private static JobExecutionContext context(String taskName) {
        JobExecutionContext context = mock(JobExecutionContext.class);
        JobDetail jobDetail = mock(JobDetail.class);
        JobDataMap dataMap = new JobDataMap();
        if (taskName != null) {
            dataMap.put("taskName", taskName);
        }
        when(context.getJobDetail()).thenReturn(jobDetail);
        when(jobDetail.getJobDataMap()).thenReturn(dataMap);
        when(jobDetail.getKey()).thenReturn(JobKey.jobKey("k"));
        when(context.getFireTime()).thenReturn(new Date());
        return context;
    }

    private static SubAgentEntity entity(String id, String tenantId) {
        SubAgentEntity entity = new SubAgentEntity();
        entity.setId(id);
        entity.setTenantId(tenantId);
        return entity;
    }

    @Test
    void restoresTenantContextBeforeExecution() {
        when(subAgentMapper.selectById("task-1")).thenReturn(entity("task-1", "tenant-a"));

        listener.jobToBeExecuted(context("task-1:每日汇总"));

        assertThat(TenantContextHolder.getCurrentTenantId()).isEqualTo("tenant-a");
    }

    @Test
    void skipsTenantRestoreWhenTaskMissingOrTenantBlank() {
        when(subAgentMapper.selectById("task-1")).thenReturn(null);
        when(subAgentMapper.selectById("task-2")).thenReturn(entity("task-2", " "));

        listener.jobToBeExecuted(context("task-1:每日汇总"));
        listener.jobToBeExecuted(context("task-2:每日汇总"));

        assertThat(TenantContextHolder.getCurrentTenantId()).isNull();
    }

    @Test
    void ignoresJobsWithoutTaskName() {
        listener.jobToBeExecuted(context(null));
        listener.jobToBeExecuted(context("no-separator"));

        verifyNoInteractions(subAgentMapper);
        assertThat(TenantContextHolder.getCurrentTenantId()).isNull();
    }

    @Test
    void notifiesWithEntityResolvedByIdAndClearsContext() {
        SubAgentEntity task = entity("task-1", "tenant-a");
        when(subAgentMapper.selectById("task-1")).thenReturn(task);
        TenantContextHolder.getInstance().setTenantId("tenant-a");

        listener.jobWasExecuted(context("task-1:每日汇总"), null);

        verify(logService)
                .record(
                        eq("task-1"),
                        eq("每日汇总"),
                        eq(TaskTriggerType.SCHEDULED.name()),
                        eq(true),
                        any(),
                        any(),
                        any(),
                        any());
        verify(taskNotifier)
                .notifyResult(eq(task), eq(true), any(), any(), any(), any(), eq(TaskTriggerType.SCHEDULED));
        assertThat(TenantContextHolder.getCurrentTenantId()).isNull();
    }

    @Test
    void skipsNotificationWhenTaskUnresolvedButStillRecords() {
        when(subAgentMapper.selectById("task-1")).thenReturn(null);

        listener.jobWasExecuted(context("task-1:每日汇总"), new JobExecutionException("boom"));

        verify(logService)
                .record(
                        eq("task-1"),
                        eq("每日汇总"),
                        eq(TaskTriggerType.SCHEDULED.name()),
                        eq(false),
                        any(),
                        eq("boom"),
                        any(),
                        any());
        verify(taskNotifier, never()).notifyResult(any(), eq(false), any(), any(), any(), any(), any());
    }
}
