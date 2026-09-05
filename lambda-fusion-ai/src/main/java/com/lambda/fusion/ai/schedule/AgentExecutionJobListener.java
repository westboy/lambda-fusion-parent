package com.lambda.fusion.ai.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.cloud.mybatis.tenant.TenantContextHolder;
import com.lambda.fusion.ai.AiConstants.SubAgentCategory;
import com.lambda.fusion.ai.AiConstants.TaskTriggerType;
import com.lambda.fusion.ai.schedule.service.ScheduledTaskLogService;
import com.lambda.fusion.ai.subagent.mapper.SubAgentMapper;
import com.lambda.fusion.ai.subagent.model.entity.SubAgentEntity;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 监听共享 Quartz {@code Scheduler} 的定时执行，补齐任务执行期间的租户上下文并记录执行结果。
 * Quartz 定时触发不经过 {@code AgentTaskScheduler.TenantAwareTask}，因此需要在监听器中单独恢复上下文。
 *
 * <p>执行前后分别承担以下职责：
 * <ul>
 *   <li>{@link #jobToBeExecuted} 从 JobDataMap 的 {@code taskName}（格式为 {@code tenantId:name}）
 *   解析租户，使 Agent 工具能够访问对应租户的数据。</li>
 *   <li>{@link #jobWasExecuted} 根据执行异常判断结果并写入 SCHEDULED 日志，最后清理租户上下文。
 *   日志表不含 {@code tenant_id}，写日志本身不依赖租户上下文。</li>
 * </ul>
 *
 * <p>Quartz Job 不暴露 Agent 返回的 {@code Msg}，因此定时执行日志暂不记录输出正文。
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
        log.debug("taskId:{}", taskId);
    }

    @Override
    public void jobExecutionVetoed(JobExecutionContext context) {
        // 被调度器否决的任务没有实际执行，因此不记录执行日志。
    }

    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
        try {
            String taskName = parseTaskName(context);
            String taskId = parseTaskId(context);
            if (StringUtils.isBlank(taskName)) {
                return;
            }
            boolean success = jobException == null;
            String errorMessage = success ? null : String.valueOf(jobException.getMessage());
            LocalDateTime finishedAt = LocalDateTime.now();
            // 以 Quartz 的触发时间作为开始时间；耗时由开始和当前完成时间计算。
            LocalDateTime startedAt = context.getFireTime() == null
                    ? finishedAt
                    : LocalDateTime.ofInstant(context.getFireTime().toInstant(), ZoneId.systemDefault());
            SubAgentEntity task = resolveTask(taskId, taskName);

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

    /**
     * 从 JobDataMap 的 {@code taskName}（格式为 {@code tenantId:name}）解析租户。
     */
    private String parseTaskId(JobExecutionContext context) {
        String taskName = context.getJobDetail().getJobDataMap().getString("taskName");
        if (StringUtils.isBlank(taskName)) {
            return null;
        }
        int idx = taskName.indexOf(':');
        return idx > 0 ? taskName.substring(0, idx) : null;
    }

    /**
     * 从 JobDataMap 的 {@code taskName}（格式为 {@code tenantId:name}）解析业务任务名。
     */
    private String parseTaskName(JobExecutionContext context) {
        String taskName = context.getJobDetail().getJobDataMap().getString("taskName");
        if (StringUtils.isBlank(taskName)) {
            return null;
        }
        int idx = taskName.indexOf(':');
        return idx > 0 ? taskName.substring(idx + 1) : taskName;
    }

    /**
     * 按租户和任务名反查定时任务实体；无法解析时返回 null（日志仍可用任务名归因，通知则跳过）。
     */
    private SubAgentEntity resolveTask(String id, String taskName) {
        try {
            return subAgentMapper.selectOne(new LambdaQueryWrapper<SubAgentEntity>()
                    .eq(SubAgentEntity::getCategory, SubAgentCategory.SCHEDULED_TASK.getCode())
                    .eq(SubAgentEntity::getName, taskName));
        } catch (Exception e) {
            log.warn("定时任务实体解析失败: taskId={}, name={}", id , taskName);
            return null;
        }
    }
}
