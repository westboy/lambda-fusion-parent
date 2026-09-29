package com.lambda.fusion.form.runtime.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    void rejectsNonSelfTreeSource() {
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_TREE);
        snapshot.setFields(List.of(field("parent_id", "parent_id", "STRING", 1)));
        snapshot.setTreeConfig("{\"sourceType\":\"DICT\",\"dictType\":\"leave_type\"}");

        assertErrorCode(() -> compiler.compile(snapshotJson.write(snapshot), 1L), FormErrorCode.FORM_TABLE_INVALID);
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
    void compilesSelfTreeWithFilterColumnDefaultingToParentColumn() {
        String treeConfig = "{\"sourceType\":\"SELF\",\"parentColumn\":\"parent_id\",\"nodeColumn\":\"name\"}";
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_TREE);
        snapshot.setFields(List.of(field("name", "name", "STRING", 1), field("parentId", "parent_id", "STRING", 2)));
        snapshot.setTreeConfig(treeConfig);

        FormModel model = compiler.compile(snapshotJson.write(snapshot), 2L);

        assertThat(model.tree()).isNotNull();
        assertThat(model.tree().sourceType()).isEqualTo("SELF");
        assertThat(model.tree().parentColumn()).isEqualTo("PARENT_ID");
        assertThat(model.tree().nodeColumn()).isEqualTo("NAME");
        assertThat(model.tree().filterColumn()).isEqualTo("PARENT_ID");
        assertThat(model.tree().selfRelation()).isTrue();
        assertThat(model.treeConfig()).isEqualTo(treeConfig);
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
        FormDefSnapshot snapshot = snapshot(FormConstants.FORM_TYPE_MASTER_SUB);
        snapshot.setFields(List.of(field("applicant", "applicant", "STRING", 1)));
        FormDefSnapshot.Relation relation = new FormDefSnapshot.Relation();
        relation.setSubFormKey("leave_item");
        relation.setForeignKey("leave_id");
        snapshot.setRelations(List.of(relation));

        FormModel model = compiler.compile(snapshotJson.write(snapshot), 1L);

        assertThat(model.relations()).hasSize(1);
        assertThat(model.relations().get(0).subFormKey()).isEqualTo("leave_item");
        assertThat(model.relations().get(0).foreignKey()).isEqualTo("LEAVE_ID");
        assertThat(model.relations().get(0).cascadeStrategy()).isEqualTo(FormConstants.CASCADE_DELETE);
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
}
