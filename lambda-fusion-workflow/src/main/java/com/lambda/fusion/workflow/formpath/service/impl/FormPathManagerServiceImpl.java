package com.lambda.fusion.workflow.formpath.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.workflow.exception.WorkflowBusinessException;
import com.lambda.fusion.workflow.exception.WorkflowErrorCode;
import com.lambda.fusion.workflow.formpath.mapper.FormPathMapper;
import com.lambda.fusion.workflow.formpath.model.FormPathEntity;
import com.lambda.fusion.workflow.formpath.model.FormPathPage;
import com.lambda.fusion.workflow.formpath.model.FormPathSaveCommand;
import com.lambda.fusion.workflow.formpath.service.FormPathManagerService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 表单注册表管理服务实现
 *
 * <p>租户过滤与审计填充由框架拦截器/填充器承担，本类不重复处理。
 * form_path 唯一性按租户内校验（uk 约束含 tenant_id 兜底并发）。
 *
 * @author jin
 */
@Service
@RequiredArgsConstructor
public class FormPathManagerServiceImpl implements FormPathManagerService {

    private static final int ENABLED = 1;
    private static final int DISABLED = 0;

    private final FormPathMapper formPathMapper;

    @Override
    public PageView<FormPathEntity> page(FormPathPage page) {
        return PageView.of(formPathMapper.selectPage(page.getPage(), page.getLambdaQueryWrapper()));
    }

    @Override
    public List<FormPathEntity> listEnabled() {
        LambdaQueryWrapper<FormPathEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FormPathEntity::getEnabled, ENABLED);
        wrapper.orderByAsc(FormPathEntity::getCreatedAt);
        return formPathMapper.selectList(wrapper);
    }

    @Override
    public void save(FormPathSaveCommand command) {
        requirePathFree(command.getFormPath(), null);
        FormPathEntity entity = new FormPathEntity();
        entity.setFormName(command.getFormName());
        entity.setFormPath(command.getFormPath());
        entity.setRemarks(command.getRemarks());
        entity.setEnabled(ENABLED);
        formPathMapper.insert(entity);
    }

    @Override
    public void update(String id, FormPathSaveCommand command) {
        FormPathEntity entity = requireExists(id);
        requirePathFree(command.getFormPath(), id);
        entity.setFormName(command.getFormName());
        entity.setFormPath(command.getFormPath());
        entity.setRemarks(command.getRemarks());
        formPathMapper.updateById(entity);
    }

    @Override
    public void toggle(String id, boolean enabled) {
        FormPathEntity entity = requireExists(id);
        entity.setEnabled(enabled ? ENABLED : DISABLED);
        formPathMapper.updateById(entity);
    }

    @Override
    public void remove(String id) {
        requireExists(id);
        formPathMapper.deleteById(id);
    }

    @Override
    public void upsert(String formName, String formPath, String remarks) {
        if (StringUtils.isBlank(formPath)) {
            return;
        }
        FormPathEntity existing =
                formPathMapper
                        .selectList(new LambdaQueryWrapper<FormPathEntity>().eq(FormPathEntity::getFormPath, formPath))
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (existing == null) {
            FormPathEntity entity = new FormPathEntity();
            entity.setFormName(formName);
            entity.setFormPath(formPath);
            entity.setRemarks(remarks);
            entity.setEnabled(ENABLED);
            formPathMapper.insert(entity);
            return;
        }
        existing.setFormName(formName);
        existing.setRemarks(remarks);
        existing.setEnabled(ENABLED);
        formPathMapper.updateById(existing);
    }

    @Override
    public void removeByPath(String formPath) {
        if (StringUtils.isBlank(formPath)) {
            return;
        }
        formPathMapper.delete(new LambdaQueryWrapper<FormPathEntity>().eq(FormPathEntity::getFormPath, formPath));
    }

    private FormPathEntity requireExists(String id) {
        FormPathEntity entity = formPathMapper.selectById(id);
        if (entity == null) {
            throw new WorkflowBusinessException(WorkflowErrorCode.FORM_PATH_NOT_FOUND);
        }
        return entity;
    }

    private void requirePathFree(String formPath, String excludeId) {
        if (StringUtils.isBlank(formPath)) {
            return;
        }
        LambdaQueryWrapper<FormPathEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FormPathEntity::getFormPath, formPath);
        boolean occupied = formPathMapper.selectList(wrapper).stream()
                .anyMatch(item -> !item.getId().equals(excludeId));
        if (occupied) {
            throw new WorkflowBusinessException(WorkflowErrorCode.FORM_PATH_DUPLICATED);
        }
    }
}
