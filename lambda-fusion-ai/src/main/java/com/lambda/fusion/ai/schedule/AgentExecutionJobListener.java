package com.lambda.fusion.ai.schedule;

import com.lambda.cloud.mybatis.tenant.TenantContextHolder;
import com.lambda.fusion.ai.AiConstants.TaskTriggerType;
import com.lambda.fusion.ai.schedule.service.ScheduledTaskLogService;
import com.lambda.fusion.ai.subagent.mapper.SubAgentMapper;
import com.lambda.fusion.ai.subagent.model.entity.SubAgentEntity;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobListener;
import org.springframework.stereotype.Component;

/**
 * 监听共享 Quartz {@code Scheduler} 的定时执行：执行前按 JobDataMap 的 {@code taskName}
 * （格式 {@code 任务主键:任务名}）恢复租户上下文，执行后写执行日志并通知创建人。
 *
 * @author Jin
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class AgentExecutionJobListener implements JobListener {

    private final ScheduledTaskLogService scheduledTaskLogService;
    private final SubAgentMapper subAgentMapper;
    private final ScheduledTaskNotifier taskNotifier;

    @Override
    public String getName() {
        return "agentExecutionJobListener";
    }

    @Override
    public void jobToBeExecuted(JobExecutionContext context) {
        String taskId = parseTaskId(context);
        if (StringUtils.isBlank(taskId)) {
            return;
        }
        try {
            // 无上下文时按主键系统级读取属受控恢复例外；拿到实体后立即恢复其真实租户。
            SubAgentEntity task = subAgentMapper.selectById(taskId);
            if (task != null && StringUtils.isNotBlank(task.getTenantId())) {
                TenantContextHolder.getInstance().setTenantId(task.getTenantId());
            }
        } catch (Exception e) {
            log.warn("定时任务租户上下文恢复失败: taskId={}", taskId, e);
        }
    }

    @Override
    public void jobExecutionVetoed(JobExecutionContext context) {
        // 被调度器否决的任务没有实际执行，因此不记录执行日志。
    }

    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
        try {
            String taskId = parseTaskId(context);
            String taskName = parseTaskName(context);
            if (StringUtils.isBlank(taskId) || StringUtils.isBlank(taskName)) {
                return;
            }
            boolean success = jobException == null;
            String errorMessage = success ? null : String.valueOf(jobException.getMessage());
            LocalDateTime finishedAt = LocalDateTime.now();
            // 以 Quartz 的触发时间作为开始时间；耗时由开始和当前完成时间计算。
            LocalDateTime startedAt = context.getFireTime() == null
                    ? finishedAt
                    : LocalDateTime.ofInstant(context.getFireTime().toInstant(), ZoneId.systemDefault());
            SubAgentEntity task = resolveTask(taskId);

            scheduledTaskLogService.record(
                    taskId,
                    taskName,
                    TaskTriggerType.SCHEDULED.name(),
                    success,
                    null,
                    errorMessage,
                    startedAt,
                    finishedAt);
            // 向任务创建人发送站内信通知；task 为空（任务已删或解析失败）时无法定位接收人，跳过。
            if (task != null) {
                taskNotifier.notifyResult(
                        task, success, null, errorMessage, startedAt, finishedAt, TaskTriggerType.SCHEDULED);
            }
        } catch (Exception e) {
            log.error("定时任务执行记录写入失败: jobKey={}", context.getJobDetail().getKey(), e);
        } finally {
            TenantContextHolder.getInstance().close();
        }
    }

    /** 从 JobDataMap 的 {@code taskName}（格式为 {@code 任务主键:任务名}）解析任务主键。 */
    private String parseTaskId(JobExecutionContext context) {
        String taskName = context.getJobDetail().getJobDataMap().getString("taskName");
        if (StringUtils.isBlank(taskName)) {
            return null;
        }
        int idx = taskName.indexOf(':');
        return idx > 0 ? taskName.substring(0, idx) : null;
    }

    /** 从 JobDataMap 的 {@code taskName} 解析业务任务名。 */
    private String parseTaskName(JobExecutionContext context) {
        String taskName = context.getJobDetail().getJobDataMap().getString("taskName");
        if (StringUtils.isBlank(taskName)) {
            return null;
        }
        int idx = taskName.indexOf(':');
        return idx > 0 ? taskName.substring(idx + 1) : taskName;
    }

    /** 按主键回源任务实体；无法解析时返回 null（日志仍可用任务名归因，通知则跳过）。 */
    private SubAgentEntity resolveTask(String taskId) {
        try {
            return subAgentMapper.selectById(taskId);
        } catch (Exception e) {
            log.warn("定时任务实体解析失败: taskId={}", taskId, e);
            return null;
        }
    }
}
