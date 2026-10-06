package com.lambda.fusion.workflow.exception;

import com.lambda.cloud.core.exception.model.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Workflow 模块错误码枚举
 * <p>
 * 错误码规则：Workflow 模块使用 16100-16199 范围（logger 占 159xx、ai 占 3xxxx 之前的首个空闲段）
 *
 * @author jin
 */
@Getter
@AllArgsConstructor
public enum WorkflowErrorCode implements ErrorCode {

    // ========== 通用错误 (16100-16109) ==========
    /**
     * 请求参数非法
     */
    INVALID_PARAMETER(16100, "请求参数非法"),

    /**
     * 流程未发布或不存在，无法发起
     */
    FLOW_NOT_PUBLISHED(16101, "流程未发布或不存在"),

    // ========== 流程定义错误 (16110-16119) ==========
    /**
     * 流程定义不存在
     */
    DEFINITION_NOT_FOUND(16110, "流程定义不存在"),

    /**
     * 已发布流程不可删除，须先失效
     */
    DEFINITION_DELETE_FORBIDDEN(16111, "已发布流程不可删除，请先失效"),

    // ========== 流程实例错误 (16120-16129) ==========
    /**
     * 流程实例不存在
     */
    INSTANCE_NOT_FOUND(16120, "流程实例不存在"),

    /**
     * 流程已结束，不允许该操作
     */
    INSTANCE_FINISHED(16121, "流程已结束，不允许该操作"),

    // ========== 任务错误 (16130-16139) ==========
    /**
     * 待办任务不存在
     */
    TASK_NOT_FOUND(16130, "待办任务不存在"),

    /**
     * 目标办理人非法
     */
    HANDLER_INVALID(16131, "目标办理人非法"),

    // ========== 引擎操作 (16140-16149) ==========
    /**
     * 引擎拒绝操作（办理人校验/状态校验/协作校验失败等），消息携带引擎原因
     */
    OPERATION_REJECTED(16140, "流程操作被拒绝：{0}"),

    // ========== 自定义表单 (16150-16159) ==========
    /**
     * 定义绑定的自定义表单不可挂载（未标记流程表单、未发布或已停用），消息携带 formPath
     */
    FORM_NOT_MOUNTABLE(16150, "绑定的自定义表单不可挂载：{0}");

    private final Integer code;

    private final String message;
}
