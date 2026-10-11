package com.lambda.fusion.form.runtime.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.entity.FormDefVersionEntity;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.design.mapper.FormDefVersionMapper;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.assembler.FormSchemaAssembler;
import com.lambda.fusion.form.runtime.integration.ProcessFormGuard;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import com.lambda.fusion.form.runtime.model.FormDataPageView;
import com.lambda.fusion.form.runtime.model.FormSchemaView;
import com.lambda.fusion.form.runtime.service.DynamicDataService;
import com.lambda.fusion.form.runtime.service.FormRuntimeService;
import com.lambda.fusion.form.runtime.support.FormAction;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModelCompiler;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 动态表单运行时编排实现
 *
 * <p>权限校验在 Controller 层完成；本类负责模型加载、Schema 组装与数据读写委派。
 *
 * @author Zx
 */
@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class FormRuntimeServiceImpl implements FormRuntimeService {

    private final FormModelProvider modelProvider;
    private final DynamicDataService dynamicDataService;
    private final FormSchemaAssembler schemaAssembler;
    private final FormDefMapper formDefMapper;
    private final FormDefVersionMapper formDefVersionMapper;
    private final FormModelCompiler modelCompiler;
    /** 流程表单数据锁（workflow 在场时装配；缺席不锁） */
    private final ObjectProvider<ProcessFormGuard> processFormGuards;

    @Override
    public FormSchemaView schema(String formKey) {
        return schema(formKey, null);
    }

    @Override
    public FormSchemaView schema(String formKey, Long version) {
        return schema(formKey, version, null);
    }

    @Override
    public FormSchemaView schema(String formKey, Long version, String nodeCode) {
        FormModel model = modelFor(formKey, version);
        FormSchemaView view = schemaAssembler.assemble(model, nodeCode);
        for (FormAction action : FormAction.values()) {
            view.getPermissions().put(action.getCode(), AuthUtils.hasPermission(action.permission(formKey)));
        }
        return view;
    }

    @Override
    public long liveVersion(String formKey) {
        return modelProvider.require(formKey).version();
    }

    /**
     * 渲染模型：version 为空 = 当前生效版（缓存）；非空 = 历史快照现场编译
     * （发布缓存只服务生效指针，历史版本不进缓存）。表单或版本缺失时按业务错误码拒绝。
     */
    private FormModel modelFor(String formKey, Long version) {
        if (version == null) {
            return modelProvider.require(formKey);
        }
        FormDefEntity def =
                formDefMapper.selectOne(new LambdaQueryWrapper<FormDefEntity>().eq(FormDefEntity::getFormKey, formKey));
        if (def == null) {
            throw new FormBusinessException(FormErrorCode.FORM_NOT_FOUND, formKey);
        }
        FormDefVersionEntity snapshot = formDefVersionMapper.selectOne(new LambdaQueryWrapper<FormDefVersionEntity>()
                .eq(FormDefVersionEntity::getFormId, def.getId())
                .eq(FormDefVersionEntity::getVersion, version));
        if (snapshot == null) {
            throw new FormBusinessException(FormErrorCode.FORM_VERSION_NOT_FOUND, formKey + " v" + version);
        }
        return modelCompiler.compile(snapshot.getSnapshotJson(), snapshot.getVersion());
    }

    @Override
    public FormDataPageView page(String formKey, FormDataPageQuery query) {
        return dynamicDataService.page(modelProvider.require(formKey), query);
    }

    @Override
    public void export(String formKey, FormDataPageQuery query, String format, java.io.OutputStream out)
            throws java.io.IOException {
        dynamicDataService.export(modelProvider.require(formKey), query, format, out);
    }

    @Override
    public int importCsv(String formKey, java.io.Reader reader) {
        return dynamicDataService.importCsv(modelProvider.require(formKey), reader);
    }

    @Override
    public int importExcel(String formKey, java.io.InputStream inputStream) {
        return dynamicDataService.importExcel(modelProvider.require(formKey), inputStream);
    }

    @Override
    public List<Map<String, Object>> tree(String formKey, String parentValue) {
        return dynamicDataService.tree(modelProvider.require(formKey), parentValue);
    }

    @Override
    public PageView<Map<String, Object>> pageSubRows(
            String formKey, String id, String subFormKey, long pageNum, long pageSize) {
        return dynamicDataService.pageSubRows(modelProvider.require(formKey), id, subFormKey, pageNum, pageSize);
    }

    @Override
    public void createSubRow(String formKey, String id, String subFormKey, Map<String, Object> data) {
        checkWritable(id);
        dynamicDataService.createSubRow(modelProvider.require(formKey), id, subFormKey, data);
    }

    @Override
    public void updateSubRow(String formKey, String id, String subFormKey, String subId, Map<String, Object> data) {
        checkWritable(id);
        dynamicDataService.updateSubRow(modelProvider.require(formKey), id, subFormKey, subId, data);
    }

    @Override
    public void deleteSubRow(String formKey, String id, String subFormKey, String subId) {
        checkWritable(id);
        dynamicDataService.deleteSubRow(modelProvider.require(formKey), id, subFormKey, subId);
    }

    @Override
    public Map<String, Object> getById(String formKey, String id) {
        return getById(formKey, id, null);
    }

    @Override
    public Map<String, Object> getById(String formKey, String id, Long version) {
        return dynamicDataService.getById(modelFor(formKey, version), id);
    }

    @Override
    public String create(String formKey, Map<String, Object> data) {
        return dynamicDataService.create(modelProvider.require(formKey), data);
    }

    @Override
    public void update(String formKey, String id, Map<String, Object> data) {
        checkWritable(id);
        dynamicDataService.update(modelProvider.require(formKey), id, data);
    }

    @Override
    public void delete(String formKey, String id) {
        checkWritable(id);
        dynamicDataService.delete(modelProvider.require(formKey), id);
    }

    /** 审批在途锁定：该业务行存在审批中实例时拒绝旁路写（节点级填写走任务随单写入，不经过本类） */
    private void checkWritable(String businessId) {
        ProcessFormGuard guard = processFormGuards.getIfAvailable();
        if (guard != null) {
            guard.checkWritable(businessId);
        }
    }
}
