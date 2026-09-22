package com.lambda.fusion.workflow.integration;

/**
 * 业务单据展示信息：待办/已办/实例列表经 {@link WorkflowBusinessProvider} 回填
 *
 * @param title 展示标题（如「张三的请假单（3 天）」）
 * @param link 业务单据详情页链接（前端跳转用）
 * @author jin
 */
public record BizInfo(String title, String link) {}
