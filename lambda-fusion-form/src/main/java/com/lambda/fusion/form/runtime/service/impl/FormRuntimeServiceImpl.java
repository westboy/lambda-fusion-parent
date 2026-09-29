package com.lambda.fusion.form.runtime.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import com.lambda.fusion.form.runtime.model.FormSchemaView;
import com.lambda.fusion.form.runtime.service.DynamicDataService;
import com.lambda.fusion.form.runtime.service.FormRuntimeService;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    @Override
    public FormSchemaView schema(String formKey) {
        FormModel model = modelProvider.require(formKey);
        FormSchemaView view = new FormSchemaView();
        view.setFormKey(model.formKey());
        view.setFormName(model.formName());
        view.setFormType(model.formType());
        view.setVersion(model.version());
        view.setLogicDeleteColumn(model.logicDeleteColumn());
        view.setListConfig(toListConfig(model.listConfig()));
        view.setTreeConfig(model.treeConfig());
        List<FormSchemaView.FieldSchema> fields = new ArrayList<>();
        model.fields().forEach(field -> fields.add(toFieldSchema(field)));
        view.setFields(fields);
        view.getPermissions().put("add", hasPermission(formKey, "add"));
        view.getPermissions().put("edit", hasPermission(formKey, "edit"));
        view.getPermissions().put("delete", hasPermission(formKey, "delete"));
        return view;
    }

    @Override
    public PageView<Map<String, Object>> page(String formKey, FormDataPageQuery query) {
        return dynamicDataService.page(modelProvider.require(formKey), query);
    }

    @Override
    public List<Map<String, Object>> tree(String formKey, String parentValue) {
        return dynamicDataService.tree(modelProvider.require(formKey), parentValue);
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
    public void update(String formKey, String id, Map<String, Object> data) {
        dynamicDataService.update(modelProvider.require(formKey), id, data);
    }

    @Override
    public void delete(String formKey, String id) {
        dynamicDataService.delete(modelProvider.require(formKey), id);
    }

    private FormSchemaView.ListConfig toListConfig(FormModel.ListConfig config) {
        FormSchemaView.ListConfig view = new FormSchemaView.ListConfig();
        view.setLayout(config.layout());
        view.setPageSizes(config.pageSizes());
        view.setAdvancedQuery(config.advancedQuery());
        view.setFullQuery(config.fullQuery());
        view.setShowSeq(config.showSeq());
        view.setButtons(config.buttons().stream()
                .map(b -> {
                    FormSchemaView.ButtonConfig item = new FormSchemaView.ButtonConfig();
                    item.setAction(b.action());
                    item.setShow(b.show());
                    item.setLabel(b.label());
                    return item;
                })
                .toList());
        return view;
    }

    private FormSchemaView.FieldSchema toFieldSchema(FormModel.FormField field) {
        FormSchemaView.FieldSchema schema = new FormSchemaView.FieldSchema();
        schema.setName(field.name());
        schema.setLabel(field.name());
        schema.setType(field.type());
        schema.setNullable(field.nullable());
        schema.setDefaultValue(field.defaultValue());
        schema.setDictType(field.dictType());
        schema.setQueryShow(field.query().show());
        schema.setQueryOp(field.query().op());
        schema.setQueryDefaultValue(field.query().defaultValue());
        schema.setListShow(field.list().show());
        schema.setWidth(field.list().width());
        schema.setAlign(field.list().align());
        schema.setFormat(field.list().format());
        schema.setFormShow(field.form().show());
        schema.setComponent(field.form().component());
        schema.setProps(field.form().props());
        schema.setRules(field.form().rules());
        schema.setReadonly(field.form().readonly());
        schema.setPersisted(field.persisted());
        schema.setSort(field.sort());
        return schema;
    }

    private boolean hasPermission(String formKey, String action) {
        return StpUtil.hasPermission(String.format(FormConstants.RUNTIME_PERMISSION_TEMPLATE, formKey, action));
    }
}
