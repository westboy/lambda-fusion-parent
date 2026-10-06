package com.lambda.fusion.form.design.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.workflow.integration.ProcessFormInfo;
import com.lambda.fusion.workflow.integration.ProcessFormSource;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * 流程表单数据源：la_form_def 中「流程表单标识开启且已发布」的动态表单。
 * 实现 workflow 模块的 SPI，供 warm-flow 设计器 form_path 下拉与流程定义发布校验；
 * 值语义为 formKey（落 flow_definition.form_path，办理端按 formKey 渲染动态表单）。
 *
 * @author Zx
 */
@RequiredArgsConstructor
public class WorkflowProcessFormSource implements ProcessFormSource {

    private final FormDefMapper forms;

    @Override
    public List<ProcessFormInfo> listMountable() {
        return forms.selectList(processFormWrapper().orderByAsc(FormDefEntity::getFormKey)).stream()
                .map(form -> new ProcessFormInfo(form.getFormKey(), form.getFormName()))
                .toList();
    }

    @Override
    public boolean mountable(String formKey) {
        return forms.selectCount(processFormWrapper().eq(FormDefEntity::getFormKey, formKey)) > 0;
    }

    private LambdaQueryWrapper<FormDefEntity> processFormWrapper() {
        return new LambdaQueryWrapper<FormDefEntity>()
                .eq(FormDefEntity::getProcessForm, Boolean.TRUE)
                .eq(FormDefEntity::getStatus, FormConstants.STATUS_PUBLISHED);
    }
}
