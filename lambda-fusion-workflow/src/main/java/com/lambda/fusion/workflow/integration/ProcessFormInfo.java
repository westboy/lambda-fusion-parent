package com.lambda.fusion.workflow.integration;

/**
 * 可挂载流程表单项
 *
 * @param formKey  表单标识（落 flow_definition.form_path，办理端按 formKey 渲染动态表单）
 * @param formName 表单显示名（设计器下拉展示）
 * @author Zx
 */
public record ProcessFormInfo(String formKey, String formName) {}
