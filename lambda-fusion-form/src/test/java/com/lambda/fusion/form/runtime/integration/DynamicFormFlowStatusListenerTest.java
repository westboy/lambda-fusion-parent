package com.lambda.fusion.form.runtime.integration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.design.model.TableColumnView;
import com.lambda.fusion.form.design.support.TableMetadataReader;
import com.lambda.fusion.form.runtime.mapper.DynamicTableMapper;
import com.lambda.fusion.form.runtime.support.FormDefSnapshot;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.form.runtime.support.SnapshotJson;
import com.lambda.fusion.workflow.WorkflowConstants;
import com.lambda.fusion.workflow.bridge.WorkflowFlowEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * 状态回写桥防御性验证：无 STATUS 列跳过、回写异常不外抛（不阻断审批主流程）、中间态忽略。
 */
class DynamicFormFlowStatusListenerTest {

    private final FormDefMapper forms = mock(FormDefMapper.class);
    private final FormModelProvider models = mock(FormModelProvider.class);
    private final DynamicTableMapper dynamicTableMapper = mock(DynamicTableMapper.class);
    private final TableMetadataReader metadataReader = mock(TableMetadataReader.class);
    private DynamicFormFlowStatusListener listener;

    @BeforeEach
    void setUp() {
        listener = new DynamicFormFlowStatusListener(forms, models, dynamicTableMapper, metadataReader);
        when(forms.selectCount(any())).thenReturn(1L);
        when(models.require("leave")).thenReturn(model());
    }

    @Test
    void missingStatusColumnSkipsSilentlyInsteadOfBreakingApproval() {
        when(metadataReader.listColumns("LA_LEAVE")).thenReturn(List.of(column("ID"), column("DAYS")));

        listener.on(event(WorkflowConstants.Type.FINISH, "8"));

        verifyNoInteractions(dynamicTableMapper);
    }

    @Test
    void writeFailureIsDowngradedToWarningAndNeverRethrown() {
        when(metadataReader.listColumns("LA_LEAVE")).thenReturn(List.of(column("ID"), column("STATUS")));
        when(dynamicTableMapper.updateDynamic(anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("simulated sql failure"));

        assertThatCode(() -> listener.on(event(WorkflowConstants.Type.FINISH, "8")))
                .doesNotThrowAnyException();
    }

    @Test
    void finishWithStatusColumnWritesMappedStatus() {
        when(metadataReader.listColumns("LA_LEAVE")).thenReturn(List.of(column("ID"), column("STATUS")));
        when(dynamicTableMapper.updateDynamic(anyString(), anyString(), anyString(), any()))
                .thenReturn(1);

        listener.on(event(WorkflowConstants.Type.FINISH, "8"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicTableMapper)
                .updateDynamic(eq("LA_LEAVE"), eq("STATUS = #{params.status}"), eq(""), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue())
                .containsEntry("status", 2)
                .containsEntry("id", "row-1");
    }

    @Test
    void intermediateFlowStatusIsIgnored() {
        when(metadataReader.listColumns("LA_LEAVE")).thenReturn(List.of(column("ID"), column("STATUS")));

        listener.on(event(WorkflowConstants.Type.FINISH, "1"));

        verifyNoInteractions(dynamicTableMapper);
    }

    private WorkflowFlowEvent event(WorkflowConstants.Type type, String flowStatus) {
        return new WorkflowFlowEvent(type, 9L, "row-1", null, flowStatus, "leave", "T1", null, 0L);
    }

    private TableColumnView column(String name) {
        return new TableColumnView(name, null, "int", "INT", false, true, 4, 0, null, null, false, false);
    }

    private static FormModel model() {
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("leave");
        snapshot.setFormName("请假");
        snapshot.setFormType(FormConstants.FORM_TYPE_SINGLE);
        snapshot.setTableName("LA_LEAVE");
        FormDefSnapshot.Field days = new FormDefSnapshot.Field();
        days.setFieldName("days");
        days.setColumnName("DAYS");
        days.setFieldType("INT");
        snapshot.setFields(java.util.List.of(days));
        SnapshotJson json = new SnapshotJson(new ObjectMapper());
        return new com.lambda.fusion.form.runtime.support.FormModelCompiler(json).compile(json.write(snapshot), 1L);
    }
}
