package com.lambda.fusion.workflow.bridge;

import com.lambda.fusion.workflow.WorkflowConstants;

/**
 * 流程领域事件：由 {@link WorkflowGlobalListener} 把引擎四钩子转发为 Spring 事件
 *
 * <p>模块内与下游应用均通过 @EventListener 消费；事件携带 tenantId，
 * 供异步消费线程按工程契约 §5.1 从领域对象恢复 TenantContextHolder 后处理、结束清理。
 *
 * @param type 钩子类型
 * @param instanceId 流程实例 ID
 * @param businessId 业务单据 ID（flow_instance.business_id）
 * @param taskId 当前任务 ID（任务未创建阶段可为 null）
 * @param flowStatus 引擎流程状态（0 待提交/1 审批中/2 通过/4 终止/5 作废/6 撤销/8 完成/9 退回/10 失效/11 拿回）
 * @param tenantId 租户 ID
 * @param nodeCode 当前节点编码
 * @param occurredAt 事件发生时间戳（毫秒）
 * @author jin
 */
public record WorkflowFlowEvent(
        WorkflowConstants.Type type,
        Long instanceId,
        String businessId,
        Long taskId,
        String flowStatus,
        String tenantId,
        String nodeCode,
        long occurredAt) {}
