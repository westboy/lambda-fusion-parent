package com.lambda.fusion.form.runtime.integration;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@link FormDataInterceptor} 按 bean 名解析（空名/未注册/类型不符均视为未配置，空安全）
 *
 * @author Zx
 */
@Component
@RequiredArgsConstructor
public class FormDataInterceptors {

    private final ApplicationContext applicationContext;

    /** 按 bean 名解析拦截器 */
    public Optional<FormDataInterceptor> resolve(String beanName) {
        if (beanName == null || beanName.isBlank() || !applicationContext.containsBean(beanName)) {
            return Optional.empty();
        }
        Object bean = applicationContext.getBean(beanName);
        return bean instanceof FormDataInterceptor interceptor ? Optional.of(interceptor) : Optional.empty();
    }

    /** 设计器下拉：全部已注册拦截器（bean 名 + 展示名） */
    public List<InterceptorOption> listAvailable() {
        return Arrays.stream(applicationContext.getBeanNamesForType(FormDataInterceptor.class))
                .sorted()
                .map(name -> new InterceptorOption(
                        name,
                        applicationContext
                                .getBean(name, FormDataInterceptor.class)
                                .getName()))
                .toList();
    }

    /**
     * 拦截器选项
     *
     * @param bean Spring bean 名（保存进表单定义）
     * @param name 展示名
     */
    public record InterceptorOption(String bean, String name) {}
}
