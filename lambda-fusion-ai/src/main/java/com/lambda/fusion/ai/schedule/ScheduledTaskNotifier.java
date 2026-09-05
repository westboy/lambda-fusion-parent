package com.lambda.fusion.ai.schedule;

import com.lambda.fusion.ai.AiConstants.TaskTriggerType;
import com.lambda.fusion.ai.subagent.model.entity.SubAgentEntity;
import com.lambda.fusion.message.api.RemoteMessageCommand;
import com.lambda.fusion.message.api.RemoteMessageService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 定时任务完成站内信通知器：任务（定时/手动）执行完成后，经 message-api 远程发送结果给任务创建人。
 *
 * <p>message 模块不在 ai 的直接依赖内，这里通过 Dubbo 引用 {@link RemoteMessageService}；
 * 远程不可用、任务无创建人（历史数据）或发送失败时仅记录日志，不影响任务执行与日志落库。
 *
 * @author zx
 */
@Slf4j
@Component
public class ScheduledTaskNotifier {

    private static final String CATEGORY_SYSTEM = "system";
    private static final String LEVEL_INFO = "info";
    private static final String LEVEL_IMPORTANT = "important";
    private static final String CHANNEL_INBOX = "inbox";
    private static final String BIZ_TYPE = "SCHEDULED_TASK";
    /** 正文里输出/错误摘要的最大长度，避免超长文本撑爆消息。 */
    private static final int MAX_SNIPPET = 500;

    private final ObjectProvider<RemoteMessageService> remoteMessageService;

    public ScheduledTaskNotifier(ObjectProvider<RemoteMessageService> remoteMessageService) {
        this.remoteMessageService = remoteMessageService;
    }

    /** 任务执行完成（无论成败）后通知创建人；任何异常都在内部消化。 */
    public void notifyResult(
            SubAgentEntity task,
            boolean success,
            String output,
            String errorMessage,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            TaskTriggerType triggerType) {
        if (task == null || StringUtils.isBlank(task.getCreatedBy())) {
            // 历史任务无创建人，无法定位接收人，跳过通知。
            return;
        }
        RemoteMessageService service = remoteMessageService.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            RemoteMessageCommand command = new RemoteMessageCommand();
            command.setTenantId(task.getTenantId());
            command.setSender(task.getCreatedBy());
            command.setReceivers(List.of(task.getCreatedBy()));
            command.setCategory(CATEGORY_SYSTEM);
            command.setLevel(success ? LEVEL_INFO : LEVEL_IMPORTANT);
            command.setChannels(List.of(CHANNEL_INBOX));
            command.setTitle(buildTitle(task.getName(), success));
            command.setContent(buildContent(task, success, output, errorMessage, startedAt, finishedAt, triggerType));
            command.setBizType(BIZ_TYPE);
            command.setBizId(task.getId());
            boolean ok = service.send(command);
            if (!ok) {
                log.warn("定时任务完成通知未被受理: task={}, success={}", task.getName(), success);
            }
        } catch (Exception e) {
            log.warn("定时任务完成通知发送失败: task={}, success={}", task.getName(), success, e);
        }
    }

    private String buildTitle(String taskName, boolean success) {
        return success ? "定时任务执行成功：" + taskName : "定时任务执行失败：" + taskName;
    }

    private String buildContent(
            SubAgentEntity task,
            boolean success,
            String output,
            String errorMessage,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            TaskTriggerType triggerType) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：").append(task.getName());
        sb.append("\n触发方式：").append(triggerType == TaskTriggerType.MANUAL ? "手动" : "定时");
        sb.append("\n执行结果：").append(success ? "成功" : "失败");
        sb.append("\n耗时：").append(durationText(startedAt, finishedAt));
        if (success && StringUtils.isNotBlank(output)) {
            sb.append("\n输出：").append(snippet(output));
        }
        if (!success && StringUtils.isNotBlank(errorMessage)) {
            sb.append("\n错误：").append(snippet(errorMessage));
        }
        return sb.toString();
    }

    private String durationText(LocalDateTime startedAt, LocalDateTime finishedAt) {
        if (startedAt == null || finishedAt == null) {
            return "未知";
        }
        long ms = Duration.between(startedAt, finishedAt).toMillis();
        return ms < 1000 ? ms + " 毫秒" : (ms / 1000) + " 秒";
    }

    private String snippet(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= MAX_SNIPPET ? trimmed : trimmed.substring(0, MAX_SNIPPET) + "…";
    }
}
