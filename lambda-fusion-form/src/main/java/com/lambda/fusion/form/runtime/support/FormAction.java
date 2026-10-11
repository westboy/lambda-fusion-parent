package com.lambda.fusion.form.runtime.support;

import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 动态表单接口校验、接口目录和操作资源共用的权限语义。 */
@Getter
@RequiredArgsConstructor
public enum FormAction {
    PAGE("page", "查看"),
    ADD("add", "新增"),
    EDIT("edit", "编辑"),
    DELETE("delete", "删除"),
    EXPORT("export", "导出"),
    IMPORT("import", "导入");

    private final String code;
    private final String label;

    public String permission(String formKey) {
        if (!FormConstants.isFormKey(formKey)) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "表单标识非法");
        }
        return String.format(FormConstants.RUNTIME_PERMISSION_TEMPLATE, formKey, code);
    }

    /** 按钮标识与接口权限分开，避免按钮授权绕过 la_api_resources 的解绑。 */
    public String resourceCode(String formKey) {
        if (!FormConstants.isFormKey(formKey)) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "表单标识非法");
        }
        return "form-ui:" + formKey + ":" + code;
    }
}
