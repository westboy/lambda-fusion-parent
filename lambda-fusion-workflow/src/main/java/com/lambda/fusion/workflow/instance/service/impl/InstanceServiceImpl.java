package com.lambda.fusion.workflow.instance.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.workflow.exception.EngineCalls;
import com.lambda.fusion.workflow.exception.WorkflowBusinessException;
import com.lambda.fusion.workflow.exception.WorkflowErrorCode;
import com.lambda.fusion.workflow.instance.model.ChartView;
import com.lambda.fusion.workflow.instance.model.HistoryView;
import com.lambda.fusion.workflow.instance.model.InstancePage;
import com.lambda.fusion.workflow.instance.model.InstanceView;
import com.lambda.fusion.workflow.instance.model.StartCommand;
import com.lambda.fusion.workflow.instance.service.InstanceService;
import com.lambda.fusion.workflow.integration.BizInfoResolver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.dromara.warm.flow.core.dto.FlowParams;
import org.dromara.warm.flow.core.entity.Definition;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.service.DefService;
import org.dromara.warm.flow.core.service.InsService;
import org.dromara.warm.flow.core.service.TaskService;
import org.dromara.warm.flow.orm.entity.FlowHisTask;
import org.dromara.warm.flow.orm.mapper.FlowHisTaskMapper;
import org.dromara.warm.flow.orm.mapper.FlowInstanceMapper;
import org.springframework.stereotype.Service;

/**
 * 流程实例服务实现：薄封装引擎 InsService/TaskService，补齐 fusion 侧校验与展示信息
 *
 * <p>租户过滤依赖框架 TenantLineInnerInterceptor（flow_* 表不豁免），本类不重复处理。
 *
 * @author jin
 */
@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class InstanceServiceImpl implements InstanceService {

    private final FlowInstanceMapper instanceMapper;
    private final FlowHisTaskMapper hisTaskMapper;
    private final InsService insService;
    private final TaskService taskService;
    private final DefService defService;
    private final BizInfoResolver bizInfoResolver;

    @Override
    public InstanceView start(StartCommand command) {
        Definition definition = defService.getPublishByFlowCode(command.getFlowCode());
        if (definition == null) {
            throw new WorkflowBusinessException(WorkflowErrorCode.FLOW_NOT_PUBLISHED, command.getFlowCode());
        }
        FlowParams params = FlowParams.build()
                .flowCode(command.getFlowCode())
                .variable(command.getVariable() == null ? new HashMap<>() : command.getVariable());
        if (StrUtil.isNotBlank(command.getBizType())) {
            params.ext(command.getBizType());
        }
        Instance instance = EngineCalls.call(() -> insService.start(command.getBusinessId(), params));
        return toView(instance);
    }

    @Override
    public PageView<InstanceView> page(InstancePage page) {
        return doPage(page, null);
    }

    @Override
    public PageView<InstanceView> pageMine(InstancePage page) {
        return doPage(page, AuthUtils.getSafeUsername());
    }

    private PageView<InstanceView> doPage(InstancePage page, String mineUsername) {
        LambdaQueryWrapper<org.dromara.warm.flow.orm.entity.FlowInstance> wrapper = page.getLambdaQueryWrapper();
        if (StrUtil.isNotBlank(mineUsername)) {
            wrapper.eq(org.dromara.warm.flow.orm.entity.FlowInstance::getCreateBy, mineUsername);
        }
        PageView<InstanceView> view = PageView.of(instanceMapper.selectPage(page.getPage(), wrapper), this::toView);
        bizInfoResolver.fill(view.getRecords());
        return view;
    }

    @Override
    public List<InstanceView> listByBusiness(String businessId) {
        LambdaQueryWrapper<org.dromara.warm.flow.orm.entity.FlowInstance> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(org.dromara.warm.flow.orm.entity.FlowInstance::getBusinessId, businessId);
        wrapper.orderByDesc(org.dromara.warm.flow.orm.entity.FlowInstance::getCreateTime);
        List<InstanceView> views =
                instanceMapper.selectList(wrapper).stream().map(this::toView).toList();
        bizInfoResolver.fill(views);
        return views;
    }

    @Override
    public InstanceView detail(Long id) {
        InstanceView view = toView(requireInstance(id));
        bizInfoResolver.fill(List.of(view));
        return view;
    }

    @Override
    public List<HistoryView> history(Long id) {
        requireInstance(id);
        LambdaQueryWrapper<FlowHisTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FlowHisTask::getInstanceId, id);
        wrapper.orderByAsc(FlowHisTask::getCreateTime);
        return hisTaskMapper.selectList(wrapper).stream()
                .map(this::toHistoryView)
                .toList();
    }

    @Override
    public ChartView chart(Long id) {
        Instance instance = requireInstance(id);
        org.dromara.warm.flow.core.dto.FlowCombine combine =
                EngineCalls.call(() -> defService.getFlowCombine(instance.getDefinitionId()));
        InstanceView view = toView(instance);
        bizInfoResolver.fill(List.of(view));
        return new ChartView(combine, view);
    }

    @Override
    public boolean revoke(Long id, String message) {
        requireInstance(id);
        // 引擎 revoke 内部校验「发起人 = handler」，必须显式携带当前用户
        FlowParams params = FlowParams.build().handler(AuthUtils.getSafeUsername());
        if (StrUtil.isNotBlank(message)) {
            params.message(message);
        }
        EngineCalls.call(() -> taskService.revoke(id, params));
        return true;
    }

    @Override
    public boolean terminate(Long id, String message) {
        requireInstance(id);
        FlowParams params = FlowParams.build().handler(AuthUtils.getSafeUsername());
        if (StrUtil.isNotBlank(message)) {
            params.message(message);
        }
        EngineCalls.call(() -> taskService.terminationByInsId(id, params));
        return true;
    }

    @Override
    public boolean active(Long id) {
        requireInstance(id);
        return EngineCalls.call(() -> insService.active(id));
    }

    @Override
    public boolean unActive(Long id) {
        requireInstance(id);
        return EngineCalls.call(() -> insService.unActive(id));
    }

    private Instance requireInstance(Long id) {
        Instance instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw new WorkflowBusinessException(WorkflowErrorCode.INSTANCE_NOT_FOUND);
        }
        return instance;
    }

    private InstanceView toView(Instance instance) {
        InstanceView view = new InstanceView();
        view.setId(instance.getId());
        view.setDefinitionId(instance.getDefinitionId());
        view.setBusinessId(instance.getBusinessId());
        view.setBizType(instance.getExt());
        view.setNodeCode(instance.getNodeCode());
        view.setNodeName(instance.getNodeName());
        view.setNodeType(instance.getNodeType());
        view.setFlowStatus(instance.getFlowStatus());
        view.setActivityStatus(instance.getActivityStatus());
        view.setCreateBy(instance.getCreateBy());
        view.setCreateTime(instance.getCreateTime());
        return view;
    }

    private HistoryView toHistoryView(FlowHisTask hisTask) {
        HistoryView view = new HistoryView();
        view.setId(hisTask.getId());
        view.setTaskId(hisTask.getTaskId());
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
