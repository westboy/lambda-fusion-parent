package com.lambda.fusion.form.runtime.integration;

import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.service.DynamicDataService;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.workflow.integration.TaskFormHandler;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

/**
 * 动态表单任务随单写入桥：实现 workflow 模块的 {@link TaskFormHandler} SPI
 *
 * <p>办理「通过」携带 formData 时按 formKey + nodeCode 解析节点填写白名单
 * （主表落库字段且 nodePerms[nodeCode].editable=true），提交键必须全部落在白名单内
 * （多余键显式拒绝，防止 schema 与白名单漂移时静默丢数据），校验后复用通用运行时
 * update 回写同一业务行。装配由 FormConfigure 按 workflow 类路径条件守护。
 *
 * @author Zx
 */
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class DynamicFormTaskFormHandler implements TaskFormHandler {

    private final FormModelProvider modelProvider;
    private final DynamicDataService dynamicDataService;

    @Override
    public Map<String, Object> writeTaskFormData(
            String formKey, String businessId, String nodeCode, Map<String, Object> formData) {
        if (!StringUtils.hasText(nodeCode) || formData == null || formData.isEmpty()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "缺少节点编码或表单数据");
        }
        FormModel model = modelProvider.require(formKey);
        Set<String> fillable = fillableFields(model, nodeCode);
        if (fillable.isEmpty()) {
            throw new FormBusinessException(FormErrorCode.FORM_TASK_FIELD_FORBIDDEN, nodeCode + "（节点未配置可填写字段）");
        }
        for (String key : formData.keySet()) {
            if (!fillable.contains(key)) {
                throw new FormBusinessException(FormErrorCode.FORM_TASK_FIELD_FORBIDDEN, key);
            }
        }
        dynamicDataService.update(model, businessId, formData);
        return new LinkedHashMap<>(formData);
    }

    /** 节点填写白名单：主表、画布可见（formShow）、落库字段，且该节点 editable=true */
    private Set<String> fillableFields(FormModel model, String nodeCode) {
        return model.fields().stream()
                .filter(field -> field.subFormKey() == null
                        && field.persisted()
                        && field.form().show())
                .filter(field -> {
                    FormModel.NodeConf conf = field.form().nodeConf(nodeCode);
                    return conf != null && conf.editable();
                })
                .map(FormModel.FormField::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
