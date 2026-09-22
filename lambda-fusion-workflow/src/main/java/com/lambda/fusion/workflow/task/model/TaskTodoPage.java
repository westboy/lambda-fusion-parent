package com.lambda.fusion.workflow.task.model;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.lambda.fusion.core.pagination.PageQuery;
import lombok.Getter;
import lombok.Setter;
import org.dromara.warm.flow.orm.entity.FlowTask;

/**
 * 待办任务分页查询（过滤条件作用于 flow_task；权限过滤由服务层按当前用户完成）
 *
 * @author jin
 */
@Getter
@Setter
public class TaskTodoPage extends PageQuery<FlowTask> {

    /**
     * 节点名称（模糊）
     */
    private String nodeName;

    @Override
    public LambdaQueryWrapper<FlowTask> getLambdaQueryWrapper() {
        LambdaQueryWrapper<FlowTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.isNotBlank(nodeName), FlowTask::getNodeName, nodeName);
        wrapper.orderByDesc(FlowTask::getCreateTime);
        return wrapper;
    }
}
