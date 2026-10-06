package com.lambda.fusion.form.runtime.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.runtime.service.DynamicDataService;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.workflow.integration.BizInfo;
import com.lambda.fusion.workflow.integration.WorkflowBusinessProvider;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 动态表单待办标题解析：bizType=流程表单 formKey（发起编排约定），
 * 按 la_form_def.TITLE_TEMPLATE 渲染（{字段标识} 占位替换为行值；未配置时退化为表单名）
 *
 * <p>实现 workflow 模块 SPI，经 FormConfigure 条件装配（workflow 缺席时不注册）。
 * 解析失败的单据静默跳过（标题空，列表照常返回）。
 *
 * @author Zx
 */
@Slf4j
@SuppressFBWarnings("EI_EXPOSE_REP2")
@RequiredArgsConstructor
public class DynamicFormBusinessProvider implements WorkflowBusinessProvider {

    /** 模板占位符：行字段标识（如 {applicant}） */
    private static final String PLACEHOLDER = "\\{(\\w+)}";

    private final FormDefMapper forms;
    private final FormModelProvider modelProvider;
    private final DynamicDataService dynamicDataService;

    @Override
    public Set<String> supportedTypes() {
        return new LinkedHashSet<>(forms
                .selectList(new LambdaQueryWrapper<FormDefEntity>()
                        .eq(FormDefEntity::getProcessForm, Boolean.TRUE)
                        .eq(FormDefEntity::getStatus, FormConstants.STATUS_PUBLISHED)
                        .select(FormDefEntity::getFormKey))
                .stream()
                .map(FormDefEntity::getFormKey)
                .toList());
    }

    @Override
    public Map<String, BizInfo> resolve(String bizType, Collection<String> businessIds) {
        Map<String, BizInfo> result = new HashMap<>();
        FormDefEntity def = forms.selectOne(new LambdaQueryWrapper<FormDefEntity>()
                .eq(FormDefEntity::getFormKey, bizType)
                .select(FormDefEntity::getFormName, FormDefEntity::getTitleTemplate));
        String template = def == null
                        || def.getTitleTemplate() == null
                        || def.getTitleTemplate().isBlank()
                ? def == null ? null : def.getFormName()
                : def.getTitleTemplate();
        if (template == null) {
            return result;
        }
        for (String businessId : businessIds) {
            try {
                Map<String, Object> row = dynamicDataService.getById(modelProvider.require(bizType), businessId);
                result.put(businessId, new BizInfo(render(template, row), null));
            } catch (Exception e) {
                log.warn("流程表单标题解析失败，跳过：formKey={} businessId={} - {}", bizType, businessId, e.getMessage());
            }
        }
        return result;
    }

    /** 模板渲染：{字段标识} 替换为行值，未命中字段原样保留 */
    private String render(String template, Map<String, Object> row) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile(PLACEHOLDER).matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            Object value = row.get(matcher.group(1));
            matcher.appendReplacement(
                    rendered, java.util.regex.Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
