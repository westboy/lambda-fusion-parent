package com.lambda.fusion.ai.runtime.workspace.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.ai.runtime.workspace.entity.WorkspaceAuditEntity;
import com.lambda.fusion.ai.runtime.workspace.mapper.WorkspaceAuditMapper;
import com.lambda.fusion.ai.runtime.workspace.service.WorkspaceAuditService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class WorkspaceAuditServiceImpl implements WorkspaceAuditService {

    private final WorkspaceAuditMapper workspaceAuditMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void record(WorkspaceAuditEntity entity) {
        entity.setId(IdUtil.getSnowflakeNextId());
        workspaceAuditMapper.insert(entity);
    }

    @Override
    public List<WorkspaceAuditEntity> listByAppAndTenant(String appId, String tenantId) {
        return workspaceAuditMapper.selectList(new LambdaQueryWrapper<WorkspaceAuditEntity>()
                .eq(WorkspaceAuditEntity::getAppId, appId)
                .eq(WorkspaceAuditEntity::getTenantId, tenantId)
                .orderByDesc(WorkspaceAuditEntity::getCreatedAt));
    }
}
