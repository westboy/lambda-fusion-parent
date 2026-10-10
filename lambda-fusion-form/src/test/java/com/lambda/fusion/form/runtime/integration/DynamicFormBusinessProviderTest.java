package com.lambda.fusion.form.runtime.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.service.DynamicDataService;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.workflow.integration.BizInfo;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 待办标题解析的批量口径：模型核对与行读取按批一次完成，不逐行装配（N+1 回归守门）。 */
class DynamicFormBusinessProviderTest {

    private final FormDefMapper forms = mock(FormDefMapper.class);
    private final FormModelProvider modelProvider = mock(FormModelProvider.class);
    private final DynamicDataService dataService = mock(DynamicDataService.class);
    private final DynamicFormBusinessProvider provider =
            new DynamicFormBusinessProvider(forms, modelProvider, dataService);

    @Test
    void resolveLoadsModelOnceAndBatchFetchesOnlyTemplateFields() {
        FormDefEntity def = new FormDefEntity();
        def.setFormKey("leave");
        def.setFormName("请假");
        def.setTitleTemplate("{personName}的请假单");
        when(forms.selectOne(any())).thenReturn(def);
        FormModel model = mock(FormModel.class);
        when(model.withoutDataScope()).thenReturn(model);
        when(modelProvider.require("leave")).thenReturn(model);
        when(dataService.findRows(eq(model), eq(List.of("1", "2", "3")), eq(Set.of("personName"))))
                .thenReturn(Map.of(
                        "1", Map.of("id", "1", "personName", "张三"),
                        "2", Map.of("id", "2", "personName", "李四")));

        Map<String, BizInfo> result = provider.resolve("leave", List.of("1", "2", "3"));

        // 行 3 已被删（不在批量结果中）：静默跳过；模板按字段值渲染
        assertThat(result).containsOnlyKeys("1", "2");
        assertThat(result.get("1").title()).isEqualTo("张三的请假单");
        assertThat(result.get("2").title()).isEqualTo("李四的请假单");
        verify(modelProvider, times(1)).require("leave");
        verify(dataService, times(1)).findRows(any(), any(), any());
        verify(dataService, never()).getById(any(), any());
    }

    @Test
    void resolveFallsBackToFormNameWhenTemplateBlankAndSkipsBatchOnMissingModel() {
        FormDefEntity def = new FormDefEntity();
        def.setFormKey("leave");
        def.setFormName("请假");
        when(forms.selectOne(any())).thenReturn(def);
        FormModel model = mock(FormModel.class);
        when(model.withoutDataScope()).thenReturn(model);
        when(modelProvider.require("leave")).thenReturn(model);
        when(dataService.findRows(eq(model), eq(List.of("1")), eq(Set.of())))
                .thenReturn(Map.of("1", Map.of("id", "1")));
        // 未配置标题模板：退化为表单名（无占位符，引用字段集为空）
        assertThat(provider.resolve("leave", List.of("1")).get("1").title()).isEqualTo("请假");

        when(modelProvider.require("leave")).thenThrow(new FormBusinessException(FormErrorCode.FORM_DISABLED, "leave"));
        assertThat(provider.resolve("leave", List.of("1"))).isEmpty();
        verify(dataService, times(1)).findRows(any(), any(), any());
    }

    @Test
    void resolveReturnsEmptyWhenFormDefinitionMissing() {
        when(forms.selectOne(any())).thenReturn(null);
        assertThat(provider.resolve("ghost", List.of("1"))).isEmpty();
        verifyNoInteractions(modelProvider, dataService);
    }

    private static void verifyNoInteractions(FormModelProvider models, DynamicDataService data) {
        org.mockito.Mockito.verifyNoInteractions(models);
        org.mockito.Mockito.verifyNoInteractions(data);
    }
}
