package com.lambda.fusion.form.runtime.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.FormProperties;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 已发布表单模型读取（核对状态与版本 → 复用该版本的编译结果）
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
    private final FormProperties properties;

    /**
     * 读取当前租户下指定表单的生效模型
     *
     * @param formKey 表单标识
     */
    public FormModel require(String formKey) {
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
        if (!properties.allowsTable(def.getTableName())) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "表未开放动态读写：" + def.getTableName());
        }
        FormModel cached = cache.get(def.getId(), def.getPublishedVersion());
        if (cached != null) {
            return cached;
        }
        FormDefVersionEntity version = formDefVersionMapper.selectOne(new LambdaQueryWrapper<FormDefVersionEntity>()
                .eq(FormDefVersionEntity::getFormId, def.getId())
                .eq(FormDefVersionEntity::getVersion, def.getPublishedVersion()));
        if (version == null) {
            throw new FormBusinessException(FormErrorCode.FORM_VERSION_NOT_FOUND);
        }
        FormModel model = compiler.compile(version.getSnapshotJson(), version.getVersion());
        if (!properties.allowsTable(model.tableName())) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "表未开放动态读写：" + model.tableName());
        }
        cache.put(def.getId(), model);
        return model;
    }

    /**
     * 事务成功提交后清除编译结果；状态与版本正确性由 require 的数据库核对保证。
     */
    public void evictAfterCommit(String formId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.evict(formId);
                }
            });
        } else {
            cache.evict(formId);
        }
    }
}
