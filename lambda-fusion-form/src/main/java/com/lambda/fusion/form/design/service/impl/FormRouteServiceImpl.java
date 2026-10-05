package com.lambda.fusion.form.design.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.design.adapter.FormPathRegistry;
import com.lambda.fusion.form.design.adapter.FormRouteGateway;
import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.design.model.FormRouteCommand;
import com.lambda.fusion.form.design.model.FormRouteInfo;
import com.lambda.fusion.form.design.service.FormRouteService;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FormRouteServiceImpl implements FormRouteService {
    private final FormDefMapper forms;
    private final ObjectProvider<FormRouteGateway> gateways;
    private final ObjectProvider<FormPathRegistry> formPathRegistries;

    @Override
    public Map<String, FormRouteInfo> states(List<String> ids) {
        if (ids == null || ids.size() > 500) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "最多查询 500 个表单的挂载状态");
        }
        if (ids.isEmpty()) return Map.of();
        List<FormDefEntity> definitions =
                forms.selectList(new LambdaQueryWrapper<FormDefEntity>().in(FormDefEntity::getId, ids));
        if (definitions.size() != ids.stream().distinct().count()) {
            throw new FormBusinessException(FormErrorCode.FORM_NOT_FOUND);
        }
        FormRouteGateway gateway = gateways.getIfAvailable();
        Map<String, FormRouteInfo> result = new LinkedHashMap<>();
        definitions.forEach(form -> result.put(form.getId(), read(form, gateway)));
        return result;
    }

    @Override
    public FormRouteInfo detail(String id) {
        FormDefEntity form = forms.selectById(id);
        if (form == null) throw new FormBusinessException(FormErrorCode.FORM_NOT_FOUND);
        return read(form, gateways.getIfAvailable());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FormRouteInfo save(String id, FormRouteCommand command) {
        FormRouteGateway gateway = requireGateway();
        FormDefEntity form = requireLocked(id);
        if (form.getPublishedVersion() == null) throw new FormBusinessException(FormErrorCode.FORM_NOT_PUBLISHED);
        FormRouteInfo current = gateway.read(form);
        if (!current.mounted() && !FormConstants.STATUS_PUBLISHED.equals(form.getStatus())) {
            throw new FormBusinessException(FormErrorCode.FORM_DISABLED);
        }
        String previousPath = current.mounted() ? current.path() : null;
        form.setRouteResourceId(gateway.save(form, command));
        if (forms.updateById(form) == 0) throw new FormBusinessException(FormErrorCode.FORM_DRAFT_CONFLICT);
        // 同事务登记流程表单注册表：warm-flow 设计器「自定义表单路径」下拉即可选到本表单；
        // 换路径重挂时先注销旧路径，注册表不残留悬空记录
        formPathRegistries.ifAvailable(registry -> {
            if (previousPath != null && !previousPath.equals(command.path())) {
                registry.unregister(previousPath);
            }
            registry.register(form.getFormName(), command.path(), "动态表单自动注册（formKey=" + form.getFormKey() + "）");
        });
        return gateway.read(form);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(String id) {
        FormRouteGateway gateway = requireGateway();
        FormDefEntity form = requireLocked(id);
        String routePath = gateway.read(form).path();
        gateway.remove(form);
        form.setRouteResourceId(null);
        if (forms.updateById(form) == 0) throw new FormBusinessException(FormErrorCode.FORM_DRAFT_CONFLICT);
        // 撤路由同步注销注册表，避免 warm-flow 下拉残留悬空路径
        if (routePath != null) {
            formPathRegistries.ifAvailable(registry -> registry.unregister(routePath));
        }
    }

    private FormDefEntity requireLocked(String id) {
        FormDefEntity form = forms.selectOne(new LambdaQueryWrapper<FormDefEntity>()
                .eq(FormDefEntity::getId, id)
                .last("FOR UPDATE"));
        if (form == null) throw new FormBusinessException(FormErrorCode.FORM_NOT_FOUND);
        return form;
    }

    private FormRouteGateway requireGateway() {
        FormRouteGateway gateway = gateways.getIfAvailable();
        if (gateway == null) throw new FormBusinessException(FormErrorCode.FORM_ROUTE_UNAVAILABLE);
        return gateway;
    }

    private FormRouteInfo read(FormDefEntity form, FormRouteGateway gateway) {
        return gateway == null
                ? new FormRouteInfo(false, false, null, null, null, null, null, null, List.of())
                : gateway.read(form);
    }
}
