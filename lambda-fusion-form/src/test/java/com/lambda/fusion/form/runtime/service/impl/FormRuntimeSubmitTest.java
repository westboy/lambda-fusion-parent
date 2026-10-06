package com.lambda.fusion.form.runtime.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.assembler.FormSchemaAssembler;
import com.lambda.fusion.form.runtime.service.DynamicDataService;
import com.lambda.fusion.form.runtime.support.FormDefSnapshot;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.form.runtime.support.SnapshotJson;
import com.lambda.fusion.workflow.instance.model.StartCommand;
import com.lambda.fusion.workflow.instance.service.InstanceService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

/** 流程表单提交编排：同事务 create+start 的参数绑定与 workflow 缺席拒绝。 */
class FormRuntimeSubmitTest {

    private final FormModelProvider models = mock(FormModelProvider.class);
    private final DynamicDataService data = mock(DynamicDataService.class);
    private final InstanceService instances = mock(InstanceService.class);
    private FormRuntimeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FormRuntimeServiceImpl(models, data, mock(FormSchemaAssembler.class), providerOf(true));
        when(models.require("leave")).thenReturn(model());
        when(data.create(any(), any())).thenReturn("row-1");
    }

    @Test
    void submitOrchestratesCreateThenStartWithBoundBusinessId() {
        String id = service.submit("leave", "leave_flow", Map.of("days", 3));

        assertThat(id).isEqualTo("row-1");
        ArgumentCaptor<StartCommand> captor = ArgumentCaptor.forClass(StartCommand.class);
        verify(instances).start(captor.capture());
        StartCommand command = captor.getValue();
        assertThat(command.getBusinessId()).isEqualTo("row-1");
        assertThat(command.getFlowCode()).isEqualTo("leave_flow");
        assertThat(command.getBizType()).isEqualTo("leave");
        assertThat(command.getVariable()).containsEntry("days", 3);
    }

    @Test
    void submitRejectsWhenWorkflowModuleIsAbsent() {
        FormRuntimeServiceImpl isolated =
                new FormRuntimeServiceImpl(models, data, mock(FormSchemaAssembler.class), providerOf(false));
        assertThatThrownBy(() -> isolated.submit("leave", "leave_flow", Map.of()))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_WORKFLOW_UNAVAILABLE.getCode()));
        org.mockito.Mockito.verifyNoInteractions(instances);
    }

    private org.springframework.beans.factory.ObjectProvider<InstanceService> providerOf(boolean withInstanceService) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (withInstanceService) {
            factory.addBean("instanceService", instances);
        }
        return factory.getBeanProvider(InstanceService.class);
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
        snapshot.setFields(List.of(days));
        SnapshotJson json = new SnapshotJson(new ObjectMapper());
        return new com.lambda.fusion.form.runtime.support.FormModelCompiler(json).compile(json.write(snapshot), 1L);
    }
}
