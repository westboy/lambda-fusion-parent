package com.lambda.fusion.form.runtime.assembler;

import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.runtime.model.FormSchemaView;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 已编译模型的唯一渲染协议出口，供运行页和设计预览共用。 */
@Component
public class FormSchemaAssembler {

    private final FormModelProvider modelProvider;

    public FormSchemaAssembler(FormModelProvider modelProvider) {
        this.modelProvider = modelProvider;
    }

    public FormSchemaView assemble(FormModel model) {
        return assemble(model, null);
    }

    /**
     * @param nodeCode 流程节点编码（可空）：非空时为每个字段解析节点维度权限
     *                 （nodeEditable 该节点可填写 / nodeShow 该节点是否展示），供办理端渲染填写区
     */
    public FormSchemaView assemble(FormModel model, String nodeCode) {
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
            tree.setViewMode(model.tree().viewMode());
            // FORM 来源：左树行来自树表单模型，formLabelField 下发树表单的节点展示字段标识供前端取标签
            if (model.tree().formSource()) {
                try {
                    FormModel treeModel = modelProvider.require(model.tree().treeFormKey());
                    tree.setFormLabelField(
                            treeModel.tree() != null && treeModel.tree().nodeColumn() != null
                                    ? fieldNameOfColumn(
                                            treeModel, treeModel.tree().nodeColumn())
                                    : null);
                } catch (FormBusinessException error) {
                    // 树表单未发布等异常不阻断主 schema 组装，左树前端自行降级
                    tree.setFormLabelField(null);
                }
            }
            view.setTree(tree);
        }
        model.relations().forEach(relation -> {
            FormSchemaView.RelationSchema view1 = new FormSchemaView.RelationSchema();
            view1.setSubFormKey(relation.subFormKey());
            view1.setSubTable(relation.subTable());
            view1.setForeignKey(relation.foreignKey());
            view1.setCascadeStrategy(relation.cascadeStrategy());
            view1.setMode(relation.mode());
            view1.setSubTableConfig(relation.subTableConfig());
            view.getRelations().add(view1);
        });
        view.setListConfig(toListConfig(model.listConfig()));
        view.setDetailConfig(toDetailConfig(model.detailConfig()));
        view.setFormLayout(model.formLayout());
        List<FormSchemaView.FieldSchema> fields = new ArrayList<>();
        model.fields().forEach(field -> {
            FormSchemaView.FieldSchema schema = toFieldSchema(field, nodeCode);
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

    private FormSchemaView.DetailConfig toDetailConfig(FormModel.DetailConfig config) {
        FormSchemaView.DetailConfig view = new FormSchemaView.DetailConfig();
        view.setMode(config.mode());
        view.setWidth(config.width());
        view.setColumns(config.columns());
        view.setSystemCollapsed(config.systemCollapsed());
        return view;
    }

    private FormSchemaView.ListConfig toListConfig(FormModel.ListConfig config) {
        FormSchemaView.ListConfig view = new FormSchemaView.ListConfig();
        view.setPageSizes(config.pageSizes());
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

    private FormSchemaView.FieldSchema toFieldSchema(FormModel.FormField field, String nodeCode) {
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
        schema.setFrozen(field.list().frozen());
        schema.setSummary(field.list().summary());
        schema.setDefaultSort(field.list().defaultSort());
        schema.setSortOrder(field.list().sortOrder());
        schema.setFormShow(field.form().show());
        schema.setComponent(field.form().component());
        schema.setProps(field.form().props());
        schema.setRules(field.form().rules());
        schema.setReadonly(field.form().readonly());
        schema.setPersisted(field.persisted());
        schema.setSubFormKey(field.subFormKey());
        schema.setSort(field.sort());
        if (nodeCode != null) {
            resolveNodePerms(field, nodeCode, schema);
        }
        return schema;
    }

    /**
     * 节点维度权限解析（仅主表、画布可见、落库字段可成为填写字段）：
     * 有该节点配置时 editable/show 显式生效，否则 show=true / editable=false（单据只读可见）。
     */
    private void resolveNodePerms(FormModel.FormField field, String nodeCode, FormSchemaView.FieldSchema schema) {
        FormModel.NodeConf conf = field.form().nodeConf(nodeCode);
        boolean fillable =
                field.subFormKey() == null && field.persisted() && field.form().show();
        schema.setNodeEditable(fillable && conf != null && conf.editable());
        schema.setNodeShow(conf == null || conf.show());
    }
}
