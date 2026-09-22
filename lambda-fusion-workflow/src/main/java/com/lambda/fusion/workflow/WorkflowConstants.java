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
}
