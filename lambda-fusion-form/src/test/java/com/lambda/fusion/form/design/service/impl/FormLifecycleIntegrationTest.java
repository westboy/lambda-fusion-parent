package com.lambda.fusion.form.design.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;

import cn.hutool.extra.spring.SpringUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.lambda.autoconfig.MybatisPlusExtendProperties;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.FormProperties;
import com.lambda.fusion.form.design.assembler.FormSnapshotAssemblerImpl;
import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.design.mapper.FormDefVersionMapper;
import com.lambda.fusion.form.design.mapper.FormFieldMapper;
import com.lambda.fusion.form.design.mapper.FormRelationMapper;
import com.lambda.fusion.form.design.model.FormDefSaveCommand;
import com.lambda.fusion.form.design.model.FormDefViewConverterImpl;
import com.lambda.fusion.form.design.model.FormFieldItem;
import com.lambda.fusion.form.design.model.FormFieldItemConverterImpl;
import com.lambda.fusion.form.design.model.FormPreviewSource;
import com.lambda.fusion.form.design.model.FormRelationItem;
import com.lambda.fusion.form.design.model.FormRelationItemConverterImpl;
import com.lambda.fusion.form.design.service.FormDesignService;
import com.lambda.fusion.form.design.service.FormPreviewService;
import com.lambda.fusion.form.design.service.FormPublishService;
import com.lambda.fusion.form.design.support.TableMetadataReader;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.assembler.FormSchemaAssembler;
import com.lambda.fusion.form.runtime.mapper.DynamicTableMapper;
import com.lambda.fusion.form.runtime.service.DynamicDataService;
import com.lambda.fusion.form.runtime.service.impl.DynamicDataServiceImpl;
import com.lambda.fusion.form.runtime.support.FormLayout;
import com.lambda.fusion.form.runtime.support.FormModelCache;
import com.lambda.fusion.form.runtime.support.FormModelCompiler;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.form.runtime.support.QueryAstValidator;
import com.lambda.fusion.form.runtime.support.SnapshotJson;
import com.lambda.fusion.form.runtime.support.TargetTableContract;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;

/** 真实事务与 Mapper：自动发布权限、快照原子性、草稿冲突及生效版本切换。 */
class FormLifecycleIntegrationTest {

    private AnnotationConfigApplicationContext context;
    private FormDesignService design;
    private FormPublishService publish;
    private FormDefMapper definitions;
    private FormModelProvider models;
    private JdbcTemplate jdbc;

