package com.lambda.fusion.workflow;

/**
 * Workflow 模块常量
 *
 * @author jin
 */
public interface WorkflowConstants {

    /**
     * 办理人权限标识前缀：当前用户（username）
     */
    String PERMISSION_PREFIX_USER = "user:";

    /**
     * 办理人权限标识前缀：角色编码
     */
    String PERMISSION_PREFIX_ROLE = "role:";

    /**
     * 办理人权限标识前缀：组织 ID
     */
    String PERMISSION_PREFIX_ORG = "org:";

    /**
     * flow_user 人员类型：待办任务的审批人权限
     */
    String USER_TYPE_APPROVAL = "1";

    /**
     * 流程类别对应的字典类型编码（dictionary 模块 la_dict_type/dict_info 自持）
     */
    String DICT_TYPE_WORKFLOW_CATEGORY = "workflow_category";

    enum Type {
        /**
         * 任务开始办理
         */
        START,
        /**
         * 新待办分派（通知场景消费）
         */
        ASSIGNMENT,
        /**
         * 当前任务完成（业务状态回写消费）
         */
        FINISH,
        /**
         * 任务创建
         */
        CREATE
    }
}
