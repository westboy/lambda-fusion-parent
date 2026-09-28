package com.lambda.fusion.form.runtime.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.entity.FormDefVersionEntity;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.design.mapper.FormDefVersionMapper;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 已发布表单模型读取（缓存 → 库）
 *
 * <p>草稿不对外生效；停用拒绝访问；版本以 {@code published_version} 指针为准。
 * 租户条件由框架租户插件追加。
 *
 * @author Zx
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class FormModelProvider {

    private final FormDefMapper formDefMapper;
    private final FormDefVersionMapper formDefVersionMapper;
    private final FormModelCompiler compiler;
    private final FormModelCache cache;

    /**
     * 读取当前租户下指定表单的生效模型
     *
     * @param formKey 表单标识
     */
    public FormModel require(String formKey) {
        String tenantId = AuthUtils.getTenantId();
        FormModel cached = cache.get(tenantId, formKey);
        if (cached != null) {
            return cached;
        }
        FormModel model = load(formKey);
        cache.put(tenantId, formKey, model);
        return model;
    }

    /**
     * 从库加载并编译生效快照（跳过缓存）
     */
    private FormModel load(String formKey) {
        FormDefEntity def =
                formDefMapper.selectOne(new LambdaQueryWrapper<FormDefEntity>().eq(FormDefEntity::getFormKey, formKey));
        if (def == null) {
            throw new FormBusinessException(FormErrorCode.FORM_NOT_FOUND, formKey);
        }
        if (FormConstants.STATUS_DISABLED.equals(def.getStatus())) {
            throw new FormBusinessException(FormErrorCode.FORM_DISABLED, formKey);
        }
        if (def.getPublishedVersion() == null) {
            throw new FormBusinessException(FormErrorCode.FORM_NOT_PUBLISHED, formKey);
        }
        FormDefVersionEntity version = formDefVersionMapper.selectOne(new LambdaQueryWrapper<FormDefVersionEntity>()
                .eq(FormDefVersionEntity::getFormId, def.getId())
                .eq(FormDefVersionEntity::getVersion, def.getPublishedVersion()));
        if (version == null) {
            throw new FormBusinessException(FormErrorCode.FORM_VERSION_NOT_FOUND);
        }
        return compiler.compile(version.getSnapshotJson(), version.getVersion());
    }

    /**
     * 清除指定表单缓存（发布/停用/回滚后调用）
     */
    public void evict(String formKey) {
        cache.evict(AuthUtils.getTenantId(), formKey);
    }
}
