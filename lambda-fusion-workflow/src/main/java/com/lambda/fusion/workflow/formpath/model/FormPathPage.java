package com.lambda.fusion.workflow.formpath.model;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.lambda.fusion.core.pagination.PageQuery;
import lombok.Getter;
import lombok.Setter;

/**
 * 表单注册表分页查询
 *
 * @author jin
 */
@Getter
@Setter
public class FormPathPage extends PageQuery<FormPathEntity> {

    /** 表单名称（模糊） */
    private String formName;

    /** 表单路径（模糊） */
    private String formPath;

    /** 启停（等值：1 启用 0 停用） */
    private Integer enabled;

    @Override
    public LambdaQueryWrapper<FormPathEntity> getLambdaQueryWrapper() {
        LambdaQueryWrapper<FormPathEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.isNotBlank(formName), FormPathEntity::getFormName, formName);
        wrapper.like(StringUtils.isNotBlank(formPath), FormPathEntity::getFormPath, formPath);
        wrapper.eq(enabled != null, FormPathEntity::getEnabled, enabled);
        wrapper.orderByAsc(FormPathEntity::getCreatedAt);
        return wrapper;
    }
}
