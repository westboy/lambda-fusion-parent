package com.lambda.fusion.form.runtime.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.lambda.autoconfig.MybatisPlusExtendProperties;
import com.lambda.cloud.mybatis.tenant.MissingTenantStrategy;
import com.lambda.cloud.mybatis.tenant.TenantContextHolder;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.FormProperties;
import com.lambda.fusion.form.design.support.RuntimeDataSourceResolver;
import com.lambda.fusion.form.design.support.TableMetadataReader;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.biz.BizDataProvider;
import com.lambda.fusion.form.runtime.integration.FormDataInterceptors;
import com.lambda.fusion.form.runtime.mapper.DynamicTableMapper;
import com.lambda.fusion.form.runtime.model.AstCondition;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import com.lambda.fusion.form.runtime.model.FormDataPageView;
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
import java.util.Set;
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
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cache.CacheManager;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/** 使用真实 Mapper 与租户插件验证 SQL 及前后端行协议。 */
class DynamicDataServiceJdbcTest {

    private SqlSession session;
    private JdbcTemplate jdbc;
    private DynamicDataServiceImpl service;
    private TargetTableContract contract;
    private FormProperties formProperties;
    private MybatisPlusExtendProperties mybatisProperties;
    private final FormModelProvider modelProvider = mock(FormModelProvider.class);

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
        jdbc.execute(
                """
                CREATE TABLE LA_TEST_SUB (
                    ID VARCHAR(32) PRIMARY KEY, ITEM_NAME VARCHAR(64) NOT NULL,
                    LEAVE_ID VARCHAR(32), TENANT_ID VARCHAR(32), DELETED INT NOT NULL DEFAULT 0,
                    CREATED_BY VARCHAR(64), CREATED_AT TIMESTAMP, UPDATED_BY VARCHAR(64), UPDATED_AT TIMESTAMP)
                """);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                String currentTenantId = TenantContextHolder.getCurrentTenantId();
                return currentTenantId == null ? null : new StringValue(currentTenantId);
            }

            @Override
            public boolean ignoreTable(String tableName) {
                // 镜像框架 TenantHandler：缺上下文全表跳过，有上下文按豁免清单判断（契约与插件共用同一配置）
                return MissingTenantStrategy.isMissingTenantContext()
                        || mybatisProperties.getTenant().getIgnoreTables().contains(tableName.toLowerCase());
            }
        }));
        configuration.addInterceptor(interceptor);
        configuration.addMapper(DynamicTableMapper.class);
        var factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(true);
        FormProperties properties = new FormProperties();
        // 默认排除正则保护 la_ 前缀：测试表 LA_TEST_* 需显式放行（demo_/test_ 例外）
        properties.setTableExcludePattern("la_(?!demo_|test_).*|ai_.*|flow_.*|databasechangelog.*|qrtz_.*");
        var mybatisProperties = new MybatisPlusExtendProperties();
        this.mybatisProperties = mybatisProperties;
        mybatisProperties.getTenant().setEnabled(true);
        mybatisProperties.getTenant().setIgnoreTables(Set.of("la_test_tiny"));
        formProperties = new FormProperties();
        formProperties.setTableExcludePattern("la_(?!demo_|test_).*|ai_.*|flow_.*|databasechangelog.*|qrtz_.*");
        formProperties.setMaxTreeDescendants(3);
        contract = new TargetTableContract(
                new TableMetadataReader(
                        dataSource,
                        properties,
                        new StaticListableBeanFactory().getBeanProvider(CacheManager.class),
                        new StaticListableBeanFactory().getBeanProvider(RuntimeDataSourceResolver.class)),
                mybatisProperties);
        service = new DynamicDataServiceImpl(
                session.getMapper(DynamicTableMapper.class),
                new QueryAstValidator(new FormProperties()),
                modelProvider,
                contract,
                formProperties,
                new FormDataInterceptors(new GenericApplicationContext()),
                new ObjectMapper(),
                new StaticListableBeanFactory().getBeanProvider(BizDataProvider.class));
        TenantContextHolder.getInstance().setTenantId("tenantA");
    }

    @AfterEach
    void tearDown() {
        session.close();
        TenantContextHolder.getInstance().close();
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
    void updateWithStaleEtagIsRejectedAndCurrentEtagSucceeds() {
        FormModel model = model(false, true, false);
        String id = service.create(model, Map.of("personName", "AB"));
        String etag = (String) service.getById(model, id).get("__etag");
        assertThat(etag).isNotBlank();
        // 另一会话先提交 → 旧指纹失效，携带它的更新被拒且数据不被覆盖
        service.update(model, id, new HashMap<>(Map.of("personName", "CD")));
        Map<String, Object> staleWrite = new HashMap<>(Map.of("personName", "EF"));
        staleWrite.put("__etag", etag);
        assertThatThrownBy(() -> service.update(model, id, staleWrite))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_DATA_CONFLICT.getCode()));
        assertThat(service.getById(model, id)).containsEntry("personName", "CD");
        // 重新读取拿到的新指纹可以通过；未携带指纹的旧客户端保持兼容（仅存在性校验）
        Map<String, Object> freshWrite = new HashMap<>(Map.of("personName", "EF"));
        freshWrite.put("__etag", service.getById(model, id).get("__etag"));
        service.update(model, id, freshWrite);
        assertThat(service.getById(model, id)).containsEntry("personName", "EF");
        service.update(model, id, new HashMap<>(Map.of("personName", "GH")));
        assertThat(service.getById(model, id)).containsEntry("personName", "GH");
    }

    @Test
    void defaultSortAppliesWhenClientDoesNotSortAndSummariesAggregateFilteredSet() {
        FormModel model = listCapabilityModel();
        service.create(model, Map.of("personName", "B", "amount", "10"));
        service.create(model, Map.of("personName", "A", "amount", "5.5"));
        String removed = service.create(model, Map.of("personName", "C", "amount", "2"));
        service.delete(model, removed);

        // 客户端未排序：按设计期默认排序（personName ASC）；合计为全量口径且不含已删除行
        var page = service.page(model, query(null, null, null));
        assertThat(page.getRecords()).extracting(row -> row.get("personName")).containsExactly("A", "B");
        assertThat(page.getSummaries().get("amount"))
                .isInstanceOfSatisfying(
                        java.math.BigDecimal.class, sum -> assertThat(sum).isEqualByComparingTo("15.5"));
        assertThat(page.getSummaries().get("personName")).isEqualTo(2L);

        // 筛选收窄后合计跟随筛选集合
        var filtered = service.page(model, query("personName", "eq", "A"));
        assertThat(filtered.getSummaries().get("amount"))
                .isInstanceOfSatisfying(
                        java.math.BigDecimal.class, sum -> assertThat(sum).isEqualByComparingTo("5.5"));

        // 客户端显式排序优先于设计期默认排序
        FormDataPageQuery desc = query(null, null, null);
        desc.setSorts(List.of(new FormDataPageQuery.SortItem("personName", false)));
        assertThat(service.page(model, desc).getRecords())
                .extracting(row -> row.get("personName"))
                .containsExactly("B", "A");
    }

    @Test
    void exportWritesCsvWithBomEscapedCellsAndRespectsRowLimit() throws Exception {
        FormModel model = model(false, true, false);
        service.create(model, Map.of("personName", "张,三"));
        service.create(model, Map.of("personName", "李\"四\""));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        service.export(model, query(null, null, null), FormConstants.EXPORT_FORMAT_CSV, out);
        String csv = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        // BOM + 表头 + RFC4180 转义（逗号/引号单元格加引号并双写引号）
        assertThat(csv).startsWith("﻿");
        assertThat(csv).contains("\"张,三\"").contains("\"李\"\"四\"\"\"");
        assertThat(csv.trim().lines()).hasSize(3);
        // 行数上限：超出先拒绝，不写半个文件
        formProperties.setMaxExportRows(1);
        assertThatThrownBy(() -> service.export(
                        model,
                        query(null, null, null),
                        FormConstants.EXPORT_FORMAT_CSV,
                        new java.io.ByteArrayOutputStream()))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_EXPORT_LIMIT.getCode()));
    }

    @Test
    void importExcelParsesNumbersAsTextAndDecimalsAsIs() throws Exception {
        FormModel model = model(false, false, false);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (cn.hutool.poi.excel.ExcelWriter writer = cn.hutool.poi.excel.ExcelUtil.getBigWriter()) {
            writer.writeHeadRow(List.of("personName", "note", "amount"));
            writer.writeRow(List.of("张三", 13303820660L, 1.5));
            writer.writeRow(List.of("李四", "备注", 2));
            writer.flush(bytes);
        }

        int count = service.importExcel(model, new java.io.ByteArrayInputStream(bytes.toByteArray()));

        assertThat(count).isEqualTo(2);
        // 数值单元格防科学计数：电话号码按整数文本落库；小数保持 1.5
        Map<String, Object> zhangsan = rowOf(model, "personName", "张三");
        assertThat(String.valueOf(zhangsan.get("note"))).isEqualTo("13303820660");
        assertThat(String.valueOf(zhangsan.get("amount"))).isEqualTo("1.50");
        Map<String, Object> lisi = rowOf(model, "personName", "李四");
        assertThat(String.valueOf(lisi.get("note"))).isEqualTo("备注");
        assertThat(String.valueOf(lisi.get("amount"))).isEqualTo("2.00");
    }

    @Test
    void exportXlsxWritesHeaderAndRows() throws Exception {
        FormModel model = model(false, false, false);
        service.create(model, Map.of("personName", "张,三"));
        service.create(model, Map.of("personName", "李四"));

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        service.export(model, query(null, null, null), FormConstants.EXPORT_FORMAT_XLSX, out);

        try (cn.hutool.poi.excel.ExcelReader reader =
                cn.hutool.poi.excel.ExcelUtil.getReader(new java.io.ByteArrayInputStream(out.toByteArray()))) {
            List<List<Object>> rows = reader.read();
            assertThat(rows).hasSize(3);
            assertThat(rows.get(0).get(0)).isEqualTo("personName");
            // 分页缺省按创建时间倒序：两行不假定先后
            assertThat(rows.subList(1, 3).stream().map(row -> row.get(0)).toList())
                    .containsExactlyInAnyOrder("张,三", "李四");
        }
    }

    /** 按唯一字段值定位行 id（分页默认按创建时间倒序，不宜按顺序取） */
    private Map<String, Object> rowOf(FormModel model, String field, String value) {
        FormDataPageView view = service.page(model, query(field, "eq", value));
        assertThat(view.getRecords()).hasSize(1);
        return service.getById(model, view.getRecords().get(0).get("id").toString());
    }

    @Test
    void likeWildcardsAreEscapedAndMatchLiterally() {
        FormModel model = model(false, true, false);
        service.create(model, Map.of("personName", "100%"));
        service.create(model, Map.of("personName", "1008"));
        // % 与 _ 按字面匹配而非通配：like '100%' 只命中含百分号的行，like '100_' 只命中含下划线的行
        assertThat(service.page(model, query("personName", "like", "100%")).getTotal())
                .isEqualTo(1);
        assertThat(service.page(model, query("personName", "like", "100_")).getTotal())
                .isEqualTo(0);
    }

    @Test
    void uniqueFieldRejectsDuplicateAndFreesValueAfterDelete() {
        FormModel model = uniqueModel();
        String first = service.create(model, Map.of("personName", "AB"));
        // 重复值新增 → 拒
        assertThatThrownBy(() -> service.create(model, Map.of("personName", "AB")))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_DATA_INVALID.getCode()));
        // 更新自身保持原值：排除自身行，不冲突
        service.update(model, first, new HashMap<>(Map.of("personName", "AB")));
        // 改为他人占用的值 → 拒
        service.create(model, Map.of("personName", "CD"));
        assertThatThrownBy(() -> service.update(model, first, new HashMap<>(Map.of("personName", "CD"))))
                .isInstanceOf(FormBusinessException.class);
        // 改为新值 → 过
        service.update(model, first, new HashMap<>(Map.of("personName", "AB2")));
        // 物理删除后值被释放
        service.delete(model, first);
        String reused = service.create(model, Map.of("personName", "AB2"));
        assertThat(service.getById(model, reused)).containsEntry("personName", "AB2");
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
        exemptTiny(false);
        FormModel model = model(false, true, false);
        String id = service.create(model, Map.of("personName", "Alice"));
        exemptTiny(true); // 发布后策略漂移：表被豁免，对带上下文的租户请求必须拒绝而非静默放开
        TenantContextHolder.getInstance().setTenantId("tenantB");
        session.clearCache();
        assertThatThrownBy(() -> service.page(model, query(null, null, null)))
                .isInstanceOf(FormBusinessException.class);
        assertThatThrownBy(() -> service.getById(model, id)).isInstanceOf(FormBusinessException.class);
        assertThatThrownBy(() -> service.update(model, id, Map.of("personName", "Bob")))
                .isInstanceOf(FormBusinessException.class);
        assertThatThrownBy(() -> service.delete(model, id)).isInstanceOf(FormBusinessException.class);
        assertThat(jdbc.queryForObject("SELECT PERSON_NAME FROM LA_TEST_TINY WHERE ID=?", String.class, id))
                .isEqualTo("Alice");
        TenantContextHolder.getInstance().close(); // 平台管理模式：无上下文的开发者读写不设租户防线
        session.clearCache();
        assertThat(service.getById(model, id)).containsEntry("personName", "Alice");
    }

    /** 切换 LA_TEST_TINY 的豁免注册，契约与插件共用该配置事实。 */
    private void exemptTiny(boolean exempt) {
        mybatisProperties.getTenant().setIgnoreTables(exempt ? Set.of("la_test_tiny") : Set.of());
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
    void findRowsBatchFetchesOnlyReferencedFieldsWithoutAssembly() {
        // 轻量批量读：一条 IN 查询、投影仅含引用字段，无 etag/子表/标签装配（标题渲染场景）
        FormModel model = model(false, false, false);
        String alice = service.create(model, Map.of("personName", "Alice", "note", "n1"));
        String bob = service.create(model, Map.of("personName", "Bob"));

        Map<String, Map<String, Object>> rows =
                service.findRows(model, List.of(alice, bob, "missing"), Set.of("personName"));

        assertThat(rows).containsOnlyKeys(alice, bob);
        assertThat(rows.get(alice)).containsEntry("id", alice).containsEntry("personName", "Alice");
        assertThat(rows.get(alice)).doesNotContainKeys("__etag", "__treeParentLabel");
        assertThat(rows.get(bob)).containsEntry("personName", "Bob");
        // 空集短路不触库
        assertThat(service.findRows(model, List.of(), Set.of("personName"))).isEmpty();
    }

    @Test
    void pageSkipsInlineSubRowsAndEtagWhileDetailKeepsBothAndFlatStillMerges() {
        // 列表页卸载详情级装配（M1/M2）：行无 __etag、INLINE 关系仅空占位；FLAT 一对一从字段照常并入；
        // 详情保留完整装配（子行 + 行指纹），编辑乐观锁协议改由详情下发
        when(modelProvider.require("leave_item")).thenReturn(subModel());
        FormModel flatMaster = masterFlatModel();
        String flatId = service.create(flatMaster, Map.of("personName", "Alice", "itemName", "S1"));
        var flatPage = service.page(flatMaster, query(null, null, null));
        assertThat(flatPage.getRecords().getFirst())
                .containsEntry("itemName", "S1") // FLAT 从字段并入主行顶层
                .doesNotContainKey("__etag");
        assertThat(service.getById(flatMaster, flatId)).containsKey("__etag");

        FormModel inlineMaster = masterSubModel("CASCADE_DELETE");
        String id = service.create(
                inlineMaster,
                Map.of("personName", "Bob", "leave_item", List.of(Map.of("itemName", "A1"), Map.of("itemName", "A2"))));
        var page = service.page(inlineMaster, query(null, null, null));
        Map<String, Object> row = page.getRecords().getFirst();
        assertThat(row).doesNotContainKey("__etag");
        assertThat((List<?>) row.get("leave_item")).isEmpty();
        Map<String, Object> detail = service.getById(inlineMaster, id);
        assertThat(detail).containsKey("__etag");
        assertThat((List<?>) detail.get("leave_item")).hasSize(2);
        // 详情指纹仍然覆盖 INLINE 子行：改子行后旧指纹失效（乐观锁口径不受列表卸载影响）
        String etag = (String) detail.get("__etag");
        service.update(inlineMaster, id, Map.of("personName", "Bob2", "leave_item", List.of(Map.of("itemName", "B1"))));
        Map<String, Object> stale = new HashMap<>(Map.of("personName", "Bob3"));
        stale.put("__etag", etag);
        assertThatThrownBy(() -> service.update(inlineMaster, id, stale))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_DATA_CONFLICT.getCode()));
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
    void idDisplayFieldProjectsPrimaryKeyValueWithoutDuplicateColumns() {
        // 主键映射为不落库展示字段：SELECT 列去重，行内投影主键值
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("test_form");
        snapshot.setFormName("主键展示");
        snapshot.setFormType("SINGLE");
        snapshot.setTableName("LA_TEST_FORM");
        snapshot.setFields(List.of(field("personName", "PERSON_NAME", "STRING", false), idDisplayField()));
        FormModel model = compile(snapshot);
        String id = service.create(model, Map.of("personName", "Alice"));
        assertThat(service.getById(model, id)).containsEntry("id", id).containsEntry("rowId", id);
        assertThat(service.page(model, query(null, null, null)).getRecords().getFirst())
                .containsEntry("rowId", id);
    }

    /** 主键展示字段：映射 ID 列、不落库 */
    private static FormDefSnapshot.Field idDisplayField() {
        FormDefSnapshot.Field field = new FormDefSnapshot.Field();
        field.setFieldName("rowId");
        field.setColumnName("ID");
        field.setFieldType("STRING");
        field.setNullable(true);
        field.setPersisted(Boolean.FALSE);
        field.getQuery().setShow(true);
        return field;
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
    void selfTreeRejectsDeletingParentWithActiveChildren() {
        FormModel model = model(true, false, true);
        String root = service.create(model, Map.of("personName", "Root"));
        service.create(model, Map.of("personName", "Child", "parentId", root));
        // 有未删除下级即拒绝（18135），子行不受影响
        assertThatThrownBy(() -> service.delete(model, root))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_TREE_DELETE_FORBIDDEN.getCode()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LA_TEST_FORM WHERE DELETED=0", Integer.class))
                .isEqualTo(2);
        // 子行删除后父行可删（叶子无下级）
        String child = jdbc.queryForObject("SELECT ID FROM LA_TEST_FORM WHERE PARENT_ID=?", String.class, root);
        service.delete(model, child);
        service.delete(model, root);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LA_TEST_FORM WHERE DELETED=0", Integer.class))
                .isZero();
    }

    @Test
    void selfTreeValidatesParentExistenceAndRejectsCycles() {
        FormModel model = model(true, false, true);
        String root = service.create(model, Map.of("personName", "Root"));
        String child = service.create(model, Map.of("personName", "Child", "parentId", root));
        // 上级不存在（18136）
        assertThatThrownBy(() -> service.create(model, Map.of("personName", "Orphan", "parentId", "not-exist")))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_TREE_PARENT_NOT_FOUND.getCode()));
        // 上级指向自身（18137）
        assertThatThrownBy(() -> service.update(model, root, Map.of("parentId", root)))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_TREE_PARENT_CYCLE.getCode()));
        // 上级指向自己的子孙（18137）
        assertThatThrownBy(() -> service.update(model, root, Map.of("parentId", child)))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_TREE_PARENT_CYCLE.getCode()));
        // 合法换父不受影响
        String other = service.create(model, Map.of("personName", "Other"));
        service.update(model, child, Map.of("parentId", other));
        assertThat(jdbc.queryForObject("SELECT PARENT_ID FROM LA_TEST_FORM WHERE ID=?", String.class, child))
                .isEqualTo(other);
        // 空串上级归一为 NULL（根节点）
        service.update(model, child, Map.of("parentId", ""));
        assertThat(jdbc.queryForObject("SELECT PARENT_ID FROM LA_TEST_FORM WHERE ID=?", String.class, child))
                .isNull();
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
    void treeNodeFilterReturnsSelfAndAllDescendantsAndEnforcesLimit() {
        FormModel model = model(true, false, true);
        String root = service.create(model, Map.of("personName", "Root"));
        String child = service.create(model, Map.of("personName", "Child", "parentId", root));
        service.create(model, Map.of("personName", "Grandson", "parentId", child));
        service.create(model, Map.of("personName", "Outsider"));

        FormDataPageQuery treeQuery = query(null, null, null);
        treeQuery.setTreeNode(new FormDataPageQuery.TreeNodeFilter(root, null));
        var page = service.page(model, treeQuery);
        assertThat(page.getTotal()).isEqualTo(3);
        assertThat(page.getRecords())
                .extracting(row -> row.get("personName"))
                .containsExactlyInAnyOrder("Root", "Child", "Grandson");

        // 越选叶子只剩自身子树；懒加载根节点返回全部根（Root 与 Outsider 都是根）
        treeQuery.setTreeNode(new FormDataPageQuery.TreeNodeFilter(child, null));
        assertThat(service.page(model, treeQuery).getTotal()).isEqualTo(2);
        assertThat(service.tree(model, null))
                .extracting(row -> row.get("personName"))
                .containsExactlyInAnyOrder("Root", "Outsider");

        // 上限 maxTreeDescendants=3：root 的子孙集（Root+Child+Grandson=3）触顶，再多即拒绝
        formProperties.setMaxTreeDescendants(2);
        treeQuery.setTreeNode(new FormDataPageQuery.TreeNodeFilter(root, null));
        assertThatThrownBy(() -> service.page(model, treeQuery))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_TREE_DESCENDANT_LIMIT.getCode()));
    }

    @Test
    void treeNodeFilterIsRejectedOnNonTreeForms() {
        FormModel model = model(false, false, false);
        FormDataPageQuery treeQuery = query(null, null, null);
        treeQuery.setTreeNode(new FormDataPageQuery.TreeNodeFilter("any", null));
        assertThatThrownBy(() -> service.page(model, treeQuery)).isInstanceOf(FormBusinessException.class);
    }

    @Test
    void selfTreeParentColumnMustMapToPersistedField() {
        // 父列映射到不落库展示字段（persisted=false）时表单永不提交父值，树静默退化为全根节点——编译期拒绝
        SnapshotJson json = new SnapshotJson(new ObjectMapper());
        FormDefSnapshot snapshot = treeSnapshot();
        FormDefSnapshot.Field parentId = field("parentId", "PARENT_ID", "STRING", true);
        parentId.setPersisted(false);
        snapshot.setFields(List.of(
                field("personName", "PERSON_NAME", "STRING", false),
                field("note", "NOTE", "TEXT", true),
                field("amount", "AMOUNT", "DECIMAL", true),
                field("deleted", "DELETED", "INT", false),
                parentId));
        assertThatThrownBy(() -> new FormModelCompiler(json).compile(json.write(snapshot), 1L))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(String.valueOf(((FormBusinessException) error).getArgs()[0]))
                        .contains("树父列未映射落库字段"));
    }

    @Test
    void selfTreeParentColumnMustBePhysicallyNullableForRootNodes() {
        // 根节点语义要求父列允许空值：物理 NOT NULL 且无默认值的父列创建根节点必失败——发布契约拒绝
        jdbc.execute("ALTER TABLE LA_TEST_FORM ALTER COLUMN PARENT_ID SET NOT NULL");
        SnapshotJson json = new SnapshotJson(new ObjectMapper());
        FormDefSnapshot snapshot = treeSnapshot();
        snapshot.setFields(List.of(
                field("personName", "PERSON_NAME", "STRING", false),
                field("note", "NOTE", "TEXT", true),
                field("amount", "AMOUNT", "DECIMAL", true),
                field("deleted", "DELETED", "INT", false),
                // 字段定义 nullable=false 与物理 NOT NULL 一致，不触发「允许清空但物理列 NOT NULL」
                field("parentId", "PARENT_ID", "STRING", false)));
        FormModel model = new FormModelCompiler(json).compile(json.write(snapshot), 1L);
        assertThatThrownBy(() -> contract.validate(model))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(String.valueOf(((FormBusinessException) error).getArgs()[0]))
                        .contains("SELF 树父列"));
    }

    @Test
    void masterSubWritesReadsReplacesAndCascades() {
        when(modelProvider.require("leave_item")).thenReturn(subModel());
        FormModel master = masterSubModel("CASCADE_DELETE");

        // 新增：外键回填 + 审计填充 + 租户由插件填充
        String id = service.create(
                master,
                Map.of(
                        "personName",
                        "Alice",
                        "leave_item",
                        List.of(Map.of("itemName", "A1"), Map.of("itemName", "A2"))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LA_TEST_SUB WHERE LEAVE_ID=?", Integer.class, id))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT DISTINCT TENANT_ID FROM LA_TEST_SUB WHERE LEAVE_ID=?", String.class, id))
                .isEqualTo("tenantA");
        assertThat(jdbc.queryForObject("SELECT COUNT(CREATED_BY) FROM LA_TEST_SUB WHERE LEAVE_ID=?", Integer.class, id))
                .isEqualTo(2);

        // 两段式读组装：详情挂嵌套子行；列表页不再整页装配 INLINE 子行（编辑按 id 取详情），
        // 关系键置空占位与台账式同形
        assertThat((List<?>) service.getById(master, id).get("leave_item")).hasSize(2);
        var page = service.page(master, query(null, null, null));
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat((List<?>) page.getRecords().getFirst().get("leave_item")).isEmpty();

        // 差量更新：显式携带键才比对子行集合（无 id 的提交行按新增处理，未提交的既有行删除）
        service.update(master, id, Map.of("personName", "Alice2", "leave_item", List.of(Map.of("itemName", "B1"))));
        assertThat(jdbc.queryForObject(
                        "SELECT ITEM_NAME FROM LA_TEST_SUB WHERE LEAVE_ID=? AND DELETED=0", String.class, id))
                .isEqualTo("B1");
        service.update(master, id, Map.of("personName", "Alice3"));
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM LA_TEST_SUB WHERE LEAVE_ID=? AND DELETED=0", Integer.class, id))
                .isEqualTo(1);

        // CASCADE_DELETE：删除主行级联清理子行（子表有逻辑删除列：置标记，活跃行清零）
        service.delete(master, id);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM LA_TEST_SUB WHERE LEAVE_ID=? AND DELETED=0", Integer.class, id))
                .isEqualTo(0);
    }

    @Test
    void subRowOverrideRulesAreEnforcedOnWrite() {
        when(modelProvider.require("leave_item")).thenReturn(subModel());
        // 场景覆盖：itemName 在本主从下须以 A 开头（子表单自身定义无此规则）
        FormModel master =
                masterSubModel("CASCADE_DELETE", null, "{\"columns\":[{\"field\":\"itemName\",\"pattern\":\"^A\"}]}");
        assertThatThrownBy(() -> service.create(
                        master, Map.of("personName", "X", "leave_item", List.of(Map.of("itemName", "B1")))))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_DATA_INVALID.getCode()));
        String id = service.create(master, Map.of("personName", "X", "leave_item", List.of(Map.of("itemName", "A1"))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LA_TEST_SUB WHERE LEAVE_ID=?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void inlineSubRowSubmissionAboveReadWindowIsRejected() {
        // 写入口径与读窗口对称：超过 maxInlineSubRows 的子行提交直接拒绝（防单事务无限 INSERT）
        formProperties.setMaxInlineSubRows(2);
        when(modelProvider.require("leave_item")).thenReturn(subModel());
        FormModel master = masterSubModel("CASCADE_DELETE");
        assertThatThrownBy(() -> service.create(
                        master,
                        Map.of(
                                "personName",
                                "X",
                                "leave_item",
                                List.of(Map.of("itemName", "A1"), Map.of("itemName", "A2"), Map.of("itemName", "A3")))))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_DATA_INVALID.getCode()));
        // 更新路径同口径
        String id = service.create(master, Map.of("personName", "X", "leave_item", List.of(Map.of("itemName", "A1"))));
        assertThatThrownBy(() -> service.update(
                        master,
                        id,
                        Map.of(
                                "personName",
                                "Y",
                                "leave_item",
                                List.of(Map.of("itemName", "B1"), Map.of("itemName", "B2"), Map.of("itemName", "B3")))))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_DATA_INVALID.getCode()));
    }

    @Test
    void importedPrimaryKeyColumnSuppressesInjectedRowIdWhileDefaultKeepsIt() {
        // 默认关系（从字段未导入主键列）：运行时注入 rowId 展示字段
        FormModel master = masterSubModel("CASCADE_DELETE");
        when(modelProvider.require("leave_item")).thenReturn(subModel());
        String id =
                service.create(master, Map.of("personName", "Alice", "leave_item", List.of(Map.of("itemName", "A1"))));
        Map<?, ?> sub = (Map<?, ?>) ((List<?>) service.getById(master, id).get("leave_item")).get(0);
        assertThat(sub.containsKey("rowId")).isTrue();

        // 设计时显式导入主键列（不落库展示字段）：requireSubModel 不再注入重复 rowId，按导入字段回显
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("test_master");
        snapshot.setFormName("主从演示");
        snapshot.setFormType("MASTER_SUB");
        snapshot.setTableName("LA_TEST_FORM");
        snapshot.setLogicDeleteColumn("DELETED");
        FormDefSnapshot.Field importedId = subField("subId", "ID", "STRING", true, "leave_item");
        importedId.setPersisted(false);
        snapshot.setFields(List.of(
                field("personName", "PERSON_NAME", "STRING", false),
                field("deleted", "DELETED", "INT", false),
                subField("itemName", "ITEM_NAME", "STRING", false, "leave_item"),
                subField("leaveId", "LEAVE_ID", "STRING", true, "leave_item"),
                importedId));
        var relation = new FormDefSnapshot.Relation();
        relation.setSubFormKey("leave_item");
        relation.setSubTable("LA_TEST_SUB");
        relation.setForeignKey("LEAVE_ID");
        relation.setCascadeStrategy("CASCADE_DELETE");
        snapshot.setRelations(List.of(relation));
        FormModel masterWithId = compile(snapshot);
        String id2 = service.create(
                masterWithId, Map.of("personName", "Bob", "leave_item", List.of(Map.of("itemName", "B1"))));
        Map<?, ?> sub2 =
                (Map<?, ?>) ((List<?>) service.getById(masterWithId, id2).get("leave_item")).get(0);
        assertThat(sub2.containsKey("subId")).isTrue();
        assertThat(sub2.containsKey("rowId")).isFalse();
        assertThat(sub2.get("subId")).isEqualTo(sub2.get("id"));
    }

    @Test
    void subTableNotNullForeignKeyColumnIsExemptFromUninputableRequiredCheck() {
        // 外键列指向 NOT NULL 物理列且表单隐藏（值由服务端回填主行 id）：
        // 目标表契约不得按「无法输入的必填列」拒绝（前端导入外键即 show=false + 列 NOT NULL 是常态）
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("test_master");
        snapshot.setFormName("主从演示");
        snapshot.setFormType("MASTER_SUB");
        snapshot.setTableName("LA_TEST_FORM");
        snapshot.setLogicDeleteColumn("DELETED");
        FormDefSnapshot.Field itemName = subField("itemName", "ITEM_NAME", "STRING", false, "leave_item");
        itemName.getForm().setShow(false);
        snapshot.setFields(List.of(
                field("personName", "PERSON_NAME", "STRING", false),
                field("deleted", "DELETED", "INT", false),
                itemName,
                subField("leaveId", "LEAVE_ID", "STRING", true, "leave_item")));
        var relation = new FormDefSnapshot.Relation();
        relation.setSubFormKey("leave_item");
        relation.setSubTable("LA_TEST_SUB");
        relation.setForeignKey("ITEM_NAME");
        relation.setCascadeStrategy("CASCADE_DELETE");
        snapshot.setRelations(List.of(relation));
        FormModel master = compile(snapshot);

        String id =
                service.create(master, Map.of("personName", "Alice", "leave_item", List.of(Map.of("itemName", "占位"))));
        // 外键列落库值=主行 id（客户端值被剥离后由服务端回填）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LA_TEST_SUB WHERE ITEM_NAME=?", Integer.class, id))
                .isEqualTo(1);
        assertThat((List<?>) service.getById(master, id).get("leave_item")).hasSize(1);
    }

    @Test
    void inlineSubRowsDiffUpdateKeepsIdsDefendsEmptyClearAndRejectsForeignSubId() {
        when(modelProvider.require("leave_item")).thenReturn(subModel());
        FormModel master = masterSubModel("CASCADE_DELETE");
        String id = service.create(
                master,
                Map.of(
                        "personName",
                        "Alice",
                        "leave_item",
                        List.of(Map.of("itemName", "A1"), Map.of("itemName", "A2"))));
        List<?> subs = (List<?>) service.getById(master, id).get("leave_item");
        String firstSubId = (String) ((Map<?, ?>) subs.get(0)).get("id");
        String secondSubId = (String) ((Map<?, ?>) subs.get(1)).get("id");

        // 防线一：无行指纹的空数组（清空全部子行）拒绝，既有子行不动
        Map<String, Object> clearWithoutEtag = new HashMap<>();
        clearWithoutEtag.put("personName", "X");
        clearWithoutEtag.put("leave_item", List.of());
        assertThatThrownBy(() -> service.update(master, id, clearWithoutEtag))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_DATA_INVALID.getCode()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LA_TEST_SUB WHERE LEAVE_ID=?", Integer.class, id))
                .isEqualTo(2);

        // 防线二：不属于本主单的子行 id 拒绝（防越权改他单子行）
        Map<String, Object> foreignRow = new HashMap<>(Map.of("itemName", "HACK"));
        foreignRow.put("id", "999999999999");
        Map<String, Object> forged = new HashMap<>();
        forged.put("personName", "X");
        forged.put("leave_item", List.of(foreignRow));
        assertThatThrownBy(() -> service.update(master, id, forged))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_DATA_INVALID.getCode()));

        // 差量：第一行带 id 更新、第二行不提交（删除）、第三行无 id 新增
        String etag = (String) service.getById(master, id).get("__etag");
        Map<String, Object> keep = new HashMap<>(Map.of("itemName", "A1-updated"));
        keep.put("id", firstSubId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("personName", "Alice2");
        payload.put("leave_item", List.of(keep, Map.of("itemName", "A3")));
        payload.put("__etag", etag);
        service.update(master, id, payload);

        // 既有子行 id 稳定：更新不换新 id；未提交的既有行被删除；新增行获得新 id
        assertThat(jdbc.queryForObject("SELECT ITEM_NAME FROM LA_TEST_SUB WHERE ID=?", String.class, firstSubId))
                .isEqualTo("A1-updated");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM LA_TEST_SUB WHERE ID=? AND DELETED=0", Integer.class, secondSubId))
                .isEqualTo(0);
        List<?> after = (List<?>) service.getById(master, id).get("leave_item");
        assertThat(after).hasSize(2);

        // 携带行指纹的空数组：提交者看到了当前数据，允许清空
        Map<String, Object> clearWithEtag = new HashMap<>();
        clearWithEtag.put("personName", "Alice3");
        clearWithEtag.put("leave_item", List.of());
        clearWithEtag.put("__etag", service.getById(master, id).get("__etag"));
        service.update(master, id, clearWithEtag);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM LA_TEST_SUB WHERE LEAVE_ID=? AND DELETED=0", Integer.class, id))
                .isEqualTo(0);
    }

    @Test
    void notNullForeignKeyFieldSkipsRequiredNormalizationAndIsHiddenOnCompile() {
        // 真实场景：外键列物理 NOT NULL，导入为 nullable=false 的从字段，前端不提交外键（服务端回填）。
        // 归一阶段若按必填校验空外键，主从新增会误报「不能为空」——服务端绑定列须跳过归一必填/非空闸。
        jdbc.execute("ALTER TABLE LA_TEST_SUB ALTER COLUMN LEAVE_ID SET NOT NULL");
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("test_master");
        snapshot.setFormName("主从演示");
        snapshot.setFormType("MASTER_SUB");
        snapshot.setTableName("LA_TEST_FORM");
        snapshot.setLogicDeleteColumn("DELETED");
        snapshot.setFields(List.of(
                field("personName", "PERSON_NAME", "STRING", false),
                field("deleted", "DELETED", "INT", false),
                subField("itemName", "ITEM_NAME", "STRING", false, "leave_item"),
                subField("leaveId", "LEAVE_ID", "STRING", false, "leave_item")));
        var relation = new FormDefSnapshot.Relation();
        relation.setSubFormKey("leave_item");
        relation.setSubTable("LA_TEST_SUB");
        relation.setForeignKey("LEAVE_ID");
        relation.setCascadeStrategy("CASCADE_DELETE");
        snapshot.setRelations(List.of(relation));
        FormModel master = compile(snapshot);

        // 编译期强制外键字段表单/列表隐藏（设计期展示配置不得覆盖服务端绑定事实）
        FormModel.FormField foreignKeyField = master.fields().stream()
                .filter(field -> "leaveId".equals(field.name()))
                .findFirst()
                .orElseThrow();
        assertThat(foreignKeyField.form().show()).isFalse();
        assertThat(foreignKeyField.list().show()).isFalse();

        // 提交体不含外键：create 归一不再误报「不能为空」，外键由服务端回填主行 id
        String id =
                service.create(master, Map.of("personName", "Alice", "leave_item", List.of(Map.of("itemName", "A1"))));
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM LA_TEST_SUB WHERE LEAVE_ID=? AND ITEM_NAME='A1'", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void clientSuppliedForeignKeyIsStrippedAndOverriddenByServerBinding() {
        when(modelProvider.require("leave_item")).thenReturn(subModel());
        FormModel master = masterSubModel("CASCADE_DELETE");

        // 子行数据携带外键字段（如子表单把外键列设计为可编辑字段）：服务端绑定优先，
        // 不再与提交值产生重复列，也不被伪造值覆盖
        String id = service.create(
                master,
                Map.of(
                        "personName",
                        "Alice",
                        "leave_item",
                        List.of(Map.of("itemName", "A1", "leaveId", "forged"), Map.of("itemName", "A2"))));
        assertThat(jdbc.queryForObject("SELECT DISTINCT LEAVE_ID FROM LA_TEST_SUB", String.class))
                .isEqualTo(id);
        service.update(
                master,
                id,
                Map.of("personName", "Alice2", "leave_item", List.of(Map.of("itemName", "B1", "leaveId", "forged2"))));
        assertThat(jdbc.queryForObject("SELECT DISTINCT LEAVE_ID FROM LA_TEST_SUB", String.class))
                .isEqualTo(id);
    }

    @Test
    void masterSubForbidStrategyRejectsDeleteWhenChildRowsExist() {
        when(modelProvider.require("leave_item")).thenReturn(subModel());
        FormModel master = masterSubModel("FORBID_DELETE");
        String id =
                service.create(master, Map.of("personName", "Bob", "leave_item", List.of(Map.of("itemName", "C1"))));
        assertThatThrownBy(() -> service.delete(master, id))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_SUB_DELETE_FORBIDDEN.getCode()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LA_TEST_SUB WHERE LEAVE_ID=?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void masterSubListModePagesSubRowsIndependentlyAndRejectsInlineSubmit() {
        when(modelProvider.require("leave_item")).thenReturn(subModel());
        FormModel master = masterSubModel("CASCADE_DELETE", FormConstants.RELATION_MODE_LIST);

        // 台账式子行不随主表提交：新增与更新显式携带子表键均拒绝
        assertThatThrownBy(() -> service.create(
                        master, Map.of("personName", "Alice", "leave_item", List.of(Map.of("itemName", "A1")))))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.INVALID_PARAMETER.getCode()));
        String id = service.create(master, Map.of("personName", "Alice"));
        assertThatThrownBy(() -> service.update(master, id, Map.of("personName", "Alice2", "leave_item", List.of())))
                .isInstanceOf(FormBusinessException.class);

        // 独立新增：外键由服务端绑定（提交数据不携带外键）；子表内嵌后写权限并入主表（端点已校验主表 ADD）
        service.createSubRow(master, id, "leave_item", Map.of("itemName", "L1"));
        service.createSubRow(master, id, "leave_item", Map.of("itemName", "L2"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LA_TEST_SUB WHERE LEAVE_ID=?", Integer.class, id))
                .isEqualTo(2);

        // 详情不嵌套台账式子行（载荷有界），分页端点按外键过滤、投影并返回字段标识键
        assertThat((List<?>) service.getById(master, id).get("leave_item")).isEmpty();
        var page = service.pageSubRows(master, id, "leave_item", 1, 1);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getRecords()).hasSize(1);
        assertThat(page.getRecords().getFirst()).containsEntry("itemName", "L1");
        assertThatThrownBy(() -> service.pageSubRows(master, id, "other_item", 1, 10))
                .isInstanceOf(FormBusinessException.class);

        // 级联删除不受呈现方式影响（子表有逻辑删除列：置标记，活跃行清零）
        service.delete(master, id);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM LA_TEST_SUB WHERE LEAVE_ID=? AND DELETED=0", Integer.class, id))
                .isEqualTo(0);
    }

    @Test
    void masterSubSubRowUpdateAndDeleteAreScopedToOwnerMainRow() {
        FormModel master = masterSubModel("CASCADE_DELETE", FormConstants.RELATION_MODE_LIST);
        String id = service.create(master, Map.of("personName", "Alice"));
        String otherId = service.create(master, Map.of("personName", "Bob"));
        service.createSubRow(master, id, "leave_item", Map.of("itemName", "L1"));
        String subId =
                jdbc.queryForObject("SELECT ID FROM LA_TEST_SUB WHERE LEAVE_ID=? AND DELETED=0", String.class, id);

        // 台账式分页随行下发子行指纹（独立编辑的乐观锁凭证）
        var page = service.pageSubRows(master, id, "leave_item", 1, 10);
        Object etag = page.getRecords().getFirst().get("__etag");
        assertThat(etag).isInstanceOf(String.class);

        // 过期指纹拒绝；匹配指纹放行（外键列提交被剥离，归属不可改写）
        assertThatThrownBy(() -> service.updateSubRow(
                        master, id, "leave_item", subId, Map.of("itemName", "LX", "__etag", "stale")))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_DATA_CONFLICT.getCode()));
        service.updateSubRow(
                master,
                id,
                "leave_item",
                subId,
                Map.of("itemName", "L1X", "leaveId", otherId, "__etag", String.valueOf(etag)));
        assertThat(jdbc.queryForObject("SELECT ITEM_NAME FROM LA_TEST_SUB WHERE ID=?", String.class, subId))
                .isEqualTo("L1X");
        assertThat(jdbc.queryForObject("SELECT LEAVE_ID FROM LA_TEST_SUB WHERE ID=?", String.class, subId))
                .isEqualTo(id);

        // 归属防线：用别的主行 id 定位该子行，更新与删除均按不存在拒绝
        assertThatThrownBy(() -> service.updateSubRow(master, otherId, "leave_item", subId, Map.of("itemName", "HACK")))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_DATA_NOT_FOUND.getCode()));
        assertThatThrownBy(() -> service.deleteSubRow(master, otherId, "leave_item", subId))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_DATA_NOT_FOUND.getCode()));
        assertThat(jdbc.queryForObject("SELECT ITEM_NAME FROM LA_TEST_SUB WHERE ID=?", String.class, subId))
                .isEqualTo("L1X");

        // 正常删除：子表有逻辑删除列，置标记后活跃行清零
        service.deleteSubRow(master, id, "leave_item", subId);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM LA_TEST_SUB WHERE ID=? AND DELETED=0", Integer.class, subId))
                .isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT DELETED FROM LA_TEST_SUB WHERE ID=?", Integer.class, subId))
                .isEqualTo(1);
    }

    @Test
    void masterSubCreateRejectsWhenSubTableNotAllowed() {
        // 子表直连物理表：目标子表命中平台内部表排除正则时拒绝（发布快照篡改的兜底）
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("test_master");
        snapshot.setFormName("主从演示");
        snapshot.setFormType("MASTER_SUB");
        snapshot.setTableName("LA_TEST_FORM");
        snapshot.setLogicDeleteColumn("DELETED");
        snapshot.setFields(List.of(
                field("personName", "PERSON_NAME", "STRING", false),
                field("deleted", "DELETED", "INT", false),
                subField("itemName", "ITEM_NAME", "STRING", false, "leave_item"),
                subField("leaveId", "LEAVE_ID", "STRING", true, "leave_item")));
        var relation = new FormDefSnapshot.Relation();
        relation.setSubFormKey("leave_item");
        relation.setSubTable("LA_GHOST_SUB");
        relation.setForeignKey("LEAVE_ID");
        snapshot.setRelations(List.of(relation));
        FormModel master = compile(snapshot);
        assertThatThrownBy(() -> service.create(
                        master, Map.of("personName", "Carol", "leave_item", List.of(Map.of("itemName", "D1")))))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_TABLE_INVALID.getCode()));
    }

    @Test
    void masterSubFlatModeWritesSubRowUpsertAndReadsMergedFlat() {
        when(modelProvider.require("leave_item")).thenReturn(subModel());
        FormModel master = masterFlatModel();

        // 平铺提交：从表字段随主表字段一起，写入拆分为「主行 + 从行」单条 upsert（外键回填）
        String id = service.create(master, Map.of("personName", "Alice", "itemName", "S1"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LA_TEST_SUB WHERE LEAVE_ID=?", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ITEM_NAME FROM LA_TEST_SUB WHERE LEAVE_ID=?", String.class, id))
                .isEqualTo("S1");

        // 读取扁平合并：从行字段提升到主行顶层（无嵌套键）
        assertThat(service.getById(master, id))
                .containsEntry("personName", "Alice")
                .containsEntry("itemName", "S1")
                .doesNotContainKeys("leave_item");

        // 编辑从字段：从行单条更新而非重复插入（一对一 upsert）
        service.update(master, id, Map.of("personName", "Alice2", "itemName", "S2"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LA_TEST_SUB WHERE LEAVE_ID=?", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ITEM_NAME FROM LA_TEST_SUB WHERE LEAVE_ID=?", String.class, id))
                .isEqualTo("S2");

        // 嵌套子表键按扁平协议拒绝
        assertThatThrownBy(() -> service.update(
                        master, id, Map.of("personName", "Alice3", "leave_item", List.of(Map.of("itemName", "X")))))
                .isInstanceOf(FormBusinessException.class);

        // 历史多行从数据：取最新一条融合展示（不静默删数）
        jdbc.update("INSERT INTO LA_TEST_SUB (ID, ITEM_NAME, LEAVE_ID) VALUES (?, ?, ?)", "legacy-sub", "OLD", id);
        assertThat(service.getById(master, id)).containsEntry("itemName", "S2");
    }

    /** 一对一扁平主表：personName 为主表字段，itemName 为 FLAT 标记的从表字段（列在 LA_TEST_SUB） */
    private static FormModel masterFlatModel() {
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("test_flat");
        snapshot.setFormName("扁平演示");
        snapshot.setFormType("MASTER_SUB");
        snapshot.setTableName("LA_TEST_FORM");
        snapshot.setLogicDeleteColumn("DELETED");
        FormDefSnapshot.Field itemName = field("itemName", "ITEM_NAME", "STRING", false);
        itemName.setSubFormKey("leave_item");
        // 外键列须映射为该关系的落库从字段（引擎回填，表单上默认不展示）
        FormDefSnapshot.Field leaveId = field("leaveId", "LEAVE_ID", "STRING", true);
        leaveId.setSubFormKey("leave_item");
        leaveId.getForm().setShow(Boolean.FALSE);
        snapshot.setFields(List.of(
                field("personName", "PERSON_NAME", "STRING", false),
                field("deleted", "DELETED", "INT", false),
                itemName,
                leaveId));
        var relation = new FormDefSnapshot.Relation();
        relation.setSubFormKey("leave_item");
        relation.setSubTable("LA_TEST_SUB");
        relation.setForeignKey("LEAVE_ID");
        relation.setCascadeStrategy("CASCADE_DELETE");
        relation.setMode(FormConstants.RELATION_MODE_FLAT);
        snapshot.setRelations(List.of(relation));
        return compile(snapshot);
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
        TenantContextHolder.getInstance().setTenantId("tenantB");
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

    /** 主从演示主表模型：LA_TEST_FORM + leave_item 关系 */
    private static FormModel masterSubModel(String cascadeStrategy) {
        return masterSubModel(cascadeStrategy, null);
    }

    private static FormModel masterSubModel(String cascadeStrategy, String mode) {
        return masterSubModel(cascadeStrategy, mode, null);
    }

    private static FormModel masterSubModel(String cascadeStrategy, String mode, String subTableConfig) {
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("test_master");
        snapshot.setFormName("主从演示");
        snapshot.setFormType("MASTER_SUB");
        snapshot.setTableName("LA_TEST_FORM");
        snapshot.setLogicDeleteColumn("DELETED");
        // 子表字段内嵌主清单（subFormKey=关系标识归属；外键列须有落库映射）
        snapshot.setFields(List.of(
                field("personName", "PERSON_NAME", "STRING", false),
                field("deleted", "DELETED", "INT", false),
                subField("itemName", "ITEM_NAME", "STRING", false, "leave_item"),
                subField("leaveId", "LEAVE_ID", "STRING", true, "leave_item")));
        var relation = new FormDefSnapshot.Relation();
        relation.setSubFormKey("leave_item");
        relation.setSubTable("LA_TEST_SUB");
        relation.setForeignKey("LEAVE_ID");
        relation.setCascadeStrategy(cascadeStrategy);
        relation.setMode(mode);
        relation.setSubTableConfig(subTableConfig);
        snapshot.setRelations(List.of(relation));
        return compile(snapshot);
    }

    /** 从表字段（归属标记=关系标识） */
    private static FormDefSnapshot.Field subField(
            String name, String column, String type, boolean nullable, String subFormKey) {
        FormDefSnapshot.Field field = field(name, column, type, nullable);
        field.setSubFormKey(subFormKey);
        return field;
    }

    /** 主从演示子表模型：LA_TEST_SUB（单表类型，LEAVE_ID 外键已映射、可空由引擎回填） */
    private static FormModel subModel() {
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("leave_item");
        snapshot.setFormName("请假明细");
        snapshot.setFormType("SINGLE");
        snapshot.setTableName("LA_TEST_SUB");
        snapshot.setFields(
                List.of(field("itemName", "ITEM_NAME", "STRING", false), field("leaveId", "LEAVE_ID", "STRING", true)));
        return compile(snapshot);
    }

    /** 唯一约束演示模型：LA_TEST_FORM，personName 唯一（rules.unique） */
    private static FormModel uniqueModel() {
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("test_unique");
        snapshot.setFormName("唯一约束演示");
        snapshot.setFormType("SINGLE");
        snapshot.setTableName("LA_TEST_FORM");
        FormDefSnapshot.Field personName = field("personName", "PERSON_NAME", "STRING", false);
        personName.getForm().setRules(Map.of("unique", true));
        snapshot.setFields(List.of(personName));
        return compile(snapshot);
    }

    /** 列表能力演示模型：personName 默认升序+计数合计，amount 求和合计，DELETED 逻辑删除 */
    private static FormModel listCapabilityModel() {
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("test_list");
        snapshot.setFormName("列表能力");
        snapshot.setFormType("SINGLE");
        snapshot.setTableName("LA_TEST_FORM");
        snapshot.setLogicDeleteColumn("DELETED");
        FormDefSnapshot.Field personName = field("personName", "PERSON_NAME", "STRING", false);
        personName.getList().setDefaultSort("asc");
        personName.getList().setSummary(true);
        FormDefSnapshot.Field amount = field("amount", "AMOUNT", "DECIMAL", true);
        amount.getList().setSummary(true);
        snapshot.setFields(List.of(personName, amount, field("deleted", "DELETED", "INT", false)));
        return compile(snapshot);
    }

    private static FormModel compile(FormDefSnapshot snapshot) {
        SnapshotJson json = new SnapshotJson(new ObjectMapper());
        return new FormModelCompiler(json).compile(json.write(snapshot), 1L);
    }

    /** SELF 树演示快照：LA_TEST_FORM（PARENT_ID 父列 + PERSON_NAME 节点列，字段集与 model(tree=true) 一致） */
    private static FormDefSnapshot treeSnapshot() {
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("test_form");
        snapshot.setFormName("测试表单");
        snapshot.setFormType("TREE");
        snapshot.setTableName("LA_TEST_FORM");
        snapshot.setLogicDeleteColumn("DELETED");
        snapshot.setTreeConfig(
                "{\"sourceType\":\"SELF\",\"parentColumn\":\"PARENT_ID\",\"nodeColumn\":\"PERSON_NAME\"}");
        return snapshot;
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
