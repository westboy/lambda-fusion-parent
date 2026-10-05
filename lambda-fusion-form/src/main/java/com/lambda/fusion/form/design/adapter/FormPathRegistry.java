package com.lambda.fusion.form.design.adapter;

/**
 * 流程表单注册表（workflow 模块 la_workflow_form_path 的写入口）
 *
 * <p>表单挂路由时自动登记，使 warm-flow 设计器「自定义表单路径」下拉可直接选到动态表单；
 * 撤路由时注销。workflow 模块缺席时无实现 Bean，消费侧空安全跳过。
 *
 * @author Zx
 */
public interface FormPathRegistry {

    /**
     * 按路径幂等注册/更新（存在则更新名称并启用）
     *
     * @param formName 表单显示名
     * @param formPath 表单运行时路由路径
     * @param remarks 备注
     */
    void register(String formName, String formPath, String remarks);

    /**
     * 按路径注销（记录不存在时静默跳过）
     *
     * @param formPath 表单运行时路由路径
     */
    void unregister(String formPath);
}
