package com.lambda.fusion.form.design.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.form.FormConstants;
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

    @Override
    public Map<String, FormRouteInfo> states(List<String> ids) {
        if (ids == null || ids.size() > 500) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "最多查询 500 个表单的挂载状态");
        }
        if (ids.isEmpty()) return Map.of();
        List<FormDefEntity> definitions =
                forms.selectList(new LambdaQueryWrapper<FormDefEntity>().in(FormDefEntity::getId, ids));
        if (definitions.size() != ids.stream().distinct().count()) {
            throw new FormBusinessException(
                    FormErrorCode.FORM_NOT_FOUND,
                    "部分表单不存在：" + ids.stream().distinct().toList());
        }
        FormRouteGateway gateway = gateways.getIfAvailable();
        Map<String, FormRouteInfo> result = new LinkedHashMap<>();
        definitions.forEach(form -> result.put(form.getId(), read(form, gateway)));
        return result;
    }

    @Override
    public FormRouteInfo detail(String id) {
        FormDefEntity form = forms.selectById(id);
        if (form == null) throw new FormBusinessException(FormErrorCode.FORM_NOT_FOUND, id);
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
        form.setRouteResourceId(gateway.save(form, command));
        form.setUpdatedAt(null);
        form.setUpdatedBy(null);
        if (forms.updateById(form) == 0) throw new FormBusinessException(FormErrorCode.FORM_DRAFT_CONFLICT);
        return gateway.read(form);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(String id) {
        FormRouteGateway gateway = requireGateway();
        FormDefEntity form = requireLocked(id);
        gateway.remove(form);
        form.setRouteResourceId(null);
        form.setUpdatedAt(null);
        form.setUpdatedBy(null);
        if (forms.updateById(form) == 0) throw new FormBusinessException(FormErrorCode.FORM_DRAFT_CONFLICT);
    }

    private FormDefEntity requireLocked(String id) {
        FormDefEntity form = forms.selectOne(new LambdaQueryWrapper<FormDefEntity>()
                .eq(FormDefEntity::getId, id)
                .last("FOR UPDATE"));
        if (form == null) throw new FormBusinessException(FormErrorCode.FORM_NOT_FOUND, id);
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