    @Test
    void invalidDatabaseDefaultRollsBackInsertAndValidDefaultIsAccepted() {
        jdbc.execute("ALTER TABLE LA_TEST_BUSINESS ALTER COLUMN PERSON_NAME SET DEFAULT 'bad'");
        FormDefSaveCommand command = command();
        command.getFields().getFirst().setNullable(false);
        command.getFields().getFirst().setFormConfig("{\"rules\":{\"pattern\":\"^OK$\"}}");
        try (var permissions = mockStatic(AuthUtils.class)) {
            String formId = design.save(command);
            publish.publish(formId);
            var model = models.require("test_form");
            DynamicDataService data = context.getBean(DynamicDataService.class);
            assertThatThrownBy(() -> data.create(model, Map.of()))
                    .isInstanceOf(FormBusinessException.class)
                    .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                            .isEqualTo(FormErrorCode.FORM_DATA_INVALID.getCode()));
            assertThat(count("LA_TEST_BUSINESS")).isZero();
            jdbc.execute("ALTER TABLE LA_TEST_BUSINESS ALTER COLUMN PERSON_NAME SET DEFAULT 'OK'");
            String id = data.create(model, Map.of());
            assertThat(data.getById(model, id)).containsEntry("personName", "OK");
            assertThat(count("LA_TEST_BUSINESS")).isEqualTo(1);
        }
    }

    @Test
    void publicationRollbackAndEnableRejectChangedPhysicalContractWithoutChangingVersion() {
        try (var permissions = mockStatic(AuthUtils.class)) {
            String id = design.save(command());
            publish.publish(id);
            var command = command();
            command.setFormName("Version two");
            command.setVersion(definitions.selectById(id).getVersion());
            design.update(id, command);
            publish.publish(id);
            publish.disable(id);
            jdbc.execute("ALTER TABLE LA_TEST_BUSINESS ALTER COLUMN ID VARCHAR(8)");
            assertThatThrownBy(() -> publish.rollback(id, 1L)).isInstanceOf(FormBusinessException.class);
            assertThatThrownBy(() -> publish.enable(id)).isInstanceOf(FormBusinessException.class);
            command.setFormName("Invalid version three");
            command.setVersion(definitions.selectById(id).getVersion());
            design.update(id, command);
            assertThatThrownBy(() -> publish.publish(id)).isInstanceOf(FormBusinessException.class);
            assertThat(definitions.selectById(id).getPublishedVersion()).isEqualTo(2);
            assertThat(definitions.selectById(id).getStatus()).isEqualTo("DISABLED");
            assertThat(count("la_form_def_version")).isEqualTo(2);
        }
    }

    @Test
    void layoutAndLabelsSurviveDraftPublishPreviewAndRollbackWithoutPreviewWrites() {
        FormPreviewService preview = context.getBean(FormPreviewService.class);
        FormDefSaveCommand command = command();
        command.getFields().getFirst().setLabel("区域名称");
        command.setFormLayout("{\"columns\":2,\"items\":[{\"field\":\"personName\",\"span\":2}]}");
        FormLayout original = new FormLayout(2, List.of(new FormLayout.Item("personName", 2)));
        try (var permissions = mockStatic(AuthUtils.class)) {
            assertThat(preview.preview(new FormPreviewSource(null, null, null, command))
                            .getFormLayout())
                    .isEqualTo(original);
            assertThat(count("la_form_def")).isZero();
            String id = design.save(command);
            assertThat(design.detail(id).getFields().getFirst().getLabel()).isEqualTo("区域名称");
            assertThat(preview.previewDraft(id).getFormLayout()).isEqualTo(original);
            assertThat(preview.previewDraft(id).getPermissions()).containsEntry("add", false);
            publish.publish(id);
            var draft = definitions.selectById(id);
            command.setVersion(draft.getVersion());
            command.setFormLayout("{\"columns\":3,\"items\":[{\"field\":\"personName\",\"span\":1}]}");
            command.getFields().getFirst().setLabel("姓名（新版）");
            design.update(id, command);
            assertThat(preview.previewDraft(id).getFormLayout().columns()).isEqualTo(3);
            assertThat(preview.previewVersion(id, 1).getFormLayout()).isEqualTo(original);
            assertThat(preview.previewVersion(id, 1).getFields().getFirst().getLabel())
                    .isEqualTo("区域名称");
            assertThat(models.require("test_form").formLayout()).isEqualTo(original);
            long versionBeforePreview = definitions.selectById(id).getVersion();
            command.setVersion(versionBeforePreview);
            preview.preview(new FormPreviewSource(id, null, null, command));
            preview.previewDraft(id);
            preview.previewVersion(id, 1);
            assertThat(definitions.selectById(id).getVersion()).isEqualTo(versionBeforePreview);
            assertThat(count("la_form_def_version")).isEqualTo(1);
            assertThat(count("LA_TEST_BUSINESS")).isZero();
            publish.publish(id);
            assertThat(models.require("test_form").formLayout().columns()).isEqualTo(3);
            publish.rollback(id, 1L);
            assertThat(models.require("test_form").formLayout()).isEqualTo(original);
            publish.disable(id);
            publish.enable(id);
            assertThat(models.require("test_form").version()).isEqualTo(1);
            assertThat(count("la_form_def_version")).isEqualTo(2);
        }
    }

    @Test
    void previewChecksOwnedDraftBeforeReadingVersion() {
        FormPreviewService preview = context.getBean(FormPreviewService.class);
        assertThatThrownBy(() -> preview.previewVersion("missing", 1))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_NOT_FOUND.getCode()));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"columns\":0,\"items\":[]}",
                "{\"columns\":2.5,\"items\":[]}",
                "{\"columns\":2,\"items\":[{\"field\":\"missing\",\"span\":1}]}",
                "{\"columns\":2,\"items\":[{\"field\":\"personName\",\"span\":3}]}",
                "{\"columns\":2,\"items\":[{\"field\":\"personName\",\"span\":1},{\"field\":\"personName\",\"span\":1}]}",
                "{\"columns\":2,\"items\":[null]}",
                "{\"columns\":2,\"items\":null}"
            })
    void invalidLayoutIsRejectedBeforeSaving(String layout) {
        FormDefSaveCommand command = command();
        command.setFormLayout(layout);
        assertThatThrownBy(() -> design.save(command)).isInstanceOf(FormBusinessException.class);
        assertThat(count("la_form_def")).isZero();
    }

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(Config.class);
        design = context.getBean(FormDesignService.class);
        publish = context.getBean(FormPublishService.class);
        definitions = context.getBean(FormDefMapper.class);
        models = context.getBean(FormModelProvider.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
        context.close();
    }

    @Test
    void automaticPublishCannotBypassPermissionAndRollsBackDraft() {
        FormDefSaveCommand command = command();
        command.setPublishOnSave(true);
        try (var permissions = mockStatic(AuthUtils.class)) {
            permissions
                    .when(() -> AuthUtils.checkPermission("form:design:publish"))
                    .thenThrow(new SecurityException("denied"));
            assertThatThrownBy(() -> design.save(command)).isInstanceOf(SecurityException.class);
            permissions.verify(() -> AuthUtils.checkPermission("form:design:publish"));
        }
        assertThat(count("la_form_def")).isZero();
        assertThat(count("la_form_field")).isZero();
        assertThat(count("la_form_def_version")).isZero();
    }

    @Test
    void invalidModelDoesNotPublishSnapshotOrChangePointer() {
        FormDefSaveCommand command = command();
        command.setFormType("TREE");
        // 保存期只做来源与映射校验；filterColumn 合法性由编译期拦截（模拟快照被篡改的发布失败路径）
        command.setTreeConfig(
                "{\"sourceType\":\"SELF\",\"parentColumn\":\"PERSON_NAME\",\"nodeColumn\":\"PERSON_NAME\",\"filterColumn\":\"BAD COLUMN\"}");
        String id = design.save(command);
        try (var permissions = mockStatic(AuthUtils.class)) {
            assertThatThrownBy(() -> publish.publish(id)).isInstanceOf(FormBusinessException.class);
        }
        assertThat(count("la_form_def_version")).isZero();
        assertThat(definitions.selectById(id).getPublishedVersion()).isNull();
        assertThat(definitions.selectById(id).getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void publishDisableAndRollbackUseDatabaseStateEvenWithWarmCache() {
        String id = design.save(command());
        try (var permissions = mockStatic(AuthUtils.class)) {
            publish.publish(id);
            assertThat(models.require("test_form").version()).isEqualTo(1);
            FormDefSaveCommand changed = command();
            changed.setFormName("第二版");
            changed.setVersion(definitions.selectById(id).getVersion());
            design.update(id, changed);
            assertThat(models.require("test_form").formName()).isEqualTo("第一版");
            publish.publish(id);
            assertThat(models.require("test_form").version()).isEqualTo(2);
            assertThat(models.require("test_form").formName()).isEqualTo("第二版");
            publish.rollback(id, 1L);
            assertThat(models.require("test_form").version()).isEqualTo(1);
            publish.disable(id);
            assertThatThrownBy(() -> models.require("test_form"))
                    .isInstanceOf(FormBusinessException.class)
                    .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                            .isEqualTo(FormErrorCode.FORM_DISABLED.getCode()));
        }
        assertThat(count("la_form_def_version")).isEqualTo(2);
    }

    @Test
    void updateRequiresMatchingVersionAndImmutableIdentity() {
        String id = design.save(command());
        FormDefSaveCommand update = command();
        assertThatThrownBy(() -> design.update(id, update)).isInstanceOf(FormBusinessException.class);
        update.setVersion(definitions.selectById(id).getVersion() + 1);
        assertThatThrownBy(() -> design.update(id, update)).isInstanceOf(FormBusinessException.class);
        update.setVersion(definitions.selectById(id).getVersion());
        update.setFormKey("renamed");
        assertThatThrownBy(() -> design.update(id, update)).isInstanceOf(FormBusinessException.class);
        assertThat(definitions.selectById(id).getFormKey()).isEqualTo("test_form");
        assertThat(count("la_form_field")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ID", "TENANT_ID", "created_by", "CREATED_AT", "UPDATED_BY", "UPDATED_AT"})
    void saveRejectsSystemColumnMappings(String column) {
        FormDefSaveCommand command = command();
        command.getFields().getFirst().setColumnName(column);
        assertThatThrownBy(() -> design.save(command)).isInstanceOf(FormBusinessException.class);
        assertThat(count("la_form_def")).isZero();
    }

    @Test
    void saveRejectsTablesOutsideExplicitAllowList() {
        FormDefSaveCommand command = command();
        command.setTableName("la_area");
        assertThatThrownBy(() -> design.save(command)).isInstanceOf(FormBusinessException.class);
        assertThat(count("la_form_def")).isZero();
    }

    @Test
    void saveRejectsRelationToMissingSubForm() {
        FormDefSaveCommand command = command();
        command.setFormType("MASTER_SUB");
        command.setRelations(List.of(relation("missing_sub", "AREA_ID")));
        assertThatThrownBy(() -> design.save(command))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertRelationDetail(error, "子表单不存在"));
        assertThat(count("la_form_def")).isZero();
    }

    @Test
    void saveRejectsRelationToNonSingleSubForm() {
        FormDefSaveCommand sub = command();
        sub.setFormKey("tree_sub");
        sub.setFormType("TREE");
        sub.setTreeConfig("{\"sourceType\":\"SELF\",\"parentColumn\":\"PERSON_NAME\",\"nodeColumn\":\"PERSON_NAME\"}");
        design.save(sub);
        FormDefSaveCommand command = command();
        command.setFormType("MASTER_SUB");
        command.setRelations(List.of(relation("tree_sub", "PERSON_NAME")));
        assertThatThrownBy(() -> design.save(command))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertRelationDetail(error, "子表单须为单表类型"));
    }

    @Test
    void saveRejectsForeignKeyNotMappedInSubForm() {
        FormDefSaveCommand sub = command();
        sub.setFormKey("sub_form");
        design.save(sub);
        FormDefSaveCommand command = command();
        command.setFormType("MASTER_SUB");
        command.setRelations(List.of(relation("sub_form", "MISSING_ID")));
        assertThatThrownBy(() -> design.save(command))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertRelationDetail(error, "子表外键未在子表单映射"));
    }

    @Test
    void saveRejectsSelfReferencingAndDuplicatedRelations() {
        FormDefSaveCommand command = command();
        command.setFormType("MASTER_SUB");
        command.setRelations(List.of(relation("test_form", "PERSON_NAME")));
        assertThatThrownBy(() -> design.save(command))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertRelationDetail(error, "子表单不能引用自身"));

        FormDefSaveCommand sub = command();
        sub.setFormKey("sub_form");
        design.save(sub);
        FormDefSaveCommand duplicated = command();
        duplicated.setFormType("MASTER_SUB");
        duplicated.setRelations(List.of(relation("sub_form", "PERSON_NAME"), relation("sub_form", "PERSON_NAME")));
        assertThatThrownBy(() -> design.save(duplicated))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertRelationDetail(error, "子表单关系重复"));
    }

    @Test
    void masterSubDraftAcceptsRelationMappedBySubFormField() {
        FormDefSaveCommand sub = command();
        sub.setFormKey("sub_form");
        design.save(sub);

        FormDefSaveCommand command = command();
        command.setFormType("MASTER_SUB");
        FormRelationItem relation = relation("sub_form", "PERSON_NAME");
        relation.setSubTableConfig("{\"columns\":[{\"field\":\"personName\",\"width\":120}]}");
        command.setRelations(List.of(relation));
        String id = design.save(command);
        assertThat(definitions.selectById(id).getFormType()).isEqualTo("MASTER_SUB");
        assertThat(count("la_form_def")).isEqualTo(2);
        assertThat(count("la_form_relation")).isEqualTo(1);
    }

    @Test
    void saveRejectsSubTableColumnNotMappedInSubForm() {
        FormDefSaveCommand sub = command();
        sub.setFormKey("sub_form");
        design.save(sub);

        FormDefSaveCommand command = command();
        command.setFormType("MASTER_SUB");
        FormRelationItem relation = relation("sub_form", "PERSON_NAME");
        relation.setSubTableConfig("{\"columns\":[{\"field\":\"missing\"}]}");
        command.setRelations(List.of(relation));
        assertThatThrownBy(() -> design.save(command))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertRelationDetail(error, "子表列表列未在子表单映射"));
    }

    @Test
    void saveRejectsMappedColumnMissingInTargetTable() {
        FormDefSaveCommand command = command();
        command.getFields().getFirst().setColumnName("PARENT_ID");
        assertThatThrownBy(() -> design.save(command))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertRelationDetail(error, "PARENT_ID"));
        assertThat(count("la_form_def")).isZero();
    }

    @Test
    void saveRejectsInvalidRelationMode() {
        FormDefSaveCommand sub = command();
        sub.setFormKey("sub_form");
        design.save(sub);

        FormDefSaveCommand command = command();
        command.setFormType("MASTER_SUB");
        FormRelationItem relation = relation("sub_form", "PERSON_NAME");
        relation.setMode("GRID");
        command.setRelations(List.of(relation));
        assertThatThrownBy(() -> design.save(command))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertRelationDetail(error, "主从呈现方式非法"));
    }

    /** 业务异常的中文明细存于 args 而非 message，据此断言具体拦截原因 */
    private static void assertRelationDetail(Throwable error, String expectedDetail) {
        assertThat(String.valueOf(((FormBusinessException) error).getArgs()[0])).contains(expectedDetail);
    }

    private static FormRelationItem relation(String subFormKey, String foreignKey) {
        FormRelationItem item = new FormRelationItem();
        item.setSubFormKey(subFormKey);
        item.setForeignKey(foreignKey);
        return item;
    }

    @Test
    void concurrentPublishHasOneWinnerAndNoOrphanSnapshot() throws Exception {
        String id = design.save(command());
        CyclicBarrier reads = new CyclicBarrier(2);
        doAnswer(invocation -> {
                    FormDefEntity entity = (FormDefEntity) invocation.callRealMethod();
                    reads.await(10, TimeUnit.SECONDS);
                    return entity;
                })
                .when(definitions)
                .selectById(id);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> publishResult(id));
            var second = workers.submit(() -> publishResult(id));
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, FormErrorCode.FORM_DRAFT_CONFLICT.getCode());
        }
        assertThat(count("la_form_def_version")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT PUBLISHED_VERSION FROM la_form_def WHERE ID=?", Long.class, id))
                .isEqualTo(1);
    }

    private int publishResult(String id) {
        try (var permissions = mockStatic(AuthUtils.class)) {
            publish.publish(id);
            return 0;
        } catch (FormBusinessException error) {
            return error.getCode();
        }
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static FormDefSaveCommand command() {
        FormDefSaveCommand command = new FormDefSaveCommand();
        command.setFormKey("test_form");
        command.setFormName("第一版");
        command.setFormType("SINGLE");
        command.setTableName("LA_TEST_BUSINESS");
        FormFieldItem field = new FormFieldItem();
        field.setFieldName("personName");
        field.setColumnName("PERSON_NAME");
        field.setFieldType("STRING");
        command.setFields(List.of(field));
        return command;
    }

    @Test
    void businessFormCannotUseTheDesignManagementPermissionNamespace() {
        FormDefSaveCommand command = command();
        command.setFormKey("design");
        assertThatThrownBy(() -> design.save(command))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_KEY_RESERVED.getCode()));
        assertThat(count("la_form_def")).isZero();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({
        FormDesignServiceImpl.class,
        FormPublishServiceImpl.class,
        FormSnapshotAssemblerImpl.class,
        FormSchemaAssembler.class,
        FormPreviewServiceImpl.class,
        SpringUtil.class,
        FormDefViewConverterImpl.class,
        FormFieldItemConverterImpl.class,
        FormRelationItemConverterImpl.class,
        TableMetadataReader.class,
        TargetTableContract.class,
        DynamicDataServiceImpl.class,
        QueryAstValidator.class,
        FormModelProvider.class,
        FormModelCache.class,
        FormModelCompiler.class,
        SnapshotJson.class
    })
    static class Config {
        @Bean
        MybatisPlusExtendProperties mybatisProperties() {
            return new MybatisPlusExtendProperties();
        }

        @Bean
        DataSource dataSource() {
            DriverManagerDataSource source = new DriverManagerDataSource(
                    "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            JdbcTemplate jdbc = new JdbcTemplate(source);
            String audit =
                    ", TENANT_ID VARCHAR(64), CREATED_BY VARCHAR(64), CREATED_AT TIMESTAMP, UPDATED_BY VARCHAR(64), UPDATED_AT TIMESTAMP)";
            jdbc.execute(
                    """
                    CREATE TABLE la_form_def (ID VARCHAR(32) PRIMARY KEY, FORM_KEY VARCHAR(64), FORM_NAME VARCHAR(128),
                    FORM_TYPE VARCHAR(16), TABLE_NAME VARCHAR(64), TREE_CONFIG CLOB, FLOW_CODE VARCHAR(64),
                    STATUS VARCHAR(16), PUBLISHED_VERSION BIGINT, TITLE_TEMPLATE VARCHAR(256), INTERCEPTOR VARCHAR(128),
                    PUBLISH_ON_SAVE BOOLEAN, LOGIC_DELETE_COLUMN VARCHAR(64),
                    LIST_CONFIG CLOB, FORM_LAYOUT CLOB, ROUTE_RESOURCE_ID VARCHAR(32), VERSION BIGINT DEFAULT 0
                    """
                            + audit);
            jdbc.execute(
                    """
                    CREATE TABLE la_form_field (ID VARCHAR(32) PRIMARY KEY, FORM_ID VARCHAR(32), FIELD_NAME VARCHAR(64),
                    FIELD_LABEL VARCHAR(128), COLUMN_NAME VARCHAR(64), FIELD_TYPE VARCHAR(16), NULLABLE BOOLEAN, DEFAULT_VALUE VARCHAR(512),
                    DICT_TYPE VARCHAR(64), QUERY_CONFIG CLOB, LIST_CONFIG CLOB, FORM_CONFIG CLOB, PERSISTED BOOLEAN, SORT INT
                    """
                            + audit);
            jdbc.execute(
                    """
                    CREATE TABLE la_form_relation (ID VARCHAR(32) PRIMARY KEY, FORM_ID VARCHAR(32), SUB_FORM_KEY VARCHAR(64),
                    FOREIGN_KEY VARCHAR(64), CASCADE_STRATEGY VARCHAR(16), RELATION_MODE VARCHAR(16) DEFAULT 'INLINE' NOT NULL, SUB_TABLE_CONFIG VARCHAR(2048)
                    """
                            + audit);
            jdbc.execute(
                    """
                    CREATE TABLE la_form_def_version (ID VARCHAR(32) PRIMARY KEY, FORM_ID VARCHAR(32), VERSION BIGINT,
                    SNAPSHOT_JSON CLOB, CONTENT_MD5 VARCHAR(32), PUBLISHED_BY VARCHAR(64), PUBLISHED_AT TIMESTAMP,
                    UNIQUE(FORM_ID, VERSION)
                    """
                            + audit);
            jdbc.execute("CREATE TABLE LA_TEST_BUSINESS (ID VARCHAR(32) PRIMARY KEY, PERSON_NAME VARCHAR(128))");
            return source;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        FormProperties properties() {
            FormProperties properties = new FormProperties();
            properties.setAllowedTables(List.of("LA_TEST_BUSINESS"));
            return properties;
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource source) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), source));
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            configuration.addInterceptor(interceptor);
            configuration.addMapper(FormDefMapper.class);
            configuration.addMapper(FormFieldMapper.class);
            configuration.addMapper(FormRelationMapper.class);
            configuration.addMapper(FormDefVersionMapper.class);
            configuration.addMapper(DynamicTableMapper.class);
            return new MybatisSqlSessionFactoryBuilder().build(configuration);
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        FormDefMapper formDefMapper(SqlSessionTemplate template) {
            return spy(template.getMapper(FormDefMapper.class));
        }

        @Bean
        FormFieldMapper formFieldMapper(SqlSessionTemplate template) {
            return template.getMapper(FormFieldMapper.class);
        }

        @Bean
        FormRelationMapper formRelationMapper(SqlSessionTemplate template) {
            return template.getMapper(FormRelationMapper.class);
        }

        @Bean
        FormDefVersionMapper formDefVersionMapper(SqlSessionTemplate template) {
            return template.getMapper(FormDefVersionMapper.class);
        }

        @Bean
        DynamicTableMapper dynamicTableMapper(SqlSessionTemplate template) {
            return template.getMapper(DynamicTableMapper.class);
        }
    }
}
