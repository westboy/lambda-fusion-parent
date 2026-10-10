package com.lambda.fusion.form.runtime.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.service.DynamicDataService;
import com.lambda.fusion.form.runtime.support.FormDefSnapshot;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.form.runtime.support.SnapshotJson;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/** 任务随单写入：节点白名单解析、越权字段拒绝与运行时 update 委派。 */
class DynamicFormTaskFormHandlerTest {

    private final FormModelProvider models = mock(FormModelProvider.class);
    private final DynamicDataService data = mock(DynamicDataService.class);
    private final DynamicFormTaskFormHandler handler = new DynamicFormTaskFormHandler(models, data);

    @BeforeEach
    void setUp() {
        when(models.require("leave")).thenReturn(model());
    }

    @Test
    void writesEditableSubsetOnly() {
        Map<String, Object> written = handler.writeTaskFormData("leave", "row-1", "node_b", Map.of("amount", 5));

        assertThat(written).containsEntry("amount", 5);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(data).update(any(), eq("row-1"), captor.capture());
        assertThat(captor.getValue()).containsEntry("amount", 5);
    }

    @Test
    void rejectsFieldOutsideNodeWhitelist() {
        assertThatThrownBy(() -> handler.writeTaskFormData("leave", "row-1", "node_b", Map.of("remark", "x")))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_TASK_FIELD_FORBIDDEN.getCode()));
        verifyNoInteractions(data);
    }

    @Test
    void rejectsNodeWithoutFillableConfig() {
        assertThatThrownBy(() -> handler.writeTaskFormData("leave", "row-1", "node_x", Map.of("amount", 5)))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_TASK_FIELD_FORBIDDEN.getCode()));
        verifyNoInteractions(data);
    }

    @Test
    void rejectsMissingNodeCodeOrData() {
        assertThatThrownBy(() -> handler.writeTaskFormData("leave", "row-1", " ", Map.of("amount", 5)))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.INVALID_PARAMETER.getCode()));
        assertThatThrownBy(() -> handler.writeTaskFormData("leave", "row-1", "node_b", Map.of()))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.INVALID_PARAMETER.getCode()));
        verifyNoInteractions(data);
    }

    @Test
    void errorMessageCarriesOffendingField() {
        assertThatThrownBy(() -> handler.writeTaskFormData("leave", "row-1", "node_b", Map.of("amount", 5, "hack", 1)))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> {
                    FormBusinessException business = (FormBusinessException) error;
                    assertThat(business.getCode()).isEqualTo(FormErrorCode.FORM_TASK_FIELD_FORBIDDEN.getCode());
                    assertThat(business.getArgs()).containsExactly("hack");
                });
    }

    /** 主表两字段：amount 在 node_b 可填写；remark 无节点配置（任何节点都不可填写） */
    private static FormModel model() {
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("leave");
        snapshot.setFormName("请假");
        snapshot.setFormType(FormConstants.FORM_TYPE_SINGLE);
        snapshot.setTableName("LA_LEAVE");
        FormDefSnapshot.Field amount = new FormDefSnapshot.Field();
        amount.setFieldName("amount");
        amount.setColumnName("AMOUNT");
        amount.setFieldType("INT");
        FormDefSnapshot.NodePerm nodeB = new FormDefSnapshot.NodePerm();
        nodeB.setEditable(Boolean.TRUE);
        amount.getForm().setNodePerms(Map.of("node_b", nodeB));
        FormDefSnapshot.Field remark = new FormDefSnapshot.Field();
        remark.setFieldName("remark");
        remark.setColumnName("REMARK");
        remark.setFieldType("STRING");
        snapshot.setFields(List.of(amount, remark));
        SnapshotJson json = new SnapshotJson(new ObjectMapper());
        return new com.lambda.fusion.form.runtime.support.FormModelCompiler(json).compile(json.write(snapshot), 1L);
    }
}
