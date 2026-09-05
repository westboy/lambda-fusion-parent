package com.lambda.fusion.ai.schedule.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.fusion.ai.AiConstants.ScheduleMode;
import com.lambda.fusion.ai.AiConstants.SubAgentCategory;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import com.lambda.fusion.ai.llm.service.LlmModelService;
import com.lambda.fusion.ai.schedule.AgentTaskScheduler;
import com.lambda.fusion.ai.schedule.model.CreateScheduledTask;
import com.lambda.fusion.ai.schedule.model.ScheduledTaskLogPage;
import com.lambda.fusion.ai.schedule.model.ScheduledTaskPage;
import com.lambda.fusion.ai.schedule.model.UpdateScheduledTask;
import com.lambda.fusion.ai.schedule.model.entity.ScheduledTaskLogEntity;
import com.lambda.fusion.ai.schedule.service.ScheduledTaskLogService;
import com.lambda.fusion.ai.schedule.service.ScheduledTaskService;
import com.lambda.fusion.ai.subagent.mapper.SubAgentMapper;
import com.lambda.fusion.ai.subagent.model.entity.SubAgentEntity;
import com.lambda.fusion.core.utils.AuthUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.quartz.Trigger.TriggerState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理存储在 {@code ai_sub_agent} 中的定时任务，并通过 {@link AgentTaskScheduler} 同步运行时调度。
 * {@code schedule_enabled} 表示任务是否应参与调度，实际运行状态以调度器查询结果为准。
 *
 * @author Jin
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class ScheduledTaskServiceImpl implements ScheduledTaskService {

    private final SubAgentMapper subAgentMapper;
    private final LlmModelService llmModelService;
    private final AgentTaskScheduler agentTaskScheduler;
    private final ScheduledTaskLogService scheduledTaskLogService;

    @Override
    public Page<SubAgentEntity> page(ScheduledTaskPage query) {
        return subAgentMapper.selectPage(query.getPage(), query.getLambdaQueryWrapper());
    }

    @Override
    public SubAgentEntity get(String id) {
        return requireTask(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubAgentEntity create(CreateScheduledTask dto) {
        ensureNameUnique(dto.getName(), null);
        validateModel(dto.getModelId());
        validateSchedule(dto.getScheduleMode(), dto.getCronExpression(), dto.getFixedRate());

        SubAgentEntity entity = new SubAgentEntity();
        entity.setId(IdUtil.getSnowflakeNextIdStr());
        entity.setTenantId(AuthUtils.getTenantId());
        entity.setName(dto.getName());
        entity.setCategory(SubAgentCategory.SCHEDULED_TASK.getCode());
        // description 是子代理模型的非空字段；定时任务不参与路由，使用备注或任务名满足存储约束。
        entity.setDescription(StringUtils.defaultIfBlank(dto.getRemark(), dto.getName()));
        entity.setPrompt(dto.getPrompt());
        entity.setModelId(dto.getModelId());
        entity.setScheduleMode(normalizeMode(dto.getScheduleMode()));
        entity.setCronExpression(dto.getCronExpression());
        entity.setFixedRate(dto.getFixedRate());
        entity.setInitialDelay(dto.getInitialDelay());
        entity.setZoneId(dto.getZoneId());
        entity.setInputMsg(dto.getInputMsg());
        entity.setToolsAllow(dto.getToolsAllow());
        entity.setTemperature(dto.getTemperature());
        entity.setTopP(dto.getTopP());
        entity.setSteps(dto.getSteps());
        entity.setRemark(dto.getRemark());
        // 定时任务不参与主 Agent 路由，路由启用状态与调度启用状态相互独立。
        entity.setEnabled(Boolean.FALSE);
        entity.setScheduleEnabled(Boolean.TRUE.equals(dto.getScheduleEnabled()));
        // 记录创建人：定时任务完成后向创建人发送站内信通知。
        entity.setCreatedBy(AuthUtils.getUsername());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        subAgentMapper.insert(entity);

        if (Boolean.TRUE.equals(entity.getScheduleEnabled())) {
            agentTaskScheduler.scheduleTask(entity);
        }
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(String id, UpdateScheduledTask dto) {
        SubAgentEntity entity = requireTask(id);
        if (StringUtils.isNotBlank(dto.getName()) && !dto.getName().equals(entity.getName())) {
            ensureNameUnique(dto.getName(), id);
            entity.setName(dto.getName());
        }
        if (dto.getPrompt() != null) {
            entity.setPrompt(dto.getPrompt());
        }
        if (StringUtils.isNotBlank(dto.getModelId()) && !dto.getModelId().equals(entity.getModelId())) {
            validateModel(dto.getModelId());
            entity.setModelId(dto.getModelId());
        }
        if (StringUtils.isNotBlank(dto.getScheduleMode())) {
            entity.setScheduleMode(normalizeMode(dto.getScheduleMode()));
        }
        if (dto.getCronExpression() != null) {
            entity.setCronExpression(dto.getCronExpression());
        }
        if (dto.getFixedRate() != null) {
            entity.setFixedRate(dto.getFixedRate());
        }
        if (dto.getInitialDelay() != null) {
            entity.setInitialDelay(dto.getInitialDelay());
        }
        if (dto.getZoneId() != null) {
            entity.setZoneId(dto.getZoneId());
        }
        if (dto.getInputMsg() != null) {
            entity.setInputMsg(dto.getInputMsg());
        }
        if (dto.getToolsAllow() != null) {
            entity.setToolsAllow(dto.getToolsAllow());
        }
        if (dto.getTemperature() != null) {
            entity.setTemperature(dto.getTemperature());
        }
        if (dto.getTopP() != null) {
            entity.setTopP(dto.getTopP());
        }
        if (dto.getSteps() != null) {
            entity.setSteps(dto.getSteps());
        }
        if (dto.getRemark() != null) {
            entity.setRemark(dto.getRemark());
        }
        if (dto.getScheduleEnabled() != null) {
            entity.setScheduleEnabled(dto.getScheduleEnabled());
        }
        validateSchedule(entity.getScheduleMode(), entity.getCronExpression(), entity.getFixedRate());
        entity.setUpdatedAt(LocalDateTime.now());
        subAgentMapper.updateById(entity);

        reschedule(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        SubAgentEntity entity = requireTask(id);
        subAgentMapper.deleteById(id);
        agentTaskScheduler.cancel(entity.getId(), entity.getName());
    }

    /**
     * 停用任务并同步暂停运行时调度。调度器中未注册该任务时仍保存停用状态，仅记录告警。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pause(String id) {
        SubAgentEntity entity = requireTask(id);
        boolean paused = agentTaskScheduler.pause(entity.getId(), entity.getName());
        if (!paused) {
            log.warn("定时任务暂停时调度器未找到活动任务(可能未注册/已重启): id={}, name={}", id, entity.getName());
        }
        entity.setScheduleEnabled(Boolean.FALSE);
        entity.setUpdatedAt(LocalDateTime.now());
        subAgentMapper.updateById(entity);
    }

    /** 启用任务并按当前配置重新提交，确保应用重启后缺失的运行时调度也能恢复。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resume(String id) {
        SubAgentEntity entity = requireTask(id);
        entity.setScheduleEnabled(Boolean.TRUE);
        entity.setUpdatedAt(LocalDateTime.now());
        subAgentMapper.updateById(entity);
        agentTaskScheduler.scheduleTask(entity);
    }

    /** 同步执行一次任务，使配置、模型或执行异常能够直接反馈给调用方。 */
    @Override
    public void trigger(String id) {
        SubAgentEntity entity = requireTask(id);
        agentTaskScheduler.runOnce(entity);
    }

    @Override
    public TriggerState status(String id) {
        SubAgentEntity entity = requireTask(id);
        return agentTaskScheduler.status(entity.getId(), entity.getName());
    }

    @Override
    public Page<ScheduledTaskLogEntity> pageLogs(String id, ScheduledTaskLogPage query) {
        requireTask(id);
        query.setTaskId(id);
        return scheduledTaskLogService.page(query);
    }

    /** 根据任务启用状态提交或取消运行时调度。 */
    private void reschedule(SubAgentEntity entity) {
        if (Boolean.TRUE.equals(entity.getScheduleEnabled())) {
            agentTaskScheduler.scheduleTask(entity);
        } else {
            agentTaskScheduler.cancel(entity.getId(), entity.getName());
        }
    }

    /** 按主键加载定时任务；记录不存在或分类不匹配时抛出业务异常。 */
    private SubAgentEntity requireTask(String id) {
        SubAgentEntity entity = subAgentMapper.selectOne(new LambdaQueryWrapper<SubAgentEntity>()
                .eq(SubAgentEntity::getId, id)
                .eq(SubAgentEntity::getCategory, SubAgentCategory.SCHEDULED_TASK.getCode()));
        if (entity == null) {
            throw new AiBusinessException(AiErrorCode.SCHEDULED_TASK_NOT_FOUND, id);
        }
        return entity;
    }

    private void ensureNameUnique(String name, String excludeId) {
        boolean exists = subAgentMapper.exists(new LambdaQueryWrapper<SubAgentEntity>()
                .eq(SubAgentEntity::getTenantId, AuthUtils.getTenantId())
                .eq(SubAgentEntity::getCategory, SubAgentCategory.SCHEDULED_TASK.getCode())
                .eq(SubAgentEntity::getName, name)
                .ne(excludeId != null, SubAgentEntity::getId, excludeId));
        if (exists) {
            throw new AiBusinessException(AiErrorCode.SCHEDULED_TASK_NAME_EXISTS, name);
        }
    }

    private void validateModel(String modelId) {
        LlmModelEntity model = llmModelService.loadById(modelId);
        if (Boolean.FALSE.equals(model.getEnabled())) {
            throw new AiBusinessException(AiErrorCode.SUB_AGENT_MODEL_INVALID, modelId);
        }
    }

    private void validateSchedule(String mode, String cron, Long fixedRate) {
        ScheduleMode m = ScheduleMode.of(mode);
        if (m == ScheduleMode.CRON && StringUtils.isBlank(cron)) {
            throw new AiBusinessException(AiErrorCode.SCHEDULED_TASK_CONFIG_INVALID, "CRON 模式必须提供 cron 表达式");
        }
        if (m == ScheduleMode.FIXED_RATE && (fixedRate == null || fixedRate <= 0)) {
            throw new AiBusinessException(AiErrorCode.SCHEDULED_TASK_CONFIG_INVALID, "FIXED_RATE 模式必须提供正的固定频率");
        }
    }

    private String normalizeMode(String mode) {
        ScheduleMode m = ScheduleMode.of(mode);
        if (m == null) {
            throw new AiBusinessException(AiErrorCode.SCHEDULED_TASK_CONFIG_INVALID, "非法调度模式: " + mode);
        }
        return m.name();
    }
}
