package com.lambda.fusion.ai.runtime.workspace.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.runtime.workspace.entity.WorkspaceAuditEntity;
import com.lambda.fusion.ai.runtime.workspace.mapper.WorkspaceAuditMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkspaceAuditServiceImplTest {
    @Test
    void shouldAssignUniqueSnowflakesBeforeAuditInsert() {
        WorkspaceAuditMapper mapper = mock(WorkspaceAuditMapper.class);
        List<Long> inserted = new ArrayList<>();
        when(mapper.insert(any(WorkspaceAuditEntity.class))).thenAnswer(call -> {
            WorkspaceAuditEntity entity = call.getArgument(0);
            assertThat(entity.getId()).isGreaterThan(1_000_000_000_000_000_000L);
            assertThat(entity.getAppId()).isEqualTo("app-1");
            inserted.add(entity.getId());
            return 1;
        });
        WorkspaceAuditServiceImpl service = new WorkspaceAuditServiceImpl(mapper);
        WorkspaceAuditEntity first = new WorkspaceAuditEntity();
        first.setAppId("app-1");
        first.setId(1L);
        WorkspaceAuditEntity second = new WorkspaceAuditEntity();
        second.setAppId("app-1");
        service.record(first);
        service.record(second);
        assertThat(inserted).containsExactly(first.getId(), second.getId()).doesNotHaveDuplicates();
    }
}
