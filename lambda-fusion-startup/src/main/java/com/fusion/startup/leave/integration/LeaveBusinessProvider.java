package com.fusion.startup.leave.integration;

import com.fusion.startup.leave.mapper.LeaveMapper;
import com.fusion.startup.leave.model.LeaveEntity;
import com.lambda.fusion.workflow.integration.BizInfo;
import com.lambda.fusion.workflow.integration.WorkflowBusinessProvider;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 请假单展示信息解析
 *
 * <p>待办/已办/我发起列表的标题列由 workflow 模块 BizInfoResolver 按本 SPI 批量回填；
 * 演示业务无独立详情路由，link 置 null（列表标题不可点击，优雅降级）。
 *
 * @author jin
 */
@Component
@RequiredArgsConstructor
public class LeaveBusinessProvider implements WorkflowBusinessProvider {

    private static final String BIZ_TYPE = "leave_demo";

    private final LeaveMapper leaveMapper;

    @Override
    public Set<String> supportedTypes() {
        return Set.of(BIZ_TYPE);
    }

    @Override
    public Map<String, BizInfo> resolve(String bizType, Collection<String> businessIds) {
        return leaveMapper.selectBatchIds(businessIds).stream()
                .collect(Collectors.toMap(LeaveEntity::getId, this::toBizInfo));
    }

    private BizInfo toBizInfo(LeaveEntity leave) {
        String title = leave.getApplicant() + " 的请假单（" + leave.getDays() + " 天）";
        return new BizInfo(title, null);
    }
}
