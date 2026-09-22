package com.lambda.fusion.workflow.instance.service;

import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.workflow.instance.model.ChartView;
import com.lambda.fusion.workflow.instance.model.HistoryView;
import com.lambda.fusion.workflow.instance.model.InstancePage;
import com.lambda.fusion.workflow.instance.model.InstanceView;
import com.lambda.fusion.workflow.instance.model.StartCommand;
import java.util.List;

/**
 * 流程实例服务：发起、查询、审批历史、流程图、撤回/终止/挂起/激活
 *
 * @author jin
 */
public interface InstanceService {

    /**
     * 发起流程（业务侧调用或本模块 REST 触达）
     */
    InstanceView start(StartCommand command);

    /**
     * 管理端全量分页
     */
    PageView<InstanceView> page(InstancePage page);

    /**
     * 我发起的（create_by = 当前登录用户）
     */
    PageView<InstanceView> pageMine(InstancePage page);

    /**
     * 按业务单据查实例列表（一单可能多次发起）
     */
    List<InstanceView> listByBusiness(String businessId);

    /**
     * 实例详情
     */
    InstanceView detail(Long id);

    /**
     * 审批历史（flow_his_task 按时间正序）
     */
    List<HistoryView> history(Long id);

    /**
     * 流程图数据（定义结构 + 实例当前状态，前端 @vue-flow 渲染）
     */
    ChartView chart(Long id);

    /**
     * 发起人撤回（回到上一节点，引擎校验发起人身份）
     */
    boolean revoke(Long id, String message);

    /**
     * 管理端终止实例
     */
    boolean terminate(Long id, String message);

    /**
     * 激活挂起的实例
     */
    boolean active(Long id);

    /**
     * 挂起实例（暂停流转）
     */
    boolean unActive(Long id);
}
