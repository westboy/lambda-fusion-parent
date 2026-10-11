package com.lambda.fusion.form.design.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.design.support.TableMetadataReader;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.support.FormDefSnapshot;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.form.runtime.support.SnapshotJson;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 代码生成器：快照 → 契约骨架（包名校验/实体字段/Controller 权限码/zip 结构）。 */
class FormCodegenServiceTest {

    private FormModelProvider modelProvider;
    private TableMetadataReader metadataReader;
    private FormCodegenService service;

    @BeforeEach
    void setUp() {
        modelProvider = mock(FormModelProvider.class);
        metadataReader = mock(TableMetadataReader.class);
        when(metadataReader.listColumns(any(), any()))
                .thenReturn(List.of(
                        column("CREATED_BY"), column("CREATED_AT"),
                        column("UPDATED_BY"), column("UPDATED_AT")));
        service = new FormCodegenService(modelProvider, metadataReader);
    }

    private com.lambda.fusion.form.design.model.TableColumnView column(String name) {
        com.lambda.fusion.form.design.model.TableColumnView view =
                new com.lambda.fusion.form.design.model.TableColumnView();
        view.setColumnName(name);
        return view;
    }

    private FormDefSnapshot snapshot() {
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("leave_demo");
        snapshot.setFormName("请假演示");
        snapshot.setFormType(FormConstants.FORM_TYPE_SINGLE);
        snapshot.setTableName("LA_DEMO_LEAVE");
        FormDefSnapshot.Field applicant = new FormDefSnapshot.Field();
        applicant.setFieldName("applicant");
        applicant.setColumnName("APPLICANT");
        applicant.setFieldType("STRING");
        applicant.setLabel("申请人");
        FormDefSnapshot.Field days = new FormDefSnapshot.Field();
        days.setFieldName("days");
        days.setColumnName("DAYS");
        days.setFieldType("INT");
        days.setLabel("请假天数");
        snapshot.setFields(List.of(applicant, days));
        return snapshot;
    }

    @Test
    void generatesContractScaffoldFromPublishedSnapshot() {
        SnapshotJson json = new SnapshotJson(new ObjectMapper());
        FormModelProviderTestSupport.require(modelProvider, "leave_demo", json, snapshot());

        Map<String, String> files = service.generate("leave_demo", "com.demo.leave");

        assertThat(files)
                .containsKeys(
                        "src/main/java/com/demo/leave/domain/LaDemoLeave.java",
                        "src/main/java/com/demo/leave/mapper/LaDemoLeaveMapper.java",
                        "src/main/java/com/demo/leave/model/LaDemoLeaveDto.java",
                        "src/main/java/com/demo/leave/service/LaDemoLeaveService.java",
                        "src/main/java/com/demo/leave/service/impl/LaDemoLeaveServiceImpl.java",
                        "src/main/java/com/demo/leave/controller/LaDemoLeaveController.java",
                        "README.txt");

        String entity = files.get("src/main/java/com/demo/leave/domain/LaDemoLeave.java");
        assertThat(entity)
                .contains("package com.demo.leave.domain;")
                .contains("@TableName(\"LA_DEMO_LEAVE\")")
                .contains("public class LaDemoLeave {")
                .contains("@TableId(value = \"ID\", type = IdType.INPUT)")
                .contains("private String applicant;")
                .contains("private Long days;")
                .contains("申请人");
        // 主键字段（"id" 字段名）被引擎禁映射，实体主键由生成器固定产出
        assertThat(entity).doesNotContain("private String applicant applicant;");

        String controller = files.get("src/main/java/com/demo/leave/controller/LaDemoLeaveController.java");
        assertThat(controller)
                .contains("@SaCheckPermission(\"leave-demo:page\")")
                .contains("BeanUtil.toBean(dto, LaDemoLeave.class)");

        String dto = files.get("src/main/java/com/demo/leave/model/LaDemoLeaveDto.java");
        assertThat(dto).contains("@AutoConverter(target = LaDemoLeave.class, isReverse = true)");
    }

    @Test
    void zipContainsAllEntries() throws Exception {
        SnapshotJson json = new SnapshotJson(new ObjectMapper());
        FormModelProviderTestSupport.require(modelProvider, "leave_demo", json, snapshot());

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        service.zip(service.generate("leave_demo", "com.demo.leave"), out);

        int entries = 0;
        boolean sawEntity = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entry.getName().endsWith("LaDemoLeave.java")) sawEntity = true;
            }
        }
        assertThat(entries).isEqualTo(7);
        assertThat(sawEntity).isTrue();
    }

    @Test
    void rejectsIllegalPackageName() {
        SnapshotJson json = new SnapshotJson(new ObjectMapper());
        FormModelProviderTestSupport.require(modelProvider, "leave_demo", json, snapshot());

        assertThatThrownBy(() -> service.generate("leave_demo", "Com.Demo"))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.INVALID_PARAMETER.getCode()));
    }

    /** modelProvider.require(formKey) 打桩辅助（快照编译与生产同路径） */
    private static final class FormModelProviderTestSupport {

        static void require(FormModelProvider provider, String formKey, SnapshotJson json, FormDefSnapshot snapshot) {
            when(provider.require(formKey))
                    .thenReturn(new com.lambda.fusion.form.runtime.support.FormModelCompiler(json)
                            .compile(json.write(snapshot), 1L));
        }
    }
}
