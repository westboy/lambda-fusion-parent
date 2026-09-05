package com.lambda.fusion.ai.schedule;

import com.lambda.cloud.mybatis.tenant.TenantContextHolder;
import com.lambda.fusion.ai.AiConstants.ScheduleMode;
import com.lambda.fusion.ai.AiConstants.TaskTriggerType;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.runtime.ModelResolver;
import com.lambda.fusion.ai.runtime.ToolkitAssembler;
import com.lambda.fusion.ai.schedule.service.ScheduledTaskLogService;
import com.lambda.fusion.ai.subagent.model.entity.SubAgentEntity;
import com.lambda.fusion.core.utils.AuthUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.scheduler.BaseScheduleAgentTask;
import io.agentscope.extensions.scheduler.ScheduleAgentTask;
import io.agentscope.extensions.scheduler.config.ModelConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import io.agentscope.extensions.scheduler.quartz.QuartzAgentScheduler;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.quartz.Trigger.TriggerState;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 将 {@code ai_sub_agent} 中的定时任务定义转换为 AgentScope 的 {@link RuntimeAgentConfig} 和
 * {@link ScheduleConfig}，再委托 {@link QuartzAgentScheduler} 管理调度生命周期。本类只负责配置转换与
 * 生命周期转发，不维护另一份调度状态。
 *
 * <p><b>租户信息通过两个通道传递：</b>
 * <ul>
 *   <li>数据库通道由 {@link TenantAwareTask} 在订阅时恢复、结束时清理
 *   {@code TenantContextHolder}。</li>
 *   <li>Agent 上下文通道由 {@link #buildSysPrompt} 注入 tenantId，供 LLM 和远程工具识别归属租户；
 *   ThreadLocal 无法跨越远程调用边界。</li>
 * </ul>
 *
 * @author Jin
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class AgentTaskScheduler {

    private final QuartzAgentScheduler agentScheduler;
    private final ModelResolver modelResolver;
    private final ToolkitAssembler toolkitAssembler;
    private final ScheduledTaskLogService scheduledTaskLogService;
    private final ScheduledTaskNotifier taskNotifier;

    /** 生成调度器内的唯一名称，以任务主键隔离同名任务（主键全局唯一，不受同名/跨租户影响）。 */
    public static String scheduleName(String id, String name) {
        return id + ":" + name;
    }

    /** 提交或重排任务，并返回可在执行时恢复租户上下文的任务装饰器。 */
    public ScheduleAgentTask<Msg> scheduleTask(SubAgentEntity entity) {
        validate(entity);
        String name = scheduleName(entity.getId(), entity.getName());
        // 更新任务时先取消同名旧调度，确保调度器只保留当前配置。
        agentScheduler.cancel(name);

        RuntimeAgentConfig agentConfig = RuntimeAgentConfig.builder()
                .name(name)
                .modelConfig(new EntityModelConfig(entity))
                .sysPrompt(buildSysPrompt(entity))
                .toolkit(buildToolkit(entity))
                .build();
        ScheduleConfig scheduleConfig = buildScheduleConfig(entity);
        Msg input = buildInput(entity);
        ScheduleAgentTask<Msg> task = agentScheduler.schedule(agentConfig, scheduleConfig, input);
        log.info("定时任务已提交调度: name={}, mode={}", name, entity.getScheduleMode());
        return new TenantAwareTask(task, entity.getTenantId());
    }

    public boolean cancel(String id, String name) {
        return agentScheduler.cancel(scheduleName(id, name));
    }

    public boolean pause(String id, String name) {
        return agentScheduler.pause(scheduleName(id, name));
    }

    public boolean resume(String id, String name) {
        return agentScheduler.resume(scheduleName(id, name));
    }

    public TriggerState status(String id, String name) {
        return agentScheduler.getStatus(scheduleName(id, name));
    }

    /**
     * 立即异步触发一次，不改变既有调度；任务尚未注册时先按当前配置临时注册。
     *
     * @deprecated 临时注册无法触发 {@code NONE} 模式，异步执行异常也无法反馈调用方；请改用
     *     {@link #runOnce(SubAgentEntity)}。
     */
    @Deprecated
    public void triggerNow(SubAgentEntity entity) {
        String name = scheduleName(entity.getId(), entity.getName());
        ScheduleAgentTask<Msg> task = agentScheduler.getScheduledAgent(name);
        if (task == null) {
            task = scheduleTask(entity);
        }
        ScheduleAgentTask<Msg> tenantTask = new TenantAwareTask(task, entity.getTenantId());
        Msg input = buildInput(entity);
        if (input != null) {
            tenantTask.run(input).subscribe();
        } else {
            tenantTask.run().subscribe();
        }
        log.info("定时任务已手动触发: name={}", name);
    }

    /**
     * 按当前实体配置同步执行一次，不注册 Quartz 调度，也不写入调度器的任务缓存。
     * 方法等待执行完成，并在调用线程抛出配置、模型或执行异常；{@code NONE} 模式同样适用。
     */
    public void runOnce(SubAgentEntity entity) {
        validate(entity);
        RuntimeAgentConfig agentConfig = RuntimeAgentConfig.builder()
                .name(scheduleName(entity.getId(), entity.getName()))
                .modelConfig(new EntityModelConfig(entity))
                .sysPrompt(buildSysPrompt(entity))
                .toolkit(buildToolkit(entity))
                .build();
        // 空调度配置只用于本次执行，不会创建持久触发器。
        BaseScheduleAgentTask task =
                new BaseScheduleAgentTask(agentConfig, ScheduleConfig.builder().build(), agentScheduler);
        ScheduleAgentTask<Msg> tenantTask = new TenantAwareTask(task, entity.getTenantId());
        Msg input = buildInput(entity);
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            Msg result = (input != null)
                    ? tenantTask.run(input).block()
                    : tenantTask.run().block();
            LocalDateTime finishedAt = LocalDateTime.now();
            log.info("定时任务已手动触发执行完成: name={}", entity.getName());
            recordExecution(entity, true, result == null ? null : result.getTextContent(), null, startedAt, finishedAt);
        } catch (Exception e) {
            LocalDateTime finishedAt = LocalDateTime.now();
            log.error("定时任务手动触发执行失败: name={}", entity.getName(), e);
            recordExecution(entity, false, null, e.getMessage(), startedAt, finishedAt);
            if (e instanceof AiBusinessException be) {
                throw be;
            }
            throw new AiBusinessException(AiErrorCode.SCHEDULED_TASK_CONFIG_INVALID, "手动触发执行失败: " + e.getMessage());
        }
    }

    /** 记录一次手动执行（MANUAL）；落库失败不影响执行结果。随后向任务创建人发送站内信通知。 */
    private void recordExecution(
            SubAgentEntity entity,
            boolean success,
            String output,
            String errorMessage,
            LocalDateTime startedAt,
            LocalDateTime finishedAt) {
        scheduledTaskLogService.record(
                entity.getId(),
                entity.getName(),
                TaskTriggerType.MANUAL.name(),
                success,
                output,
                errorMessage,
                startedAt,
                finishedAt);
        // 手动触发在控制器线程执行，当前登录人即触发者；回填为创建人以保证必有接收人。
        if (StringUtils.isBlank(entity.getCreatedBy())) {
            entity.setCreatedBy(AuthUtils.getUsername());
        }
        taskNotifier.notifyResult(entity, success, output, errorMessage, startedAt, finishedAt, TaskTriggerType.MANUAL);
    }

    private void validate(SubAgentEntity entity) {
        if (StringUtils.isBlank(entity.getModelId())) {
            throw new AiBusinessException(AiErrorCode.SCHEDULED_TASK_CONFIG_INVALID, "定时任务必须绑定模型");
        }
        ScheduleMode mode = ScheduleMode.of(entity.getScheduleMode());
        if (mode == ScheduleMode.CRON && StringUtils.isBlank(entity.getCronExpression())) {
            throw new AiBusinessException(AiErrorCode.SCHEDULED_TASK_CONFIG_INVALID, "CRON 模式必须提供 cron 表达式");
        }
        if (mode == ScheduleMode.FIXED_RATE && (entity.getFixedRate() == null || entity.getFixedRate() <= 0)) {
            throw new AiBusinessException(AiErrorCode.SCHEDULED_TASK_CONFIG_INVALID, "FIXED_RATE 模式必须提供正的固定频率");
        }
    }

    private ScheduleConfig buildScheduleConfig(SubAgentEntity entity) {
        ScheduleConfig.Builder builder = ScheduleConfig.builder();
        ScheduleMode mode = ScheduleMode.of(entity.getScheduleMode());
        if (mode == ScheduleMode.CRON) {
            builder.cron(entity.getCronExpression());
            if (StringUtils.isNotBlank(entity.getZoneId())) {
                builder.zoneId(entity.getZoneId());
            }
        } else if (mode == ScheduleMode.FIXED_RATE) {
            builder.fixedRate(entity.getFixedRate());
        }
        // NONE 不设置触发条件，仅允许手动执行。
        if (entity.getInitialDelay() != null) {
            builder.initialDelay(entity.getInitialDelay());
        }
        return builder.build();
    }

    /** 在系统提示词中注入租户标识，供 LLM 和远程工具识别任务归属。 */
    private String buildSysPrompt(SubAgentEntity entity) {
        return StringUtils.defaultString(entity.getPrompt()) + "\n\n[运行上下文] 归属租户 tenantId=" + entity.getTenantId();
    }

    /** 构建可选的初始输入；未配置输入内容时返回 {@code null}。 */
    private Msg buildInput(SubAgentEntity entity) {
        if (StringUtils.isBlank(entity.getInputMsg())) {
            return null;
        }
        return Msg.builder()
                .role(MsgRole.USER)
                .textContent(entity.getInputMsg())
                .build();
    }

    /** 注册本地工具，并按 {@code tools_allow} 白名单过滤；空白名单表示全部允许。 */
    private Toolkit buildToolkit(SubAgentEntity entity) {
        Toolkit toolkit = new Toolkit();
        List<String> allow = entity.getToolsAllow();
        boolean filter = allow != null && !allow.isEmpty();
        for (Object toolBean : toolkitAssembler.getLocalToolBeans()) {
            if (!filter || hasAllowedTool(toolBean, allow)) {
                toolkit.registerTool(toolBean);
            }
        }
        return toolkit;
    }

    /** 判断工具 Bean 是否声明了白名单中的任一 {@link Tool} 方法。未命名的工具使用方法名。 */
    private boolean hasAllowedTool(Object toolBean, List<String> allow) {
        for (Method method : toolBean.getClass().getMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool == null) {
                continue;
            }
            String toolName = tool.name().isEmpty() ? method.getName() : tool.name();
            if (allow.contains(toolName)) {
                return true;
            }
        }
        return false;
    }

    /** 将实体中的模型 ID 适配为 AgentScope 配置，并通过 {@link ModelResolver} 延迟创建模型。 */
    private final class EntityModelConfig implements ModelConfig {
        private final SubAgentEntity entity;

        private EntityModelConfig(SubAgentEntity entity) {
            this.entity = entity;
        }

        @Override
        public String getModelName() {
            return entity.getModelId();
        }

        @Override
        public Model createModel() {
            return modelResolver.apply(entity.getModelId());
        }
    }

    /**
     * 租户感知任务装饰器：订阅时恢复 {@code TenantContextHolder}，执行完成、失败或取消后统一清理。
     * 除租户上下文外不维护独立状态，其余行为均委托给底层任务。
     *
     * <p>{@code run()} 只负责组装 Mono，实际执行发生在调用方订阅时，且可能位于 Quartz 工作线程。
     * 因此租户上下文必须在订阅阶段恢复，不能依赖组装阶段所在的 ThreadLocal。
     */
    private static final class TenantAwareTask implements ScheduleAgentTask<Msg> {
        private final ScheduleAgentTask<Msg> delegate;
        private final String tenantId;

        private TenantAwareTask(ScheduleAgentTask<Msg> delegate, String tenantId) {
            this.delegate = delegate;
            this.tenantId = tenantId;
        }

        @Override
        public String getId() {
            return delegate.getId();
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public Mono<Msg> run(Msg... msgs) {
            return Mono.defer(() -> {
                        if (StringUtils.isNotBlank(tenantId)) {
                            TenantContextHolder.getInstance().setTenantId(tenantId);
                        }
                        return delegate.run(msgs);
                    })
                    .doFinally(signal -> TenantContextHolder.getInstance().close());
        }

        @Override
        public void cancel() {
            delegate.cancel();
        }
    }
}
