package com.lambda.fusion.ai.schedule.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.fusion.ai.AiConstants.TaskExecStatus;
import com.lambda.fusion.ai.schedule.mapper.ScheduledTaskLogMapper;
import com.lambda.fusion.ai.schedule.model.ScheduledTaskLogPage;
import com.lambda.fusion.ai.schedule.model.entity.ScheduledTaskLogEntity;
import com.lambda.fusion.ai.schedule.service.ScheduledTaskLogService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * 定时任务执行记录服务实现：双路径埋点写入（调度/手动）。
 *
 * <p>日志为运维观测数据，不做租户隔离（表无 tenant_id 列，跨租户可读），故写入不依赖
 * 租户上下文，Quartz worker / 手动触发线程直接落库，无需恢复租户。
 *
 * @author Jin
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class ScheduledTaskLogServiceImpl implements ScheduledTaskLogService {

    private final ScheduledTaskLogMapper scheduledTaskLogMapper;

    @Override
    public void record(
            String taskId,
            String taskName,
            String triggerType,
            boolean success,
            String output,
            String errorMessage,
            LocalDateTime startedAt,
            LocalDateTime finishedAt) {
        try {
            ScheduledTaskLogEntity entity = new ScheduledTaskLogEntity();
            entity.setId(IdUtil.getSnowflakeNextIdStr());
            entity.setTaskId(taskId);
            entity.setTaskName(taskName);
            entity.setTriggerType(triggerType);
            entity.setStatus(success ? TaskExecStatus.SUCCESS.name() : TaskExecStatus.FAILED.name());
            entity.setResultOutput(output);
            entity.setErrorMessage(StringUtils.left(errorMessage, 1024));
            entity.setDurationMs(durationMillis(startedAt, finishedAt));
            entity.setStartedAt(startedAt);
            entity.setFinishedAt(finishedAt);
            scheduledTaskLogMapper.insert(entity);
        } catch (Exception e) {
            // 记录失败不反向影响任务执行结果，仅告警
            log.error("定时任务执行记录落库失败: taskId={}, triggerType={}", taskId, triggerType, e);
        }
    }

    @Override
    public Page<ScheduledTaskLogEntity> page(ScheduledTaskLogPage query) {
        return scheduledTaskLogMapper.selectPage(query.getPage(), query.getLambdaQueryWrapper());
    }

    private static Long durationMillis(LocalDateTime startedAt, LocalDateTime finishedAt) {
        if (startedAt == null || finishedAt == null) {
            return null;
        }
        return Duration.between(startedAt, finishedAt).toMillis();
    }
}
