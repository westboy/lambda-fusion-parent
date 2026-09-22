package com.lambda.fusion.workflow.instance.model;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.lambda.fusion.core.pagination.PageQuery;
import lombok.Getter;
import lombok.Setter;
import org.dromara.warm.flow.orm.entity.FlowInstance;

/**
 * 流程实例分页查询（管理端全量 / 用户侧「我发起的」复用）
 *
 * @author jin
 */
@Getter
@Setter
public class InstancePage extends PageQuery<FlowInstance> {

    /**
     * 业务单据 ID（等值）
     */
    private String businessId;

    /**
     * 流程状态（等值）
     */
    private String flowStatus;

    @Override
    public LambdaQueryWrapper<FlowInstance> getLambdaQueryWrapper() {
        LambdaQueryWrapper<FlowInstance> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.isNotBlank(businessId), FlowInstance::getBusinessId, businessId);
        wrapper.eq(StringUtils.isNotBlank(flowStatus), FlowInstance::getFlowStatus, flowStatus);
        wrapper.orderByDesc(FlowInstance::getCreateTime);
        return wrapper;
    }
}
