package com.lambda.fusion.form.runtime.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.lambda.autoconfig.MybatisPlusExtendProperties;
import com.lambda.fusion.form.FormProperties;
import com.lambda.fusion.form.design.support.TableMetadataReader;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.runtime.mapper.DynamicTableMapper;
import com.lambda.fusion.form.runtime.model.AstCondition;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import com.lambda.fusion.form.runtime.support.FormDefSnapshot;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModelCompiler;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.form.runtime.support.QueryAstValidator;
import com.lambda.fusion.form.runtime.support.SnapshotJson;
import com.lambda.fusion.form.runtime.support.TargetTableContract;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/** 使用真实 Mapper 与租户插件验证 SQL 及前后端行协议。 */
class DynamicDataServiceJdbcTest {

    private SqlSession session;
    private JdbcTemplate jdbc;
    private DynamicDataServiceImpl service;
    private String tenant = "tenantA";
    private boolean ignoreTiny = true;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                """
                CREATE TABLE LA_TEST_FORM (
                    ID VARCHAR(32) PRIMARY KEY, PERSON_NAME VARCHAR(128) NOT NULL,
                    NOTE VARCHAR(128), AMOUNT DECIMAL(10,2), PARENT_ID VARCHAR(32),
                    DELETED INT NOT NULL DEFAULT 0, TENANT_ID VARCHAR(32),
                    CREATED_BY VARCHAR(64), CREATED_AT TIMESTAMP, UPDATED_BY VARCHAR(64), UPDATED_AT TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE LA_TEST_TINY (ID VARCHAR(32) PRIMARY KEY, PERSON_NAME VARCHAR(128))");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return tenant == null ? null : new StringValue(tenant);
            }

            @Override
            public boolean ignoreTable(String tableName) {
                return "LA_TEST_TINY".equalsIgnoreCase(tableName) && ignoreTiny;
            }
        }));
        configuration.addInterceptor(interceptor);
        configuration.addMapper(DynamicTableMapper.class);
        var factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(true);
        FormProperties properties = new FormProperties();
        properties.setAllowedTables(List.of("LA_TEST_FORM", "LA_TEST_TINY"));
        var mybatisProperties = new MybatisPlusExtendProperties();
        mybatisProperties.getTenant().setEnabled(true);
        service = new DynamicDataServiceImpl(
                session.getMapper(DynamicTableMapper.class),
                new QueryAstValidator(new FormProperties()),
                mock(FormModelProvider.class),
                new TargetTableContract(new TableMetadataReader(dataSource, properties), factory, mybatisProperties));
    }

    @AfterEach
    void tearDown() {
        session.close();
        jdbc.execute("DROP ALL OBJECTS");
    }

    @ParameterizedTest
    @MethodSource("invalidWrites")
    void invalidCreateAndUpdateNeverReachBusinessTable(String fieldName, Object value) {
        FormModel model = validationModel();
        Map<String, Object> input = new HashMap<>(Map.of("personName", "AB", "amount", 10));
        input.put(fieldName, value);
        assertThatThrownBy(() -> service.create(model, input)).isInstanceOf(FormBusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LA_TEST_TINY", Integer.class))
                .isZero();
        String id = service.create(model, Map.of("personName", "AB", "amount", 10));
        assertThatThrownBy(() -> service.update(model, id, input)).isInstanceOf(FormBusinessException.class);
        assertThat(jdbc.queryForObject("SELECT PERSON_NAME FROM LA_TEST_TINY WHERE ID=?", String.class, id))
                .isEqualTo("AB");
        assertThat(jdbc.queryForObject("SELECT AMOUNT FROM LA_TEST_TINY WHERE ID=?", java.math.BigDecimal.class, id))
                .isEqualByComparingTo("10");
    }

    static Stream<Arguments> invalidWrites() {
        return Stream.of(
                Arguments.of("personName", "ABCDEF"),
                Arguments.of("personName", "a1"),
                Arguments.of("personName", "  "),
                Arguments.of("amount", "1.234"),
                Arguments.of("amount", -1),
                Arguments.of("amount", 51),
                Arguments.of("quantity", 2147483648L),
                Arguments.of("quantity", 1.5),
                Arguments.of("flag", "invalid"),
                Arguments.of("day", "2026-02-29"),
                Arguments.of("moment", "2026-10-01 12:00:00.1234"));
    }

    @Test
    void defaultsAreTypedExplicitNullClearsAndDatabaseDefaultOnlyAppliesToOmission() {
        FormModel model = validationModel();
        String id = service.create(
                model, Map.of("personName", "AB", "day", "2024-02-29", "moment", "2026-10-01 12:00:00.123"));
        assertThat(jdbc.queryForObject("SELECT AMOUNT FROM LA_TEST_TINY WHERE ID=?", java.math.BigDecimal.class, id))
                .isEqualByComparingTo("0.25");
        assertThat(jdbc.queryForObject("SELECT FLAG FROM LA_TEST_TINY WHERE ID=?", Boolean.class, id))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT DB_VALUE FROM LA_TEST_TINY WHERE ID=?", String.class, id))
                .isEqualTo("database");
        Map<String, Object> clear = new HashMap<>();
        clear.put("amount", null);
        service.update(model, id, clear);
        assertThat(service.getById(model, id)).containsEntry("amount", null).containsEntry("personName", "AB");
        clear.put("personName", "CD");
        String second = service.create(model, clear);
        assertThat(service.getById(model, second)).containsEntry("amount", null);
    }

    @Test
    void omittedRequiredFieldCanUseDatabaseDefaultButExplicitNullCannot() {
        jdbc.execute("ALTER TABLE LA_TEST_TINY ALTER COLUMN PERSON_NAME SET DEFAULT 'database'");
        jdbc.execute("ALTER TABLE LA_TEST_TINY ALTER COLUMN PERSON_NAME SET NOT NULL");
        FormModel model = model(false, true, false);
        String id = service.create(model, Map.of());
        assertThat(service.getById(model, id)).containsEntry("personName", "database");
        Map<String, Object> clear = new HashMap<>();
        clear.put("personName", null);
        assertThatThrownBy(() -> service.create(model, clear)).isInstanceOf(FormBusinessException.class);
    }

    @Test
    void numericPrimaryKeyStillReturnsStringIdAndSchemaDriftRejectsFurtherWrites() {
        jdbc.execute("DROP TABLE LA_TEST_TINY");
        jdbc.execute("CREATE TABLE LA_TEST_TINY (ID BIGINT PRIMARY KEY, PERSON_NAME VARCHAR(128))");
        FormModel model = model(false, true, false);
        String id = service.create(model, Map.of("personName", "Alice"));
        assertThat(service.getById(model, id)).containsEntry("id", id);
        assertThat(service.page(model, query(null, null, null)).getRecords().getFirst())
                .containsEntry("id", id);
        jdbc.execute("ALTER TABLE LA_TEST_TINY ADD REQUIRED_VALUE INT DEFAULT 0 NOT NULL");
        jdbc.execute("ALTER TABLE LA_TEST_TINY ALTER COLUMN REQUIRED_VALUE DROP DEFAULT");
        assertThatThrownBy(() -> service.create(model, Map.of("personName", "Bob")))
                .isInstanceOf(FormBusinessException.class);
        assertThatThrownBy(() -> service.update(model, id, Map.of("personName", "Bob")))
                .isInstanceOf(FormBusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LA_TEST_TINY", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void changingTenantPolicyAfterPublicationCannotExposeOtherTenantsRows() {
        jdbc.execute("ALTER TABLE LA_TEST_TINY ADD TENANT_ID VARCHAR(32)");
        ignoreTiny = false;
        FormModel model = model(false, true, false);
        String id = service.create(model, Map.of("personName", "Alice"));
        tenant = "tenantB";
        ignoreTiny = true;
        session.clearCache();
        assertThatThrownBy(() -> service.page(model, query(null, null, null)))
                .isInstanceOf(FormBusinessException.class);
        assertThatThrownBy(() -> service.getById(model, id)).isInstanceOf(FormBusinessException.class);
        assertThatThrownBy(() -> service.update(model, id, Map.of("personName", "Bob")))
                .isInstanceOf(FormBusinessException.class);
        assertThatThrownBy(() -> service.delete(model, id)).isInstanceOf(FormBusinessException.class);
        assertThat(jdbc.queryForObject("SELECT PERSON_NAME FROM LA_TEST_TINY WHERE ID=?", String.class, id))
                .isEqualTo("Alice");
        ignoreTiny = false;
        tenant = null;
        assertThatThrownBy(() -> service.getById(model, id)).isInstanceOf(FormBusinessException.class);
    }

    private FormModel validationModel() {
        jdbc.execute("ALTER TABLE LA_TEST_TINY ALTER COLUMN PERSON_NAME VARCHAR(5)");
        jdbc.execute("ALTER TABLE LA_TEST_TINY ADD AMOUNT DECIMAL(4,2) DEFAULT 9.99");
        jdbc.execute("ALTER TABLE LA_TEST_TINY ADD QUANTITY INT");
        jdbc.execute("ALTER TABLE LA_TEST_TINY ADD FLAG BOOLEAN");
        jdbc.execute("ALTER TABLE LA_TEST_TINY ADD DAY_VALUE DATE");
        jdbc.execute("ALTER TABLE LA_TEST_TINY ADD MOMENT TIMESTAMP(3)");
        jdbc.execute("ALTER TABLE LA_TEST_TINY ADD DB_VALUE VARCHAR(20) NOT NULL DEFAULT 'database'");
        var name = field("personName", "PERSON_NAME", "STRING", true);
        name.getForm().setRules(Map.of("required", true, "min", 2, "pattern", "^[A-Z]+$"));
        var amount = field("amount", "AMOUNT", "DECIMAL", true);
        amount.setDefaultValue("0.25");
        amount.getForm().setRules(Map.of("min", 0, "max", 50));
        var flag = field("flag", "FLAG", "BOOL", true);
        flag.setDefaultValue("false");
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("validation_form");
        snapshot.setFormType("SINGLE");
        snapshot.setTableName("LA_TEST_TINY");
        snapshot.setSkipContractColumns(true);
        snapshot.setFields(List.of(
                name,
                amount,
                flag,
                field("quantity", "QUANTITY", "INT", true),
                field("day", "DAY_VALUE", "DATE", true),
                field("moment", "MOMENT", "DATETIME", true)));
        SnapshotJson json = new SnapshotJson(new ObjectMapper());
        return new FormModelCompiler(json).compile(json.write(snapshot), 1);
    }

    @Test
    void projectsPhysicalColumnsToFieldNamesAndKeepsNullValues() {
        FormModel model = model(false, false, false);
        String id = service.create(model, Map.of("personName", "Alice"));
        assertThat(service.getById(model, id))
                .containsEntry("id", id)
                .containsEntry("personName", "Alice")
                .containsEntry("note", null)
                .doesNotContainKeys("ID", "PERSON_NAME", "TENANT_ID");
        assertThat(service.page(model, query(null, null, null)).getRecords().getFirst())
                .containsEntry("id", id)
                .containsEntry("personName", "Alice");
    }

    @Test
    void bindsEachInValueAndTreatsSqlTextAsData() {
        FormModel model = model(false, false, false);
        String special = "x') OR 1=1 --";
        service.create(model, Map.of("personName", "Alice"));
        service.create(model, Map.of("personName", special));
        service.create(model, Map.of("personName", "Other"));
        assertThat(service.page(model, query("personName", "in", List.of("Alice", special)))
                        .getTotal())
                .isEqualTo(2);
        assertThat(service.page(model, query("personName", "like", special)).getTotal())
                .isEqualTo(1);
        assertThatThrownBy(() -> service.page(model, query("personName", "in", List.of())))
                .isInstanceOf(FormBusinessException.class);
    }

    @Test
    void supportsZeroInRangeAndNullConditions() {
        FormModel model = model(false, false, false);
        service.create(model, Map.of("personName", "Zero", "amount", 0));
        service.create(model, Map.of("personName", "Ten", "amount", 10, "note", "text"));
        service.create(model, Map.of("personName", "Outside", "amount", 20));
        assertThat(service.page(model, query("amount", "between", List.of(0, 10)))
                        .getTotal())
                .isEqualTo(2);
        assertThat(service.page(model, query("note", "isNull", null)).getTotal())
                .isEqualTo(2);
        assertThat(service.page(model, query("note", "isNotNull", null)).getTotal())
                .isEqualTo(1);
    }

    @Test
    void clearsExplicitNullAndRetainsAbsentFields() {
        FormModel model = model(false, false, false);
        String id = service.create(model, Map.of("personName", "Alice", "note", "old", "amount", 10));
        Map<String, Object> update = new HashMap<>();
        update.put("note", null);
        service.update(model, id, update);
        assertThat(service.getById(model, id))
                .containsEntry("personName", "Alice")
                .containsEntry("note", null);
        update.put("personName", null);
        assertThatThrownBy(() -> service.update(model, id, update)).isInstanceOf(FormBusinessException.class);
        assertThatThrownBy(() -> service.update(model, id, Map.of("unknown", 1)))
                .isInstanceOf(FormBusinessException.class);
    }

    @Test
    void deletedRowsDisappearFromPageDetailTreeAndWrites() {
        FormModel model = model(true, false, true);
        String id = service.create(model, Map.of("personName", "Root", "deleted", 1));
        assertThat(jdbc.queryForObject("SELECT DELETED FROM LA_TEST_FORM WHERE ID=?", Integer.class, id))
                .isZero();
        assertThat(service.tree(model, null)).hasSize(1);
        service.delete(model, id);
        assertThat(service.page(model, query(null, null, null)).getTotal()).isZero();
        assertThat(service.tree(model, null)).isEmpty();
        assertThatThrownBy(() -> service.getById(model, id)).isInstanceOf(FormBusinessException.class);
        assertThatThrownBy(() -> service.update(model, id, Map.of("personName", "Revived", "deleted", 0)))
                .isInstanceOf(FormBusinessException.class);
        assertThatThrownBy(() -> service.delete(model, id)).isInstanceOf(FormBusinessException.class);
    }

    @Test
    void cannotSetDeleteFlagThroughNormalUpdate() {
        FormModel model = model(true, false, false);
        String id = service.create(model, Map.of("personName", "Alice"));
        service.update(model, id, Map.of("personName", "Bob", "deleted", 1));
        assertThat(service.getById(model, id))
                .containsEntry("personName", "Bob")
                .containsEntry("deleted", 0);
    }

    @Test
    void tenantPluginStillIsolatesReadsAndWritesAndIgnoresForgedTenantPayload() {
        FormModel model = model(false, false, false);
        String id = service.create(model, Map.of("personName", "Alice", "TENANT_ID", "tenantB", "tenantId", "tenantB"));
        assertThat(jdbc.queryForObject("SELECT TENANT_ID FROM LA_TEST_FORM WHERE ID=?", String.class, id))
                .isEqualTo("tenantA");
        tenant = "tenantB";
        session.clearCache();
        assertThat(service.page(model, query(null, null, null)).getTotal()).isZero();
        assertThatThrownBy(() -> service.getById(model, id)).isInstanceOf(FormBusinessException.class);
        assertThatThrownBy(() -> service.update(model, id, Map.of("personName", "Other")))
                .isInstanceOf(FormBusinessException.class);
        assertThatThrownBy(() -> service.delete(model, id)).isInstanceOf(FormBusinessException.class);
    }

    @Test
    void contractExemptTableSupportsPaginationUpdatesAndPhysicalDelete() {
        FormModel model = model(false, true, false);
        String id = service.create(model, Map.of("personName", "Alice"));
        assertThat(service.page(model, query(null, null, null)).getRecords()).hasSize(1);
        service.update(model, id, Map.of("personName", "Bob"));
        assertThat(service.getById(model, id)).containsEntry("personName", "Bob");
        assertThatThrownBy(() -> service.update(model, id, Map.of())).isInstanceOf(FormBusinessException.class);
        service.delete(model, id);
        assertThat(service.page(model, query(null, null, null)).getTotal()).isZero();
    }

    private static FormModel model(boolean deleted, boolean tiny, boolean tree) {
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("test_form");
        snapshot.setFormName("测试表单");
        snapshot.setFormType(tree ? "TREE" : "SINGLE");
        snapshot.setTableName(tiny ? "LA_TEST_TINY" : "LA_TEST_FORM");
        snapshot.setSkipContractColumns(tiny);
        snapshot.setLogicDeleteColumn(deleted ? "DELETED" : null);
        if (tree) {
            snapshot.setTreeConfig(
                    "{\"sourceType\":\"SELF\",\"parentColumn\":\"PARENT_ID\",\"nodeColumn\":\"PERSON_NAME\"}");
        }
        snapshot.setFields(
                tiny
                        ? List.of(field("personName", "PERSON_NAME", "STRING", false))
                        : List.of(
                                field("personName", "PERSON_NAME", "STRING", false),
                                field("note", "NOTE", "TEXT", true),
                                field("amount", "AMOUNT", "DECIMAL", true),
                                field("deleted", "DELETED", "INT", false),
                                field("parentId", "PARENT_ID", "STRING", true)));
        SnapshotJson json = new SnapshotJson(new ObjectMapper());
        return new FormModelCompiler(json).compile(json.write(snapshot), 1L);
    }

    private static FormDefSnapshot.Field field(String name, String column, String type, boolean nullable) {
        FormDefSnapshot.Field field = new FormDefSnapshot.Field();
        field.setFieldName(name);
        field.setColumnName(column);
        field.setFieldType(type);
        field.setNullable(nullable);
        field.getQuery().setShow(true);
        return field;
    }

    private static FormDataPageQuery query(String field, String op, Object value) {
        FormDataPageQuery query = new FormDataPageQuery();
        if (field != null) {
            AstCondition condition = new AstCondition();
            condition.setField(field);
            condition.setOp(op);
            condition.setValue(value);
            query.setConditions(List.of(condition));
        }
        return query;
    }
}
