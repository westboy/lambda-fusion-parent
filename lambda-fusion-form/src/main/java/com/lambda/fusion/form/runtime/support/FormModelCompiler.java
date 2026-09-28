package com.lambda.fusion.form.runtime.support;

import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.support.FormModel.FormField;
import com.lambda.fusion.form.runtime.support.FormModel.FormRelation;
import com.lambda.fusion.form.runtime.support.FormModel.TreeConfig;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 发布快照 → 不可变 {@link FormModel} 编译
 *
 * <p>编译期做结构完整性校验（字段类型白名单、列名非空唯一、TREE 配置完整）；
 * 快照由发布流程校验后写入，编译失败说明快照被外部篡改。
 *
 * @author Zx
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class FormModelCompiler {

    private final SnapshotJson snapshotJson;

    /**
     * 编译快照 JSON
     *
     * @param snapshotJson 发布快照
     * @param version 生效版本号
     */
    public FormModel compile(String snapshotJson, long version) {
        FormDefSnapshot snapshot = this.snapshotJson.read(snapshotJson);
        if (!FormConstants.isIdentifier(snapshot.getTableName())) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "表名非法：" + snapshot.getTableName());
        }
        List<FormField> fields = new ArrayList<>();
        Set<String> fieldNames = new HashSet<>();
        Set<String> columns = new HashSet<>();
        for (FormDefSnapshot.Field field : snapshot.getFields()) {
            String type =
                    field.getFieldType() == null ? null : field.getFieldType().toUpperCase(Locale.ROOT);
            if (type == null || !FormConstants.FIELD_TYPES.contains(type)) {
                throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "字段类型非法：" + field.getFieldName());
            }
            if (field.getFieldName() == null || field.getColumnName() == null) {
                throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "字段标识与列名不能为空");
            }
            if (!FormConstants.isIdentifier(field.getColumnName())) {
                throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "列名非法：" + field.getColumnName());
            }
            if (!fieldNames.add(field.getFieldName())
                    || !columns.add(field.getColumnName().toUpperCase(Locale.ROOT))) {
                throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "字段标识或列名重复：" + field.getFieldName());
            }
            fields.add(new FormField(
                    field.getFieldName(),
                    field.getColumnName().toUpperCase(Locale.ROOT),
                    type,
                    Boolean.TRUE.equals(field.getNullable()),
                    field.getDefaultValue(),
                    field.getValidation(),
                    field.getDictType(),
                    field.getUiComponent(),
                    field.getUiProps(),
                    Boolean.TRUE.equals(field.getListShow()),
                    Boolean.TRUE.equals(field.getQueryShow()),
                    resolveQueryOp(field),
                    Boolean.TRUE.equals(field.getFormShow()),
                    field.getSort() == null ? 0 : field.getSort()));
        }
        fields.sort(Comparator.comparingInt(FormField::sort));
        List<FormRelation> relations = new ArrayList<>();
        for (FormDefSnapshot.Relation relation : snapshot.getRelations()) {
            if (!FormConstants.isIdentifier(relation.getSubFormKey())
                    || !FormConstants.isIdentifier(relation.getForeignKey())) {
                throw new FormBusinessException(
                        FormErrorCode.FORM_TABLE_INVALID,
                        "主从关系标识非法：" + relation.getSubFormKey() + "/" + relation.getForeignKey());
            }
            relations.add(new FormRelation(
                    relation.getSubFormKey(),
                    relation.getForeignKey().toUpperCase(Locale.ROOT),
                    relation.getCascadeStrategy() == null
                            ? FormConstants.CASCADE_DELETE
                            : relation.getCascadeStrategy()));
        }
        TreeConfig tree = compileTree(snapshot, fields);
        return new FormModel(
                snapshot.getFormKey(),
                snapshot.getFormName(),
                version,
                snapshot.getFormType(),
                snapshot.getTableName(),
                fields,
                relations,
                tree,
                snapshot.getTreeConfig());
    }

    private TreeConfig compileTree(FormDefSnapshot snapshot, List<FormField> fields) {
        if (!FormConstants.FORM_TYPE_TREE.equals(snapshot.getFormType())) {
            return null;
        }
        if (snapshot.getTreeConfig() == null || snapshot.getTreeConfig().isBlank()) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "TREE 类型缺少 treeConfig");
        }
        TreeConfig tree = snapshotJson.readTreeConfig(snapshot.getTreeConfig());
        if (tree.sourceType() == null) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "treeConfig 缺少 sourceType");
        }
        if (!tree.selfRelation()) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "treeConfig.sourceType 暂只支持 SELF");
        }
        if (tree.parentColumn() == null || tree.nodeColumn() == null) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "自关联树缺少 parentColumn/nodeColumn");
        }
        if (!FormConstants.isIdentifier(tree.parentColumn()) || !FormConstants.isIdentifier(tree.nodeColumn())) {
            throw new FormBusinessException(
                    FormErrorCode.FORM_TABLE_INVALID, "树配置列非法：" + tree.parentColumn() + "/" + tree.nodeColumn());
        }
        if (tree.filterColumn() != null && !FormConstants.isIdentifier(tree.filterColumn())) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "树过滤列非法：" + tree.filterColumn());
        }
        String parentColumn = tree.parentColumn().toUpperCase(Locale.ROOT);
        boolean parentMapped = fields.stream().anyMatch(f -> f.column().equals(parentColumn));
        if (!parentMapped) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "树父列未映射字段：" + parentColumn);
        }
        return new TreeConfig(
                "SELF",
                parentColumn,
                tree.nodeColumn().toUpperCase(Locale.ROOT),
                null,
                tree.filterColumn() == null ? parentColumn : tree.filterColumn().toUpperCase(Locale.ROOT));
    }

    private String resolveQueryOp(FormDefSnapshot.Field field) {
        String op = field.getQueryOp();
        if (op == null || op.isBlank()) {
            // 文本类默认 like，其余默认 eq
            String type = field.getFieldType();
            return "STRING".equals(type) || "TEXT".equals(type) ? "like" : "eq";
        }
        if (!FormConstants.QUERY_OPERATORS.contains(op)) {
            throw new FormBusinessException(FormErrorCode.FORM_QUERY_OPERATOR_UNSUPPORTED, op);
        }
        return op;
    }
}
