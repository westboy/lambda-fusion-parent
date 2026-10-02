package com.lambda.fusion.form.runtime.interceptor;

import com.lambda.fusion.form.runtime.support.FormAction;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 固定通用端点的操作类型，具体权限码在请求期由 formKey 解析。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FormPermission {
    FormAction value();
}
