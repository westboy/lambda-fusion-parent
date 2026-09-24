package com.fusion.startup.leave.service.impl;

import com.fusion.startup.leave.exception.LeaveBusinessException;
import com.fusion.startup.leave.exception.LeaveErrorCode;
import com.fusion.startup.leave.mapper.LeaveMapper;
import com.fusion.startup.leave.model.LeaveEntity;
import com.fusion.startup.leave.model.LeavePage;
import com.fusion.startup.leave.model.LeaveSaveCommand;
import com.fusion.startup.leave.service.LeaveService;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.workflow.instance.model.StartCommand;
import com.lambda.fusion.workflow.instance.service.InstanceService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 请假单服务实现
 *
 * <p>租户过滤与审计填充由框架拦截器/填充器承担，本类不重复处理。
 * 幂等由业务状态机保证：仅草稿可提交（§10.2 ⑤）——同一 businessId 引擎允许多实例
 * （驳回重提是合法场景），重复提交被 {@link LeaveErrorCode#LEAVE_STATUS_INVALID} 拦截。
 *
 * @author jin
 */
@Service
@RequiredArgsConstructor
public class LeaveServiceImpl implements LeaveService {

    /** 演示流程编码（须与设计器发布的定义 flow_code 一致） */
    private static final String FLOW_CODE = "leave_demo";

    /** 业务类型标识（落 flow_instance.ext，供 WorkflowBusinessProvider 路由） */
    private static final String BIZ_TYPE = "leave_demo";

    private final LeaveMapper leaveMapper;
    private final InstanceService instanceService;

    @Override
    public PageView<LeaveEntity> page(LeavePage page) {
        return PageView.of(leaveMapper.selectPage(page.getPage(), page.getLambdaQueryWrapper()));
    }

    @Override
    public LeaveEntity detail(String id) {
        return requireExists(id);
    }

    @Override
    public void save(LeaveSaveCommand command) {
        LeaveEntity entity = new LeaveEntity();
        entity.setApplicant(command.getApplicant());
        entity.setDays(command.getDays());
        entity.setReason(command.getReason());
        entity.setStatus(LeaveEntity.STATUS_DRAFT);
        leaveMapper.insert(entity);
    }

    @Override
    public void update(String id, LeaveSaveCommand command) {
        LeaveEntity entity = requireStatus(id, LeaveEntity.STATUS_DRAFT);
        entity.setApplicant(command.getApplicant());
        entity.setDays(command.getDays());
        entity.setReason(command.getReason());
        leaveMapper.updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(String id) {
        LeaveEntity leave = requireStatus(id, LeaveEntity.STATUS_DRAFT);
        leave.setStatus(LeaveEntity.STATUS_APPROVING);
        leaveMapper.updateById(leave);
        // 提交即启动：days 作为流程变量，供 skip_condition 条件分支（如 >3 天加签总监）
        StartCommand command = new StartCommand();
        command.setFlowCode(FLOW_CODE);
        command.setBusinessId(leave.getId());
        command.setBizType(BIZ_TYPE);
        command.setVariable(Map.of("days", leave.getDays()));
        instanceService.start(command);
    }

    @Override
    public void remove(String id) {
        requireStatus(id, LeaveEntity.STATUS_DRAFT);
        leaveMapper.deleteById(id);
    }

    @Override
    public void updateFlowStatus(String businessId, int status) {
        LeaveEntity entity = leaveMapper.selectById(businessId);
        if (entity == null) {
            return;
        }
        entity.setStatus(status);
        leaveMapper.updateById(entity);
    }

    private LeaveEntity requireExists(String id) {
        LeaveEntity entity = leaveMapper.selectById(id);
        if (entity == null) {
            throw new LeaveBusinessException(LeaveErrorCode.LEAVE_NOT_FOUND);
        }
        return entity;
    }

    private LeaveEntity requireStatus(String id, int expectedStatus) {
        LeaveEntity entity = requireExists(id);
        if (entity.getStatus() == null || entity.getStatus() != expectedStatus) {
            throw new LeaveBusinessException(LeaveErrorCode.LEAVE_STATUS_INVALID);
        }
        return entity;
    }
}
