package com.lambda.fusion.workflow.integration;

import java.util.Map;

/**
 * 待办任务随单表单写入 SPI（form 模块在场时提供实现）
 *
 * <p>办理「通过」携带 formData 时由任务服务回调：实现方按 formKey + nodeCode
 * 解析该节点可填写的字段子集，校验后写入 businessId 对应业务行，并返回实际写入的
 * 字段数据（并入流程变量，供后续条件分支消费）。
 * 消费侧经 ObjectProvider 取用，缺席且 formData 非空时拒绝办理。
 *
 * @author Zx
 */
public interface TaskFormHandler {

    /**
     * 校验并写入当前任务节点允许填写的字段子集
     *
     * @param formKey 业务类型（流程表单为动态表单 formKey）
     * @param businessId 业务行 id（实例 business_id，服务端解析，不取自客户端）
     * @param nodeCode 当前任务节点编码
     * @param formData 办理提交的表单数据（字段标识 → 值）
     * @return 实际写入的字段数据（键为字段标识，供并入流程变量）
     */
    Map<String, Object> writeTaskFormData(
            String formKey, String businessId, String nodeCode, Map<String, Object> formData);
}
