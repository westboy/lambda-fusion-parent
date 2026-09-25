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

    /**
     * 通过终态组：2 审批通过 / 3 自动完成 / 8 已完成。
     * 注意审批通过到达结束节点时实例终态为 8 而非 2（warm-flow 1.8.9 源码核实口径）
     */
    static boolean isPassed(String flowStatus) {
        return "2".equals(flowStatus) || "3".equals(flowStatus) || "8".equals(flowStatus);
    }

    /**
     * 退回发起方组：9 退回到发起节点 / 6 撤销（业务通常回草稿、可重提）
     */
    static boolean isReturned(String flowStatus) {
        return "9".equals(flowStatus) || "6".equals(flowStatus);
    }

    /**
     * 强制终态组：4 终止 / 5 作废 / 10 失效（业务通常置为驳回/关闭）。
     * 其余状态值（0 待提交 / 1 审批中 / 7 取回 / 11 拿回 / 12 重启 / 13 暂存）为在途或中间态，业务通常忽略
     */
    static boolean isTerminated(String flowStatus) {
        return "4".equals(flowStatus) || "5".equals(flowStatus) || "10".equals(flowStatus);
    }

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
