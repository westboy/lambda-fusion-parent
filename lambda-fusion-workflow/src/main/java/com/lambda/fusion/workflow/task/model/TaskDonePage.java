package com.lambda.fusion.workflow.task.model;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.lambda.fusion.core.pagination.PageQuery;
import lombok.Getter;
import lombok.Setter;
import org.dromara.warm.flow.orm.entity.FlowHisTask;

/**
 * 已办任务分页查询（过滤条件作用于 flow_his_task；审批人过滤由服务层按当前用户完成）
 *
 * @author jin
 */
@Getter
@Setter
public class TaskDonePage extends PageQuery<FlowHisTask> {

    /**
     * 节点名称（模糊）
     */
    private String nodeName;

    /**
     * 审批意见（模糊）
     */
    private String message;

    @Override
    public LambdaQueryWrapper<FlowHisTask> getLambdaQueryWrapper() {
        LambdaQueryWrapper<FlowHisTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.isNotBlank(nodeName), FlowHisTask::getNodeName, nodeName);
        wrapper.like(StringUtils.isNotBlank(message), FlowHisTask::getMessage, message);
        wrapper.orderByDesc(FlowHisTask::getCreateTime);
        return wrapper;
    }
}
