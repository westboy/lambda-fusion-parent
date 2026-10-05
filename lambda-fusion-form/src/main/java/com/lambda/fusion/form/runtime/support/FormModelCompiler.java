package com.lambda.fusion.form.runtime.support;

import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.support.FormModel.FormField;
import com.lambda.fusion.form.runtime.support.FormModel.FormRelation;
import com.lambda.fusion.form.runtime.support.FormModel.TreeConfig;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        if (!FormConstants.isFormKey(snapshot.getFormKey())
                || snapshot.getFormType() == null
                || !FormConstants.FORM_TYPES.contains(snapshot.getFormType())) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "表单标识或类型非法");
        }
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
            if (!FormConstants.isIdentifier(field.getFieldName()) || field.getColumnName() == null) {
                throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "字段标识与列名不能为空");
            }
            if (!FormConstants.isIdentifier(field.getColumnName())) {
                throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "列名非法：" + field.getColumnName());
            }
            // 租户列结构性禁映射；字段名 id 会覆盖行标识，同样禁止。
            // 主键与审计列由引擎独占写入，仅允许映射为不落库的展示字段（主键值本就投影在行上）
            boolean persistedField = !Boolean.FALSE.equals(field.getPersisted());
            String mappedColumn = field.getColumnName().toUpperCase(Locale.ROOT);
            if (FormConstants.COLUMN_TENANT_ID.equals(mappedColumn) || "id".equalsIgnoreCase(field.getFieldName())) {
                throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "禁止映射系统字段：" + field.getColumnName());
            }
            boolean engineWritten =
                    FormConstants.COLUMN_ID.equals(mappedColumn) || FormConstants.AUDIT_COLUMNS.contains(mappedColumn);
            if (engineWritten && persistedField) {
                throw new FormBusinessException(
                        FormErrorCode.FORM_TABLE_INVALID, "列 " + field.getColumnName() + " 由引擎写入，仅可作为不落库的展示字段（落库=否）");
            }
            // 从表字段（FLAT）列唯一性按来源分别判定（跨表允许同名列），字段标识全局唯一（扁平投影无歧义）
            String columnKey = (field.getSubFormKey() == null ? "" : field.getSubFormKey() + ":") + mappedColumn;
            if (!fieldNames.add(field.getFieldName()) || !columns.add(columnKey)) {
                throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "字段标识或列名重复：" + field.getFieldName());
            }
            FormDefSnapshot.QueryConf query =
                    field.getQuery() == null ? new FormDefSnapshot.QueryConf() : field.getQuery();
            FormDefSnapshot.ListConf list = field.getList() == null ? new FormDefSnapshot.ListConf() : field.getList();
            FormDefSnapshot.FormConf formConf =
                    field.getForm() == null ? new FormDefSnapshot.FormConf() : field.getForm();
            if (Boolean.TRUE.equals(list.getSummary()) && (!persistedField || field.getSubFormKey() != null)) {
                throw new FormBusinessException(
                        FormErrorCode.FORM_TABLE_INVALID, "合计仅支持主表落库字段：" + field.getFieldName());
            }
            fields.add(new FormField(
                    field.getFieldName(),
                    field.getLabel() == null || field.getLabel().isBlank() ? field.getFieldName() : field.getLabel(),
                    field.getColumnName().toUpperCase(Locale.ROOT),
                    type,
                    Boolean.TRUE.equals(field.getNullable()),
                    field.getDefaultValue(),
                    field.getDictType(),
                    field.getSort() == null ? 0 : field.getSort(),
                    !Boolean.FALSE.equals(field.getPersisted()),
                    field.getSubFormKey(),
                    new FormModel.QueryConf(
                            Boolean.TRUE.equals(query.getShow()),
                            resolveQueryOp(type, query.getOp()),
                            query.getDefaultValue()),
                    compileListConf(list),
                    compileFormConf(formConf)));
        }
        fields.forEach(FormFieldValidator::validateDefinition);
        fields.sort(Comparator.comparingInt(FormField::sort));
        List<FormRelation> relations = new ArrayList<>();
        Set<String> subFormKeys = new HashSet<>();
        for (FormDefSnapshot.Relation relation : snapshot.getRelations()) {
            if (!FormConstants.isIdentifier(relation.getSubFormKey())
                    || !FormConstants.isIdentifier(relation.getForeignKey())) {
                throw new FormBusinessException(
                        FormErrorCode.FORM_TABLE_INVALID,
                        "主从关系标识非法：" + relation.getSubFormKey() + "/" + relation.getForeignKey());
            }
            if (relation.getSubFormKey().equals(snapshot.getFormKey())) {
                throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "主从关系不能自引用");
            }
            if (!subFormKeys.add(relation.getSubFormKey())) {
                throw new FormBusinessException(
                        FormErrorCode.FORM_TABLE_INVALID, "子表单关系重复：" + relation.getSubFormKey());
            }
            String mode = relation.getMode() == null || relation.getMode().isBlank()
                    ? FormConstants.RELATION_MODE_INLINE
                    : relation.getMode().toUpperCase(Locale.ROOT);
            if (!FormConstants.RELATION_MODES.contains(mode)) {
                throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "主从呈现方式非法：" + relation.getMode());
            }
            this.snapshotJson.validateSubTableConfig(relation.getSubTableConfig());
            relations.add(new FormRelation(
                    relation.getSubFormKey(),
                    relation.getForeignKey().toUpperCase(Locale.ROOT),
                    relation.getCascadeStrategy() == null
                            ? FormConstants.CASCADE_DELETE
                            : relation.getCascadeStrategy(),
                    mode,
                    relation.getSubTableConfig()));
        }
        TreeConfig tree = compileTree(snapshot, fields);
        validateLogicDeleteColumn(snapshot, fields);
        return new FormModel(
                snapshot.getFormKey(),
                snapshot.getFormName(),
                version,
                snapshot.getFormType(),
                snapshot.getTableName(),
                snapshot.getLogicDeleteColumn() == null
                                || snapshot.getLogicDeleteColumn().isBlank()
                        ? null
                        : snapshot.getLogicDeleteColumn().toUpperCase(Locale.ROOT),
                compileListConfig(snapshot.getListConfig()),
                FormLayouts.compile(
                        snapshot.getFormLayout(),
                        fields.stream().map(FormField::name).toList()),
                List.copyOf(fields),
                List.copyOf(relations),
                tree,
                snapshot.getInterceptor());
    }

    private TreeConfig compileTree(FormDefSnapshot snapshot, List<FormField> fields) {
        if (!FormConstants.FORM_TYPE_TREE.equals(snapshot.getFormType())) {
            return null;
        }
        if (snapshot.getTreeConfig() == null || snapshot.getTreeConfig().isBlank()) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "TREE 类型缺少 treeConfig");
        }
        TreeConfig tree = snapshotJson.readTreeConfig(snapshot.getTreeConfig());
        String sourceType = tree.sourceType() == null || tree.sourceType().isBlank()
                ? FormConstants.TREE_SOURCE_SELF
                : tree.sourceType().toUpperCase(Locale.ROOT);
        if (!FormConstants.TREE_SOURCES.contains(sourceType)) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "树来源非法：" + tree.sourceType());
        }
        return switch (sourceType) {
            case FormConstants.TREE_SOURCE_FORM -> compileFormSourceTree(snapshot, tree, fields);
            case FormConstants.TREE_SOURCE_DICT -> compileDictSourceTree(tree, fields);
            default -> compileSelfTree(snapshot, tree, fields);
        };
    }

    /** SELF 自关联：父列/节点列须为本表已映射字段，过滤列缺省取父列 */
    private TreeConfig compileSelfTree(FormDefSnapshot snapshot, TreeConfig tree, List<FormField> fields) {
        if (tree.parentColumn() == null || tree.nodeColumn() == null) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "自关联树缺少 parentColumn/nodeColumn");
        }
        if (!FormConstants.isIdentifier(tree.parentColumn()) || !FormConstants.isIdentifier(tree.nodeColumn())) {
            throw new FormBusinessException(
                    FormErrorCode.FORM_TABLE_INVALID, "树配置列非法：" + tree.parentColumn() + "/" + tree.nodeColumn());
        }
        String parentColumn = tree.parentColumn().toUpperCase(Locale.ROOT);
        String nodeColumn = tree.nodeColumn().toUpperCase(Locale.ROOT);
        boolean parentMapped = fields.stream().anyMatch(f -> f.column().equals(parentColumn));
        if (!parentMapped) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "树父列未映射字段：" + parentColumn);
        }
        if (fields.stream().noneMatch(f -> f.column().equals(nodeColumn))) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "树节点列未映射字段：" + nodeColumn);
        }
        // 呈现方式缺省左树右表；TREE_TABLE 仅 SELF 支持
        String viewMode = tree.viewMode() == null || tree.viewMode().isBlank()
                ? TreeConfig.VIEW_MODE_LEFT_TREE
                : tree.viewMode().toUpperCase(Locale.ROOT);
        if (!TreeConfig.VIEW_MODE_LEFT_TREE.equals(viewMode) && !TreeConfig.VIEW_MODE_TREE_TABLE.equals(viewMode)) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "树呈现方式非法：" + tree.viewMode());
        }
        return new TreeConfig(FormConstants.TREE_SOURCE_SELF, parentColumn, nodeColumn, null, null, null, viewMode);
    }

    /** FORM 关联树表单：引用另一 TREE 表单作左树数据源，本表须有落库的节点 id 字段；跨表单存在性由保存期校验 */
    private TreeConfig compileFormSourceTree(FormDefSnapshot snapshot, TreeConfig tree, List<FormField> fields) {
        if (!FormConstants.isFormKey(tree.treeFormKey())) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "关联树表单标识非法或缺失");
        }
        if (tree.treeFormKey().equals(snapshot.getFormKey())) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "关联树表单不能是自身");
        }
        String nodeIdField = requireNodeIdField(tree, fields);
        return new TreeConfig(FormConstants.TREE_SOURCE_FORM, null, null, null, tree.treeFormKey(), nodeIdField, null);
    }

    /** DICT 字典树：按字典编码取层级数据，本表节点 id 字段存字典项代码 */
    private TreeConfig compileDictSourceTree(TreeConfig tree, List<FormField> fields) {
        if (tree.dictType() == null || tree.dictType().isBlank()) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "字典树缺少 dictType");
        }
        String nodeIdField = requireNodeIdField(tree, fields);
        return new TreeConfig(FormConstants.TREE_SOURCE_DICT, null, null, tree.dictType(), null, nodeIdField, null);
    }

    /** FORM/DICT 来源共用的本表节点 id 字段校验：标识合法、已映射且落库，返回字段标识 */
    private String requireNodeIdField(TreeConfig tree, List<FormField> fields) {
        if (tree.nodeIdField() == null || !FormConstants.isIdentifier(tree.nodeIdField())) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "树节点字段非法或缺失");
        }
        boolean mapped = fields.stream().filter(field -> field.persisted()).anyMatch(field -> field.name()
                .equals(tree.nodeIdField()));
        if (!mapped) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "树节点字段未映射或未落库：" + tree.nodeIdField());
        }
        return tree.nodeIdField();
    }

    /** 列表配置编译：列宽范围、对齐与格式化枚举、冻结方向、排序方向 */
    private FormModel.ListConf compileListConf(FormDefSnapshot.ListConf list) {
        if (list.getWidth() != null
                && (list.getWidth() < FieldConfigs.MIN_WIDTH || list.getWidth() > FieldConfigs.MAX_WIDTH)) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "列宽非法：" + list.getWidth());
        }
        if (list.getAlign() != null && !FieldConfigs.ALIGNS.contains(list.getAlign())) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "列对齐非法：" + list.getAlign());
        }
        if (list.getFormat() != null && !FieldConfigs.FORMATS.contains(list.getFormat())) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "列格式化非法：" + list.getFormat());
        }
        if (list.getFrozen() != null && !"left".equals(list.getFrozen()) && !"right".equals(list.getFrozen())) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "冻结方向非法：" + list.getFrozen());
        }
        if (list.getDefaultSort() != null
                && !"asc".equals(list.getDefaultSort())
                && !"desc".equals(list.getDefaultSort())) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "排序方向非法：" + list.getDefaultSort());
        }
        return new FormModel.ListConf(
                !Boolean.FALSE.equals(list.getShow()),
                list.getWidth(),
                list.getAlign(),
                list.getFormat(),
                list.getFrozen(),
                Boolean.TRUE.equals(list.getSummary()),
                list.getDefaultSort(),
                list.getSortOrder() == null ? 0 : list.getSortOrder());
    }

    /** 列表级配置编译（缺省归一） */
    private FormModel.ListConfig compileListConfig(FormDefSnapshot.ListConfig config) {
        if (config == null) {
            return new FormModel.ListConfig(List.of(), true, List.of());
        }
        List<Integer> pageSizes =
                config.getPageSizes() == null || config.getPageSizes().isEmpty()
                        ? List.of(20, 50, 100, 500)
                        : List.copyOf(config.getPageSizes());
        List<FormModel.ButtonConfig> buttons = (config.getButtons() == null
                        ? List.<FormDefSnapshot.ButtonConfig>of()
                        : config.getButtons())
                .stream()
                        .map(b -> new FormModel.ButtonConfig(
                                b.getAction() == null ? "" : b.getAction(),
                                !Boolean.FALSE.equals(b.getShow()),
                                b.getLabel()))
                        .toList();
        return new FormModel.ListConfig(pageSizes, !Boolean.FALSE.equals(config.getShowSeq()), buttons);
    }

    /** 逻辑删除列：如配置，必须是已映射字段的列 */
    private void validateLogicDeleteColumn(FormDefSnapshot snapshot, List<FormField> fields) {
        String column = snapshot.getLogicDeleteColumn();
        if (column == null || column.isBlank()) {
            return;
        }
        if (!FormConstants.isIdentifier(column)) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "逻辑删除列非法：" + column);
        }
        String upper = column.toUpperCase(Locale.ROOT);
        boolean mapped = fields.stream().anyMatch(f -> f.column().equals(upper));
        if (!mapped) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "逻辑删除列未映射字段：" + column);
        }
        if (fields.stream().filter(field -> field.column().equals(upper)).anyMatch(field -> !Set.of("INT", "BOOL")
                .contains(field.type()))) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "逻辑删除列必须为整数或布尔，0 有效、1 已删除");
        }
    }

    /** 表单与详情配置编译：组件名走标识符白名单（快照被篡改时在此拦截） */
    private FormModel.FormConf compileFormConf(FormDefSnapshot.FormConf formConf) {
        String component = formConf.getComponent();
        if (component != null && !component.isBlank() && !FormConstants.isIdentifier(component)) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "表单组件名非法：" + component);
        }
        return new FormModel.FormConf(
                !Boolean.FALSE.equals(formConf.getShow()),
                component == null || component.isBlank() ? null : component,
                formConf.getProps() == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(formConf.getProps())),
                formConf.getRules() == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(formConf.getRules())),
                Boolean.TRUE.equals(formConf.getReadonly()));
    }

    /** 查询操作符归一：显式值走白名单，缺省按字段类型取 like/eq */
    private String resolveQueryOp(String type, String op) {
        if (op == null || op.isBlank()) {
            return "STRING".equals(type) || "TEXT".equals(type) ? "like" : "eq";
        }
        if (!FormConstants.QUERY_OPERATORS.contains(op)) {
            throw new FormBusinessException(FormErrorCode.FORM_QUERY_OPERATOR_UNSUPPORTED, op);
        }
        return op;
    }
}
