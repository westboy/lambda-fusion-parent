package com.lambda.fusion.workflow.bridge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.message.api.RemoteMessageCommand;
import com.lambda.fusion.message.api.RemoteMessageService;
import com.lambda.fusion.workflow.WorkflowConstants;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dromara.warm.flow.orm.entity.FlowInstance;
import org.dromara.warm.flow.orm.entity.FlowTask;
import org.dromara.warm.flow.orm.entity.FlowUser;
import org.dromara.warm.flow.orm.mapper.FlowInstanceMapper;
import org.dromara.warm.flow.orm.mapper.FlowTaskMapper;
import org.dromara.warm.flow.orm.mapper.FlowUserMapper;
import org.springframework.context.event.EventListener;

/**
 * 待办任务站内信通知器：消费 {@code ASSIGNMENT} 事件，经 message-api 通知办理人。
 *
 * <p>收件人取 flow_user 审批人权限标识中 {@code user:{username}} 形态的直接展开；
 * {@code role:}/{@code org:} 聚合标识不做展开（需角色→用户远程接口，后续按需扩充 authority-api）。
 * message 缺失或发送失败仅记日志，不影响流程流转（对齐 ai 模块 ScheduledTaskNotifier）。
 *
 * @author jin
 */
@Slf4j
public class WorkflowTaskNotifier {

    private static final String CATEGORY_SYSTEM = "system";
    private static final String LEVEL_INFO = "info";
    private static final String CHANNEL_INBOX = "inbox";
    private static final String BIZ_TYPE = "WORKFLOW_TASK";

    private final RemoteMessageService messageService;
    private final FlowUserMapper flowUserMapper;
    private final FlowTaskMapper flowTaskMapper;
    private final FlowInstanceMapper flowInstanceMapper;

    public WorkflowTaskNotifier(
            RemoteMessageService messageService,
            FlowUserMapper flowUserMapper,
            FlowTaskMapper flowTaskMapper,
            FlowInstanceMapper flowInstanceMapper) {
        this.messageService = messageService;
        this.flowUserMapper = flowUserMapper;
        this.flowTaskMapper = flowTaskMapper;
        this.flowInstanceMapper = flowInstanceMapper;
    }

    @EventListener
    public void on(WorkflowFlowEvent event) {
        if (messageService == null || event.type() != WorkflowConstants.Type.ASSIGNMENT || event.taskId() == null) {
            return;
        }
        try {
            List<String> receivers = resolveReceivers(event.taskId());
            if (receivers.isEmpty()) {
                return;
            }
            FlowTask task = flowTaskMapper.selectById(event.taskId());
            if (task == null) {
                return;
            }
            FlowInstance instance = flowInstanceMapper.selectById(task.getInstanceId());
            if (instance == null) {
                return;
            }
            // 引擎对同一任务只分派一次 ASSIGNMENT；existsRecent 做防御性判重（重复事件不重发）
            if (messageService.existsRecent(BIZ_TYPE, String.valueOf(event.taskId()), 1)) {
                return;
            }
            messageService.send(buildCommand(event, receivers, task, instance));
        } catch (Exception e) {
            log.warn("待办任务通知发送失败: taskId={}", event.taskId(), e);
        }
    }

    /** flow_user 审批人标识 → user: 前缀直接提取用户名（role:/org: 聚合标识不展开，跳过） */
    private List<String> resolveReceivers(Long taskId) {
        LambdaQueryWrapper<FlowUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FlowUser::getAssociated, taskId);
        wrapper.eq(FlowUser::getType, WorkflowConstants.USER_TYPE_APPROVAL);
        return flowUserMapper.selectList(wrapper).stream()
                .map(FlowUser::getProcessedBy)
                .filter(flag -> flag != null && flag.startsWith(WorkflowConstants.PERMISSION_PREFIX_USER))
                .map(flag -> flag.substring(WorkflowConstants.PERMISSION_PREFIX_USER.length()))
                .distinct()
                .toList();
    }

    private RemoteMessageCommand buildCommand(
            WorkflowFlowEvent event, List<String> receivers, FlowTask task, FlowInstance instance) {
        RemoteMessageCommand command = new RemoteMessageCommand();
        command.setTenantId(event.tenantId());
        command.setSender(StringUtils.defaultIfBlank(instance.getCreateBy(), "system"));
        command.setReceivers(receivers);
        command.setCategory(CATEGORY_SYSTEM);
        command.setLevel(LEVEL_INFO);
        command.setChannels(List.of(CHANNEL_INBOX));
        command.setTitle("您有新的待办任务：" + StringUtils.defaultIfBlank(task.getFlowName(), "流程审批"));
        command.setContent("任务节点：" + StringUtils.defaultIfBlank(task.getNodeName(), "-")
                + "\n发起人：" + StringUtils.defaultIfBlank(instance.getCreateBy(), "-")
                + "\n请及时进入「我的待办」处理。");
        command.setBizType(BIZ_TYPE);
        command.setBizId(String.valueOf(event.taskId()));
        return command;
    }
}
