package com.lambda.fusion.workflow.integration;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 业务单据展示信息解析 SPI
 *
 * <p>流程列表（待办/已办/我发起/实例管理）需要业务标题与详情链接，而引擎表只有
 * business_id——业务事实留在业务表，由下游实现本 SPI 按业务类型批量解析。
 * 模块经 ObjectProvider 收集全部实现，按 bizType 路由；无实现时列表照常返回，
 * 标题/链接为空（优雅降级，不报错）。
 *
 * <p>bizType 在发起流程时作为入参传入并落 flow_instance.ext（约定：ext 仅存 bizType 字符串）。
 *
 * @author jin
 */
public interface WorkflowBusinessProvider {

    /**
     * 支持的业务类型标识（如 leave、expense），与发起入参 bizType 对应
     */
    Set<String> supportedTypes();

    /**
     * 批量解析业务单据展示信息
     *
     * @param bizType 业务类型
     * @param businessIds 业务单据 ID 集合
     * @return businessId → 展示信息
     */
    Map<String, BizInfo> resolve(String bizType, Collection<String> businessIds);
}
