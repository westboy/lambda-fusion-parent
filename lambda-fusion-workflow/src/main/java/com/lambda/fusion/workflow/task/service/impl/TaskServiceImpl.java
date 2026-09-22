package com.lambda.fusion.workflow.task.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.workflow.WorkflowConstants;
import com.lambda.fusion.workflow.exception.EngineCalls;
import com.lambda.fusion.workflow.instance.model.HistoryView;
import com.lambda.fusion.workflow.integration.BizInfoResolver;
import com.lambda.fusion.workflow.task.model.ApproveCommand;
import com.lambda.fusion.workflow.task.model.RejectCommand;
import com.lambda.fusion.workflow.task.model.SignatureCommand;
import com.lambda.fusion.workflow.task.model.TaskDonePage;
import com.lambda.fusion.workflow.task.model.TaskTodoPage;
import com.lambda.fusion.workflow.task.model.TaskView;
import com.lambda.fusion.workflow.task.model.TransferCommand;
import com.lambda.fusion.workflow.task.service.TaskService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.dromara.warm.flow.core.dto.FlowParams;
import org.dromara.warm.flow.core.handler.PermissionHandler;
import org.dromara.warm.flow.orm.entity.FlowHisTask;
import org.dromara.warm.flow.orm.entity.FlowInstance;
import org.dromara.warm.flow.orm.entity.FlowTask;
import org.dromara.warm.flow.orm.entity.FlowUser;
import org.dromara.warm.flow.orm.mapper.FlowHisTaskMapper;
import org.dromara.warm.flow.orm.mapper.FlowInstanceMapper;
import org.dromara.warm.flow.orm.mapper.FlowTaskMapper;
import org.dromara.warm.flow.orm.mapper.FlowUserMapper;
import org.springframework.stereotype.Service;

/**
 * 待办任务服务实现
 *
 * <p>待办两段式查询：先按当前用户权限标识查 flow_user（type=审批）拿任务 ID 集，
 * 再按 ID 集分页查 flow_task——引擎的任务-权限人在 flow_user，避免全表扫描审批人字段。
 * 引擎 {@link org.dromara.warm.flow.core.service.TaskService} 与本接口同名，
 * 以全限定名字段注入。
 *
 * @author jin
 */
