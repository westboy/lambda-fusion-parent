package com.lambda.fusion.form.runtime.service.impl;

import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.assembler.FormSchemaAssembler;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import com.lambda.fusion.form.runtime.model.FormDataPageView;
import com.lambda.fusion.form.runtime.model.FormSchemaView;
import com.lambda.fusion.form.runtime.service.DynamicDataService;
import com.lambda.fusion.form.runtime.service.FormRuntimeService;
import com.lambda.fusion.form.runtime.support.FormAction;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.workflow.instance.model.StartCommand;
import com.lambda.fusion.workflow.instance.service.InstanceService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 动态表单运行时编排实现
 *
 * <p>权限校验在 Controller 层完成；本类负责模型加载、Schema 组装与数据读写委派。
 *
 * @author Zx
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class FormRuntimeServiceImpl implements FormRuntimeService {

    private final FormModelProvider modelProvider;
    private final DynamicDataService dynamicDataService;
    private final FormSchemaAssembler schemaAssembler;
    private final ObjectProvider<InstanceService> instanceServices;

    @Override
    public FormSchemaView schema(String formKey) {
        FormModel model = modelProvider.require(formKey);
        FormSchemaView view = schemaAssembler.assemble(model);
        for (FormAction action : FormAction.values()) {
            view.getPermissions().put(action.getCode(), AuthUtils.hasPermission(action.permission(formKey)));
        }
        return view;
    }

    @Override
    public FormDataPageView page(String formKey, FormDataPageQuery query) {
        return dynamicDataService.page(modelProvider.require(formKey), query);
    }

    @Override
    public void export(String formKey, FormDataPageQuery query, java.io.Writer writer) throws java.io.IOException {
        dynamicDataService.export(modelProvider.require(formKey), query, writer);
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
        dynamicDataService.createSubRow(modelProvider.require(formKey), id, subFormKey, data);
    }

    @Override
    public Map<String, Object> getById(String formKey, String id) {
        return dynamicDataService.getById(modelProvider.require(formKey), id);
    }

    @Override
    public String create(String formKey, Map<String, Object> data) {
        return dynamicDataService.create(modelProvider.require(formKey), data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String submit(String formKey, String flowCode, Map<String, Object> data) {
        InstanceService instanceService = instanceServices.getIfAvailable();
        if (instanceService == null) {
            throw new FormBusinessException(FormErrorCode.FORM_WORKFLOW_UNAVAILABLE);
        }
        // 同一事务：业务行落库 + 流程实例发起，start 失败随事务回滚不留孤行。
        // 业务行 id 作流程 business_id；bizType=formKey 供待办标题解析路由；行数据作流程变量供条件分支。
        String businessId = create(formKey, data);
        StartCommand command = new StartCommand();
        command.setFlowCode(flowCode);
        command.setBusinessId(businessId);
        command.setBizType(formKey);
        command.setVariable(new HashMap<>(data));
        instanceService.start(command);
        return businessId;
    }

    @Override
    public void update(String formKey, String id, Map<String, Object> data) {
        dynamicDataService.update(modelProvider.require(formKey), id, data);
    }

    @Override
    public void delete(String formKey, String id) {
        dynamicDataService.delete(modelProvider.require(formKey), id);
    }
}
