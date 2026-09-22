package com.lambda.fusion.workflow.integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 业务展示信息批量解析器（收集全部 {@link WorkflowBusinessProvider} 实现，按 bizType 路由）
 *
 * <p>无匹配 Provider 时静默跳过（bizInfo 为 null），列表照常返回——优雅降级。
 *
 * @author jin
 */
@Component
@RequiredArgsConstructor
public class BizInfoResolver {

    private final ObjectProvider<WorkflowBusinessProvider> providers;

    /**
     * 按业务类型分组批量解析并回填
     */
    public void fill(Collection<? extends BizInfoAware> carriers) {
        if (carriers == null || carriers.isEmpty()) {
            return;
        }
        Map<String, Set<String>> bizIdsByType = new HashMap<>();
        for (BizInfoAware carrier : carriers) {
            if (carrier.getBizType() != null && carrier.getBusinessId() != null) {
                bizIdsByType
                        .computeIfAbsent(carrier.getBizType(), k -> new LinkedHashSet<>())
                        .add(carrier.getBusinessId());
            }
        }
        for (Map.Entry<String, Set<String>> entry : bizIdsByType.entrySet()) {
            WorkflowBusinessProvider provider = providerFor(entry.getKey());
            if (provider == null) {
                continue;
            }
            Map<String, BizInfo> resolved = provider.resolve(entry.getKey(), entry.getValue());
            if (resolved == null || resolved.isEmpty()) {
                continue;
            }
            for (BizInfoAware carrier : carriers) {
                BizInfo bizInfo =
                        entry.getKey().equals(carrier.getBizType()) ? resolved.get(carrier.getBusinessId()) : null;
                if (bizInfo != null) {
                    carrier.setBizInfo(bizInfo);
                }
            }
        }
    }

    private WorkflowBusinessProvider providerFor(String bizType) {
        List<WorkflowBusinessProvider> matched = new ArrayList<>();
        for (WorkflowBusinessProvider provider : providers) {
            if (provider.supportedTypes() != null && provider.supportedTypes().contains(bizType)) {
                matched.add(provider);
            }
        }
        if (matched.isEmpty()) {
            return null;
        }
        if (matched.size() > 1) {
            throw new IllegalStateException(
                    "Duplicate WorkflowBusinessProvider for bizType '" + bizType + "': " + matched.size() + " beans");
        }
        return matched.getFirst();
    }
}
