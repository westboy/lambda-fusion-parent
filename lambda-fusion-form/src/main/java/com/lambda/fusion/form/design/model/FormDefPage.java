package com.lambda.fusion.form.design.model;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.lambda.fusion.core.pagination.PageQuery;
import com.lambda.fusion.form.design.entity.FormDefEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 表单定义分页查询
 *
 * @author Zx
 */
@Getter
@Setter
public class FormDefPage extends PageQuery<FormDefEntity> {

    /** 表单标识（模糊） */
    private String formKey;

    /** 表单名称（模糊） */
    private String formName;

    /** 表单类型（等值） */
    private String formType;

    /** 状态（等值：DRAFT/PUBLISHED/DISABLED） */
    private String status;

    @Override
    public LambdaQueryWrapper<FormDefEntity> getLambdaQueryWrapper() {
        LambdaQueryWrapper<FormDefEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.isNotBlank(formKey), FormDefEntity::getFormKey, formKey);
        wrapper.like(StringUtils.isNotBlank(formName), FormDefEntity::getFormName, formName);
        wrapper.eq(StringUtils.isNotBlank(formType), FormDefEntity::getFormType, formType);
        wrapper.eq(StringUtils.isNotBlank(status), FormDefEntity::getStatus, status);
        wrapper.orderByAsc(FormDefEntity::getCreatedAt);
        return wrapper;
    }
}
