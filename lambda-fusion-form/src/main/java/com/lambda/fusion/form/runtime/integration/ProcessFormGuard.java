package com.lambda.fusion.form.runtime.integration;

import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.workflow.WorkflowConstants;
import com.lambda.fusion.workflow.instance.service.InstanceService;
import lombok.RequiredArgsConstructor;

/**
 * 流程表单数据锁：审批在途（该行业务存在审批中的实例）时拒绝普通运行时写操作
 *
 * <p>锁定口径以引擎实例状态为唯一事实来源（审批中=1；退回/撤销/终态不锁，
 * 与状态回写监听器的草稿语义一致）：节点级填写必须走任务随单写入
 * （节点白名单校验 + 与引擎办理同事务），旁路的通用 update/delete/子表写端点在此拦截。
 * 装配由 FormConfigure 按 workflow 类路径条件守护，缺席时不锁（无流程能力）。
 *
 * @author Zx
 */
@RequiredArgsConstructor
public class ProcessFormGuard {

    private final InstanceService instanceService;

    /** 该业务行存在审批中的实例时抛 FORM_DATA_LOCKED */
    public void checkWritable(String businessId) {
        boolean processing = instanceService.listByBusiness(businessId).stream()
                .anyMatch(instance -> WorkflowConstants.isProcessing(instance.getFlowStatus()));
        if (processing) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_LOCKED);
        }
    }
}
