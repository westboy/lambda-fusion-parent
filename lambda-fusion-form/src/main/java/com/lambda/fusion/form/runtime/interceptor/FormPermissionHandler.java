package com.lambda.fusion.form.runtime.interceptor;

import cn.dev33.satoken.annotation.handler.SaAnnotationHandlerInterface;
import com.lambda.fusion.core.utils.AuthUtils;
import java.lang.reflect.AnnotatedElement;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

/** 由 SaBeanInject 注册进统一注解鉴权链，沿用 Cloud 登录校验与 ROLE_DEV 豁免。 */
@Component
public class FormPermissionHandler implements SaAnnotationHandlerInterface<FormPermission> {
    @Override
    public Class<FormPermission> getHandlerAnnotationClass() {
        return FormPermission.class;
    }

    @Override
    public void checkMethod(FormPermission annotation, AnnotatedElement element) {
        var attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        Object variables = attributes.getRequest().getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Object formKey = variables instanceof Map<?, ?> map ? map.get("formKey") : null;
        AuthUtils.checkPermission(annotation.value().permission(formKey instanceof String key ? key : null));
    }
}