@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class TaskServiceImpl implements TaskService {

    private final FlowTaskMapper flowTaskMapper;
    private final FlowUserMapper flowUserMapper;
    private final FlowInstanceMapper flowInstanceMapper;
    private final FlowHisTaskMapper flowHisTaskMapper;
    private final org.dromara.warm.flow.core.service.TaskService engineTaskService;
    private final PermissionHandler permissionHandler;
    private final BizInfoResolver bizInfoResolver;

    @Override
    public PageView<TaskView> pageTodo(TaskTodoPage page) {
        List<String> flags = permissionHandler.permissions();
        if (CollUtil.isEmpty(flags)) {
            return PageView.of(page.getPage(), this::toView);
        }
        LambdaQueryWrapper<FlowUser> userWrapper = new LambdaQueryWrapper<>();
        userWrapper.in(FlowUser::getProcessedBy, flags);
        userWrapper.eq(FlowUser::getType, WorkflowConstants.USER_TYPE_APPROVAL);
        List<FlowUser> flowUsers = flowUserMapper.selectList(userWrapper);
        if (flowUsers.isEmpty()) {
            return PageView.of(page.getPage(), this::toView);
        }
        Set<Long> taskIds = flowUsers.stream()
                .map(FlowUser::getAssociated)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        LambdaQueryWrapper<FlowTask> wrapper = page.getLambdaQueryWrapper();
        wrapper.in(FlowTask::getId, taskIds);
        PageView<TaskView> view = PageView.of(flowTaskMapper.selectPage(page.getPage(), wrapper), this::toView);
        fillTaskBizInfo(view.getRecords());
        return view;
    }

    @Override
    public PageView<HistoryView> pageDone(TaskDonePage page) {
        LambdaQueryWrapper<FlowHisTask> wrapper = page.getLambdaQueryWrapper();
        wrapper.eq(FlowHisTask::getApprover, AuthUtils.getSafeUsername());
        PageView<HistoryView> view =
                PageView.of(flowHisTaskMapper.selectPage(page.getPage(), wrapper), this::toHistoryView);
        fillHistoryBizInfo(view.getRecords());
        return view;
    }

    @Override
    public boolean pass(Long taskId, ApproveCommand command) {
        Map<String, Object> variable = command.getVariable() == null ? new HashMap<>() : command.getVariable();
        EngineCalls.call(() -> engineTaskService.pass(taskId, command.getMessage(), variable));
        return true;
    }

    @Override
    public boolean reject(Long taskId, RejectCommand command) {
        EngineCalls.call(() -> engineTaskService.reject(taskId, command.getMessage(), new HashMap<>()));
        return true;
    }

    @Override
    public boolean transfer(Long taskId, TransferCommand command) {
        FlowParams params = newFlowParams().message(command.getMessage());
        params.addHandlers(List.of(command.getTargetUsername()));
        return EngineCalls.call(() -> engineTaskService.transfer(taskId, params));
    }

    @Override
    public boolean depute(Long taskId, TransferCommand command) {
        FlowParams params = newFlowParams().message(command.getMessage());
        params.addHandlers(List.of(command.getTargetUsername()));
        return EngineCalls.call(() -> engineTaskService.depute(taskId, params));
    }

    @Override
    public boolean addSignature(Long taskId, SignatureCommand command) {
        FlowParams params = newFlowParams().message(command.getMessage());
        params.addHandlers(command.getHandlers());
        return EngineCalls.call(() -> engineTaskService.addSignature(taskId, params));
    }

    @Override
    public boolean reductionSignature(Long taskId, SignatureCommand command) {
        FlowParams params = newFlowParams().message(command.getMessage());
        params.reductionHandlers(command.getHandlers());
        return EngineCalls.call(() -> engineTaskService.reductionSignature(taskId, params));
    }

    @Override
    public boolean updateHandler(Long taskId, List<String> targetUsernames) {
        // 管理端改办理人：调用者未必是当前办理人，ignore 绕过引擎的处理人校验
        FlowParams params = newFlowParams().ignore(true);
        params.addHandlers(targetUsernames);
        return EngineCalls.call(() -> engineTaskService.updateHandler(taskId, params));
    }

    @Override
    public boolean passAtWill(Long taskId, String nodeCode, String message, Map<String, Object> variable) {
        Map<String, Object> vars = variable == null ? new HashMap<>() : variable;
        EngineCalls.call(() -> engineTaskService.passAtWill(taskId, nodeCode, message, vars));
        return true;
    }

    /**
     * 引擎 FlowParams 惯例：handler=当前用户，permissionFlag=当前用户权限标识集合
     * （transfer/depute/加减签/updateHandler 内部均消费这两者）
     */
    private FlowParams newFlowParams() {
        return FlowParams.build().handler(AuthUtils.getSafeUsername()).permissionFlag(permissionHandler.permissions());
    }

    private void fillTaskBizInfo(List<TaskView> views) {
        if (views.isEmpty()) {
            return;
        }
        Set<Long> instanceIds = views.stream()
                .map(TaskView::getInstanceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (instanceIds.isEmpty()) {
            return;
        }
        Map<Long, FlowInstance> instanceById = flowInstanceMapper.selectBatchIds(instanceIds).stream()
                .collect(Collectors.toMap(FlowInstance::getId, Function.identity()));
        for (TaskView view : views) {
            FlowInstance instance = instanceById.get(view.getInstanceId());
            if (instance != null) {
                view.setBusinessId(instance.getBusinessId());
                view.setBizType(instance.getExt());
            }
        }
        bizInfoResolver.fill(views);
    }

    private void fillHistoryBizInfo(List<HistoryView> views) {
        if (views.isEmpty()) {
            return;
        }
        Set<Long> instanceIds = views.stream()
                .map(HistoryView::getInstanceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (instanceIds.isEmpty()) {
            return;
        }
        Map<Long, FlowInstance> instanceById = flowInstanceMapper.selectBatchIds(instanceIds).stream()
                .collect(Collectors.toMap(FlowInstance::getId, Function.identity()));
        for (HistoryView view : views) {
            FlowInstance instance = instanceById.get(view.getInstanceId());
            if (instance != null) {
                view.setBusinessId(instance.getBusinessId());
                view.setBizType(instance.getExt());
            }
        }
        bizInfoResolver.fill(views);
    }

    private TaskView toView(FlowTask task) {
        TaskView view = new TaskView();
        view.setId(task.getId());
        view.setInstanceId(task.getInstanceId());
        view.setDefinitionId(task.getDefinitionId());
        view.setNodeCode(task.getNodeCode());
        view.setNodeName(task.getNodeName());
        view.setNodeType(task.getNodeType());
        view.setFlowStatus(task.getFlowStatus());
        view.setCreateTime(task.getCreateTime());
        return view;
    }

    private HistoryView toHistoryView(FlowHisTask hisTask) {
        HistoryView view = new HistoryView();
        view.setId(hisTask.getId());
        view.setTaskId(hisTask.getTaskId());
        view.setInstanceId(hisTask.getInstanceId());
        view.setNodeCode(hisTask.getNodeCode());
        view.setNodeName(hisTask.getNodeName());
        view.setTargetNodeCode(hisTask.getTargetNodeCode());
        view.setTargetNodeName(hisTask.getTargetNodeName());
        view.setApprover(hisTask.getApprover());
        view.setCooperateType(hisTask.getCooperateType());
        view.setCollaborator(hisTask.getCollaborator());
        view.setSkipType(hisTask.getSkipType());
        view.setFlowStatus(hisTask.getFlowStatus());
        view.setMessage(hisTask.getMessage());
        view.setCreateTime(hisTask.getCreateTime());
        view.setUpdateTime(hisTask.getUpdateTime());
        return view;
    }
}
