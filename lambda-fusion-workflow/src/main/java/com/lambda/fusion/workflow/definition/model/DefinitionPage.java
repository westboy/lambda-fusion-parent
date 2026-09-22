package com.lambda.fusion.workflow.definition.model;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.lambda.fusion.core.pagination.PageQuery;
import lombok.Getter;
import lombok.Setter;
import org.dromara.warm.flow.orm.entity.FlowDefinition;

/**
 * 流程定义分页查询
 *
 * @author jin
 */
@Getter
@Setter
public class DefinitionPage extends PageQuery<FlowDefinition> {

    /**
     * 流程编码（模糊）
     */
    private String flowCode;

    /**
     * 流程名称（模糊）
     */
    private String flowName;

    /**
     * 流程类别（等值）
     */
    private String category;

    /**
     * 发布状态（等值：0 未发布 1 已发布 9 失效）
     */
    private Integer isPublish;

    @Override
    public LambdaQueryWrapper<FlowDefinition> getLambdaQueryWrapper() {
        LambdaQueryWrapper<FlowDefinition> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.isNotBlank(flowCode), FlowDefinition::getFlowCode, flowCode);
        wrapper.like(StringUtils.isNotBlank(flowName), FlowDefinition::getFlowName, flowName);
        wrapper.eq(StringUtils.isNotBlank(category), FlowDefinition::getCategory, category);
        wrapper.eq(isPublish != null, FlowDefinition::getIsPublish, isPublish);
        wrapper.orderByDesc(FlowDefinition::getCreateTime);
        return wrapper;
    }
}
