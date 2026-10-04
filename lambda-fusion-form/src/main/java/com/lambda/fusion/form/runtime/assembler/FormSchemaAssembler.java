package com.lambda.fusion.form.runtime.assembler;

import com.lambda.fusion.form.runtime.model.FormSchemaView;
import com.lambda.fusion.form.runtime.support.FormModel;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 已编译模型的唯一渲染协议出口，供运行页和设计预览共用。 */
@Component
public class FormSchemaAssembler {
    public FormSchemaView assemble(FormModel model) {
        FormSchemaView view = new FormSchemaView();
        view.setFormKey(model.formKey());
        view.setFormName(model.formName());
        view.setFormType(model.formType());
        view.setVersion(model.version());
        view.setLogicDeleteColumn(model.logicDeleteColumn());
        if (model.tree() != null) {
            FormSchemaView.TreeSchema tree = new FormSchemaView.TreeSchema();
            tree.setSourceType(model.tree().sourceType());
            // SELF：label/parent 为本表字段；FORM/DICT：本表节点字段 + 来源信息，label 由前端从树来源侧解析
            tree.setLabelField(
                    model.tree().nodeColumn() == null
                            ? null
                            : fieldNameOfColumn(model, model.tree().nodeColumn()));
            tree.setParentField(
                    model.tree().parentColumn() == null
                            ? null
                            : fieldNameOfColumn(model, model.tree().parentColumn()));
            tree.setTreeFormKey(model.tree().treeFormKey());
            // nodeIdField 配置即字段标识（编译期按字段名校验），直接透传
            tree.setNodeIdField(model.tree().nodeIdField());
            tree.setDictType(model.tree().dictType());
            view.setTree(tree);
        }
        model.relations().forEach(relation -> {
            FormSchemaView.RelationSchema view1 = new FormSchemaView.RelationSchema();
            view1.setSubFormKey(relation.subFormKey());
            view1.setForeignKey(relation.foreignKey());
            view1.setCascadeStrategy(relation.cascadeStrategy());
            view1.setMode(relation.mode());
            view1.setSubTableConfig(relation.subTableConfig());
            view.getRelations().add(view1);
        });
        view.setListConfig(toListConfig(model.listConfig()));
        view.setTreeConfig(model.treeConfig());
        view.setFormLayout(model.formLayout());
        List<FormSchemaView.FieldSchema> fields = new ArrayList<>();
        model.fields().forEach(field -> {
            FormSchemaView.FieldSchema schema = toFieldSchema(field);
            if (field.column().equals(model.logicDeleteColumn())) {
                schema.setFormShow(false);
                schema.setReadonly(true);
            }
            fields.add(schema);
        });
        view.setFields(fields);
        return view;
    }

    /** 按物理列反查字段标识；未映射返回 null */
    private String fieldNameOfColumn(FormModel model, String column) {
        return model.fields().stream()
                .filter(field -> field.column().equals(column))
                .findFirst()
                .map(FormModel.FormField::name)
                .orElse(null);
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
        schema.setLabel(field.label());
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
}
