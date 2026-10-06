package com.lambda.fusion.workflow.bridge;

import com.lambda.fusion.workflow.WorkflowConstants;

/**
 * 流程领域事件：由 {@link WorkflowGlobalListener} 把引擎四钩子转发为 Spring 事件。
 * 异步消费方按工程契约 §5.1 用 tenantId 恢复 TenantContextHolder。
 *
 * @param flowStatus 引擎流程状态（0待提交/1审批中/2通过/4终止/5作废/6撤销/8完成/9退回/10失效/11拿回）
 * @param bizType 业务类型（发起入参，落 flow_instance.ext；发起方未传时为 null）
 * @author jin
 */
public record WorkflowFlowEvent(
        WorkflowConstants.Type type,
        Long instanceId,
        String businessId,
        Long taskId,
        String flowStatus,
        String bizType,
        String tenantId,
        String nodeCode,
        long occurredAt) {}
