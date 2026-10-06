package com.lambda.fusion.form.runtime.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/**
 * 发布快照 → 运行时模型编译测试（结构完整性校验与默认值归一）
 *
 * @author Zx
 */
class FormModelCompilerTest {

    private SnapshotJson snapshotJson;

    private FormModelCompiler compiler;

    @BeforeEach
    void setUp() {
        snapshotJson = new SnapshotJson(new ObjectMapper());
        compiler = new FormModelCompiler(snapshotJson);
    }

    @Test
    void compilesFieldsSortedBySortWithUppercasedColumns() {
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_SINGLE);
        snapshot.setFields(List.of(field("reason", "reason", "TEXT", 2), field("applicant", "applicant", "STRING", 1)));

        FormModel model = compiler.compile(snapshotJson.write(snapshot), 7L);

        assertThat(model.formKey()).isEqualTo("leave_demo");
        assertThat(model.formName()).isEqualTo("请假演示");
        assertThat(model.version()).isEqualTo(7L);
        assertThat(model.tableName()).isEqualTo("la_demo_leave");
        assertThat(model.fields()).extracting(FormModel.FormField::name).containsExactly("applicant", "reason");
        assertThat(model.fields()).extracting(FormModel.FormField::column).containsExactly("APPLICANT", "REASON");
        assertThat(model.tree()).isNull();
    }

    @Test
    void defaultsQueryOperatorByFieldType() {
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_SINGLE);
        snapshot.setFields(List.of(
                field("applicant", "applicant", "STRING", 1),
                field("reason", "reason", "TEXT", 2),
                field("days", "days", "DECIMAL", 3)));

        FormModel model = compiler.compile(snapshotJson.write(snapshot), 1L);

        assertThat(model.fields()).extracting(field -> field.query().op()).containsExactly("like", "like", "eq");
    }

    @Test
    void keepsExplicitQueryOperator() {
        FormDefSnapshot.Field field = field("days", "days", "DECIMAL", 1);
        field.getQuery().setOp("ge");
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_SINGLE);
        snapshot.setFields(List.of(field));

        FormModel model = compiler.compile(snapshotJson.write(snapshot), 1L);

        assertThat(model.fields().get(0).query().op()).isEqualTo("ge");
    }

    @Test
    void rejectsFieldTypeOutsideWhitelist() {
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_SINGLE);
        snapshot.setFields(List.of(field("amount", "amount", "MONEY", 1)));

        assertErrorCode(() -> compiler.compile(snapshotJson.write(snapshot), 1L), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void rejectsDuplicateFieldNameOrColumn() {
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_SINGLE);
        snapshot.setFields(
                List.of(field("applicant", "applicant", "STRING", 1), field("applicant", "applicant2", "STRING", 2)));

        assertErrorCode(() -> compiler.compile(snapshotJson.write(snapshot), 1L), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void rejectsQueryOperatorOutsideWhitelist() {
        FormDefSnapshot.Field field = field("applicant", "applicant", "STRING", 1);
        field.getQuery().setOp("regexp");
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_SINGLE);
        snapshot.setFields(List.of(field));

        assertErrorCode(
                () -> compiler.compile(snapshotJson.write(snapshot), 1L),
                FormErrorCode.FORM_QUERY_OPERATOR_UNSUPPORTED);
    }

    @Test
    void rejectsTreeFormWithoutTreeConfig() {
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_TREE);
        snapshot.setFields(List.of(field("name", "name", "STRING", 1)));

        assertErrorCode(() -> compiler.compile(snapshotJson.write(snapshot), 1L), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void rejectsUnknownTreeSource() {
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_TREE);
        snapshot.setFields(List.of(field("parent_id", "parent_id", "STRING", 1)));
        snapshot.setTreeConfig("{\"sourceType\":\"EXTERNAL\"}");

        assertErrorCode(() -> compiler.compile(snapshotJson.write(snapshot), 1L), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void compilesFormAndDictTreeSourcesWithMappedNodeIdField() {
        FormDefSnapshot formSource = snapshot(FormConstants.FORM_TYPE_TREE);
        formSource.setFields(List.of(field("orgId", "ORG_ID", "STRING", 1)));
        formSource.setTreeConfig("{\"sourceType\":\"FORM\",\"treeFormKey\":\"org_tree\",\"nodeIdField\":\"orgId\"}");
        FormModel formModel = compiler.compile(snapshotJson.write(formSource), 1L);
        assertThat(formModel.tree().formSource()).isTrue();
        assertThat(formModel.tree().treeFormKey()).isEqualTo("org_tree");
        assertThat(formModel.tree().nodeIdField()).isEqualTo("orgId");

        FormDefSnapshot dictSource = snapshot(FormConstants.FORM_TYPE_TREE);
        dictSource.setFields(List.of(field("orgId", "ORG_ID", "STRING", 1)));
        dictSource.setTreeConfig("{\"sourceType\":\"DICT\",\"dictType\":\"area_type\",\"nodeIdField\":\"orgId\"}");
        FormModel dictModel = compiler.compile(snapshotJson.write(dictSource), 1L);
        assertThat(dictModel.tree().dictSource()).isTrue();
        assertThat(dictModel.tree().dictType()).isEqualTo("area_type");
    }

    @Test
    void rejectsFormSourceSelfReferenceAndUnmappedNodeIdField() {
        FormDefSnapshot selfRef = snapshot(FormConstants.FORM_TYPE_TREE);
        selfRef.setFields(List.of(field("orgId", "ORG_ID", "STRING", 1)));
        // 快照自身 formKey 为 leave_demo，引用自身即拒绝
        selfRef.setTreeConfig("{\"sourceType\":\"FORM\",\"treeFormKey\":\"leave_demo\",\"nodeIdField\":\"orgId\"}");
        assertErrorCode(() -> compiler.compile(snapshotJson.write(selfRef), 1L), FormErrorCode.FORM_TABLE_INVALID);

        FormDefSnapshot unmapped = snapshot(FormConstants.FORM_TYPE_TREE);
        unmapped.setFields(List.of(field("name", "name", "STRING", 1)));
        unmapped.setTreeConfig("{\"sourceType\":\"FORM\",\"treeFormKey\":\"org_tree\",\"nodeIdField\":\"orgId\"}");
        assertErrorCode(() -> compiler.compile(snapshotJson.write(unmapped), 1L), FormErrorCode.FORM_TABLE_INVALID);

        FormDefSnapshot noDictType = snapshot(FormConstants.FORM_TYPE_TREE);
        noDictType.setFields(List.of(field("orgId", "ORG_ID", "STRING", 1)));
        noDictType.setTreeConfig("{\"sourceType\":\"DICT\",\"nodeIdField\":\"orgId\"}");
        assertErrorCode(() -> compiler.compile(snapshotJson.write(noDictType), 1L), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void rejectsTreeParentColumnNotMapped() {
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_TREE);
        snapshot.setFields(List.of(field("name", "name", "STRING", 1)));
        snapshot.setTreeConfig("{\"sourceType\":\"SELF\",\"parentColumn\":\"parent_id\",\"nodeColumn\":\"name\"}");

        assertErrorCode(() -> compiler.compile(snapshotJson.write(snapshot), 1L), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void rejectsNonIdentifierTreeColumns() {
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_TREE);
        snapshot.setFields(List.of(field("name", "name", "STRING", 1), field("parentId", "parent_id", "STRING", 2)));
        snapshot.setTreeConfig(
                "{\"sourceType\":\"SELF\",\"parentColumn\":\"parent_id\",\"nodeColumn\":\"name WHERE 1 = 1 --\"}");

        assertErrorCode(() -> compiler.compile(snapshotJson.write(snapshot), 1L), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void compilesSelfTree() {
        String treeConfig = "{\"sourceType\":\"SELF\",\"parentColumn\":\"parent_id\",\"nodeColumn\":\"name\"}";
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_TREE);
        snapshot.setFields(List.of(field("name", "name", "STRING", 1), field("parentId", "parent_id", "STRING", 2)));
        snapshot.setTreeConfig(treeConfig);

        FormModel model = compiler.compile(snapshotJson.write(snapshot), 2L);

        assertThat(model.tree()).isNotNull();
        assertThat(model.tree().sourceType()).isEqualTo("SELF");
        assertThat(model.tree().parentColumn()).isEqualTo("PARENT_ID");
        assertThat(model.tree().nodeColumn()).isEqualTo("NAME");
        assertThat(model.tree().selfRelation()).isTrue();
    }

    @Test
    void rejectsNonIdentifierTableName() {
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_SINGLE);
        snapshot.setTableName("la_demo_leave WHERE 1 = 1 --");
        snapshot.setFields(List.of(field("applicant", "applicant", "STRING", 1)));

        assertErrorCode(() -> compiler.compile(snapshotJson.write(snapshot), 1L), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void rejectsNonIdentifierColumn() {
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_SINGLE);
        snapshot.setFields(List.of(field("applicant", "APPLICANT = 'x' OR 1 = 1 --", "STRING", 1)));

        assertErrorCode(() -> compiler.compile(snapshotJson.write(snapshot), 1L), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void rejectsNonIdentifierRelationForeignKey() {
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_MASTER_SUB);
        snapshot.setFields(List.of(field("applicant", "applicant", "STRING", 1)));
        FormDefSnapshot.Relation relation = new FormDefSnapshot.Relation();
        relation.setSubFormKey("leave_item");
        relation.setForeignKey("LEAVE_ID = 'x' OR 1 = 1 --");
        snapshot.setRelations(List.of(relation));

        assertErrorCode(() -> compiler.compile(snapshotJson.write(snapshot), 1L), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void uppercasesRelationForeignKeyAndDefaultsCascadeStrategy() {
        FormDefSnapshot snapshot = masterSubSnapshot();
        FormDefSnapshot.Relation relation = relationOf("leave_item", "leave_id", "LA_TEST_SUB");
        snapshot.setRelations(List.of(relation));

        FormModel model = compiler.compile(snapshotJson.write(snapshot), 1L);

        assertThat(model.relations()).hasSize(1);
        assertThat(model.relations().get(0).subFormKey()).isEqualTo("leave_item");
        assertThat(model.relations().get(0).subTable()).isEqualTo("LA_TEST_SUB");
        assertThat(model.relations().get(0).foreignKey()).isEqualTo("LEAVE_ID");
        assertThat(model.relations().get(0).cascadeStrategy()).isEqualTo(FormConstants.CASCADE_DELETE);
    }

    @Test
    void rejectsRelationMissingSubTableOrUnmappedForeignKey() {
        // 缺子表物理表名：拒绝
        FormDefSnapshot missing = masterSubSnapshot();
        FormDefSnapshot.Relation relation = new FormDefSnapshot.Relation();
        relation.setSubFormKey("leave_item");
        relation.setForeignKey("LEAVE_ID");
        missing.setRelations(List.of(relation));
        assertErrorCode(() -> compiler.compile(snapshotJson.write(missing), 1L), FormErrorCode.FORM_TABLE_INVALID);

        // 外键列未在该关系从表字段中映射：拒绝
        FormDefSnapshot unmapped = masterSubSnapshot();
        unmapped.setRelations(List.of(relationOf("leave_item", "GHOST_FK", "LA_TEST_SUB")));
        assertErrorCode(() -> compiler.compile(snapshotJson.write(unmapped), 1L), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void rejectsInvalidRelationMode() {
        FormDefSnapshot snapshot = masterSubSnapshot();
        FormDefSnapshot.Relation relation = relationOf("leave_item", "LEAVE_ID", "LA_TEST_SUB");
        relation.setMode("GRID");
        snapshot.setRelations(List.of(relation));

        assertErrorCode(() -> compiler.compile(snapshotJson.write(snapshot), 1L), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void defaultsRelationModeToInlineAndKeepsListMode() {
        FormDefSnapshot snapshot = masterSubSnapshot();
        FormDefSnapshot.Relation inline = relationOf("leave_item", "LEAVE_ID", "LA_TEST_SUB");
        FormDefSnapshot.Relation ledger = relationOf("leave_memo", "LEAVE_ID", "LA_TEST_SUB");
        ledger.setMode("LIST");
        snapshot.setRelations(List.of(inline, ledger));

        FormModel model = compiler.compile(snapshotJson.write(snapshot), 1L);

        assertThat(model.relations().get(0).mode()).isEqualTo(FormConstants.RELATION_MODE_INLINE);
        assertThat(model.relations().get(0).listMode()).isFalse();
        assertThat(model.relations().get(1).mode()).isEqualTo(FormConstants.RELATION_MODE_LIST);
        assertThat(model.relations().get(1).listMode()).isTrue();
    }

    @Test
    void compilesFieldConfigsWithDefaults() {
        FormDefSnapshot.Field field = field("days", "days", "DECIMAL", 1);
        field.getQuery().setShow(Boolean.TRUE);
        field.getQuery().setDefaultValue("3");
        field.getList().setWidth(120);
        field.getList().setAlign("center");
        field.getForm().setComponent("InputNumber");
        field.getForm().setReadonly(Boolean.TRUE);
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_SINGLE);
        snapshot.setFields(List.of(field));

        FormModel model = compiler.compile(snapshotJson.write(snapshot), 1L);
        FormModel.FormField compiled = model.fields().get(0);

        assertThat(compiled.query().show()).isTrue();
        assertThat(compiled.query().defaultValue()).isEqualTo("3");
        assertThat(compiled.list().show()).isTrue();
        assertThat(compiled.list().width()).isEqualTo(120);
        assertThat(compiled.list().align()).isEqualTo("center");
        assertThat(compiled.list().format()).isNull();
        assertThat(compiled.form().component()).isEqualTo("InputNumber");
        assertThat(compiled.form().readonly()).isTrue();
        assertThat(compiled.form().props()).isEmpty();
    }

    @Test
    void rejectsListWidthOutOfRange() {
        FormDefSnapshot.Field field = field("days", "days", "DECIMAL", 1);
        field.getList().setWidth(600);
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_SINGLE);
        snapshot.setFields(List.of(field));

        assertErrorCode(() -> compiler.compile(snapshotJson.write(snapshot), 1L), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void rejectsIllegalListAlign() {
        FormDefSnapshot.Field field = field("days", "days", "DECIMAL", 1);
        field.getList().setAlign("middle");
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_SINGLE);
        snapshot.setFields(List.of(field));

        assertErrorCode(() -> compiler.compile(snapshotJson.write(snapshot), 1L), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void rejectsIllegalListFormat() {
        FormDefSnapshot.Field field = field("days", "days", "DECIMAL", 1);
        field.getList().setFormat("currency");
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_SINGLE);
        snapshot.setFields(List.of(field));

        assertErrorCode(() -> compiler.compile(snapshotJson.write(snapshot), 1L), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void rejectsNonIdentifierFormComponent() {
        FormDefSnapshot.Field field = field("days", "days", "DECIMAL", 1);
        field.getForm().setComponent("Input onclick=alert(1)");
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_SINGLE);
        snapshot.setFields(List.of(field));

        assertErrorCode(() -> compiler.compile(snapshotJson.write(snapshot), 1L), FormErrorCode.FORM_TABLE_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TENANT_ID"})
    void rejectsReservedColumnsEvenForReadOnlyMappings(String column) {
        // 租户列结构性禁映射（任何落库形态）；主键/审计列只读映射已合法（见 auditColumnsAllowedOnlyAsDisplayFields）
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_SINGLE);
        FormDefSnapshot.Field reserved = field("businessField", column, "STRING", 0);
        reserved.setPersisted(false);
        snapshot.setFields(List.of(reserved));
        assertErrorCode(() -> compiler.compile(snapshotJson.write(snapshot), 1), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void idColumnAllowedOnlyAsDisplayField() {
        // 不落库展示：允许映射主键列（值本就投影在行上），字段名不得为 id
        FormDefSnapshot display = snapshot(FormConstants.FORM_TYPE_SINGLE);
        FormDefSnapshot.Field rowId = field("rowId", "ID", "STRING", 1);
        rowId.setPersisted(Boolean.FALSE);
        display.setFields(List.of(rowId));
        FormModel model = compiler.compile(snapshotJson.write(display), 1L);
        assertThat(model.fields().getFirst().persisted()).isFalse();

        // 落库映射主键：拒绝
        FormDefSnapshot writable = snapshot(FormConstants.FORM_TYPE_SINGLE);
        writable.setFields(List.of(field("rowId", "ID", "STRING", 1)));
        assertErrorCode(() -> compiler.compile(snapshotJson.write(writable), 1L), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void rejectsIdFieldNameWhichWouldOverwriteRowIdentity() {
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_SINGLE);
        snapshot.setFields(List.of(field("id", "BUSINESS_CODE", "STRING", 0)));
        assertErrorCode(() -> compiler.compile(snapshotJson.write(snapshot), 1), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void acceptsNullComponentPropsWithoutMutableTopLevelMap() {
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_SINGLE);
        FormDefSnapshot.Field field = field("name", "NAME", "STRING", 0);
        Map<String, Object> props = new HashMap<>();
        props.put("placeholder", null);
        field.getForm().setProps(props);
        snapshot.setFields(List.of(field));
        FormModel model = compiler.compile(snapshotJson.write(snapshot), 1);
        assertThat(model.fields().getFirst().form().props()).containsEntry("placeholder", null);
        assertThatThrownBy(() -> model.fields().getFirst().form().props().put("disabled", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static void assertErrorCode(ThrowingCallable callable, FormErrorCode expected) {
        assertThatThrownBy(callable).isInstanceOf(FormBusinessException.class).satisfies(e -> assertThat(
                        ((FormBusinessException) e).getCode())
                .isEqualTo(expected.getCode()));
    }

    private static FormDefSnapshot snapshot(String formType) {
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("leave_demo");
        snapshot.setFormName("请假演示");
        snapshot.setFormType(formType);
        snapshot.setTableName("la_demo_leave");
        snapshot.setFields(new ArrayList<>());
        snapshot.setRelations(new ArrayList<>());
        return snapshot;
    }

    private static FormDefSnapshot.Field field(String name, String column, String type, int sort) {
        FormDefSnapshot.Field field = new FormDefSnapshot.Field();
        field.setFieldName(name);
        field.setColumnName(column);
        field.setFieldType(type);
        field.setSort(sort);
        return field;
    }

    /** 主从夹具：从表字段内嵌主清单（归属标记=关系标识；leave_memo 一组供双关系用例） */
    private static FormDefSnapshot masterSubSnapshot() {
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_MASTER_SUB);
        FormDefSnapshot.Field subName = field("itemName", "ITEM_NAME", "STRING", 2);
        subName.setSubFormKey("leave_item");
        FormDefSnapshot.Field subFk = field("leaveId", "LEAVE_ID", "STRING", 3);
        subFk.setSubFormKey("leave_item");
        FormDefSnapshot.Field memoName = field("memoText", "MEMO_TEXT", "STRING", 4);
        memoName.setSubFormKey("leave_memo");
        FormDefSnapshot.Field memoFk = field("memoLeaveId", "LEAVE_ID", "STRING", 5);
        memoFk.setSubFormKey("leave_memo");
        snapshot.setFields(List.of(field("applicant", "applicant", "STRING", 1), subName, subFk, memoName, memoFk));
        return snapshot;
    }

    /** 关系夹具：关系标识 + 直连物理表 + 外键列 */
    private static FormDefSnapshot.Relation relationOf(String subFormKey, String foreignKey, String subTable) {
        FormDefSnapshot.Relation relation = new FormDefSnapshot.Relation();
        relation.setSubFormKey(subFormKey);
        relation.setSubTable(subTable);
        relation.setForeignKey(foreignKey);
        return relation;
    }

    @Test
    void auditColumnsAllowedOnlyAsDisplayFields() {
        // 不落库展示字段：允许映射审计列
        FormDefSnapshot display = snapshot(FormConstants.FORM_TYPE_SINGLE);
        FormDefSnapshot.Field createdAt = field("createdAt", "CREATED_AT", "DATETIME", 1);
        createdAt.setPersisted(Boolean.FALSE);
        display.setFields(List.of(createdAt));
        FormModel model = compiler.compile(snapshotJson.write(display), 1L);
        assertThat(model.fields().getFirst().persisted()).isFalse();

        // 落库映射审计列：拒绝（写入由引擎独占）
        FormDefSnapshot writable = snapshot(FormConstants.FORM_TYPE_SINGLE);
        writable.setFields(List.of(field("createdAt", "CREATED_AT", "DATETIME", 1)));
        assertErrorCode(() -> compiler.compile(snapshotJson.write(writable), 1L), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void uniqueRuleRequiresPersistedIndexableField() {
        // TEXT/FILE 内容长且不可索引：拒绝
        FormDefSnapshot textSnapshot = snapshot(FormConstants.FORM_TYPE_SINGLE);
        FormDefSnapshot.Field text = field("content", "CONTENT", "TEXT", 1);
        text.getForm().setRules(Map.of("unique", true));
        textSnapshot.setFields(List.of(text));
        assertErrorCode(() -> compiler.compile(snapshotJson.write(textSnapshot), 1L), FormErrorCode.FORM_TABLE_INVALID);

        // 不落库字段无存储语义：拒绝
        FormDefSnapshot displaySnapshot = snapshot(FormConstants.FORM_TYPE_SINGLE);
        FormDefSnapshot.Field display = field("name", "NAME", "STRING", 1);
        display.setPersisted(false);
        display.getForm().setRules(Map.of("unique", true));
        displaySnapshot.setFields(List.of(display));
        assertErrorCode(
                () -> compiler.compile(snapshotJson.write(displaySnapshot), 1L), FormErrorCode.FORM_TABLE_INVALID);

        // 落库 STRING 字段：放行并携带规则
        FormDefSnapshot ok = snapshot(FormConstants.FORM_TYPE_SINGLE);
        FormDefSnapshot.Field code = field("code", "CODE", "STRING", 1);
        code.getForm().setRules(Map.of("unique", true));
        ok.setFields(List.of(code));
        assertThat(compiler.compile(snapshotJson.write(ok), 1L)
                        .fields()
                        .getFirst()
                        .form()
                        .rules())
                .containsEntry("unique", true);
    }
}
