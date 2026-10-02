package com.lambda.fusion.form.runtime.provider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.cloud.mybatis.tenant.TenantContextHolder;
import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.runtime.interceptor.FormPermission;
import com.lambda.fusion.permission.model.ApiPermissionMetadata;
import com.lambda.fusion.permission.service.ApiPermissionProvider;
import io.swagger.v3.oas.annotations.Operation;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** 从当前租户已发布定义及实际 MVC 映射派生接口目录；停用保留目录，重启无需重注册。 */
@RequiredArgsConstructor
public class FormApiPermissionProvider implements ApiPermissionProvider {
    private final FormDefMapper forms;
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    private final String application;

    @Override
    public List<ApiPermissionMetadata> getApis() {
        // 不将缺少租户上下文的启动/后台目录读取扩展为全租户定义扫描。
        if (TenantContextHolder.getCurrentTenantId() == null) {
            return List.of();
        }
        List<FormDefEntity> definitions =
                forms.selectList(new LambdaQueryWrapper<FormDefEntity>().isNotNull(FormDefEntity::getPublishedVersion));
        List<ApiPermissionMetadata> result = new ArrayList<>();
        mappings.orderedStream().forEach(mapping -> mapping.getHandlerMethods().forEach((info, handler) -> {
            FormPermission permission = handler.getMethodAnnotation(FormPermission.class);
            if (permission == null) return;
            Operation operation = handler.getMethodAnnotation(Operation.class);
            for (FormDefEntity form : definitions) {
                for (String pattern : info.getPatternValues()) {
                    if (!pattern.contains("{formKey}")) continue;
                    info.getMethodsCondition().getMethods().forEach(method -> {
                        ApiPermissionMetadata api = new ApiPermissionMetadata();
                        api.setApplication(application);
                        api.setModule("lambda-fusion-form");
                        api.setGroup("动态表单 / " + form.getFormName());
                        api.setDescription(form.getFormName() + " · "
                                + (operation == null ? permission.value().getLabel() : operation.summary()));
                        api.setController(handler.getBeanType().getName());
                        api.setMethodName(handler.getMethod().getName());
                        api.setMethod(method.name());
                        api.setPath(pattern.replace("{formKey}", form.getFormKey()));
                        api.setPermissions(List.of(permission.value().permission(form.getFormKey())));
                        result.add(api);
                    });
                }
            }
        }));
        return List.copyOf(result);
    }
}
