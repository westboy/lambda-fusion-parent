package com.lambda.fusion.form.design.support;

import com.lambda.fusion.form.FormProperties;
import com.lambda.fusion.form.design.model.TableColumnView;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * 物理表元数据只读读取（选表导列与发布校验共用）
 *
 * <p>经 JDBC DatabaseMetaData introspection，不执行任何 DDL；排除表由
 * {@link FormProperties#getTableExcludePattern()} 过滤。只读取当前连接的 catalog/schema，
 * 转义 JDBC 模式字符并核对返回的表名，不跨库合并同名表。
 *
 * <p>数据源维度：空 id 读平台主库（注入的主 DataSource），非空 id 经
 * {@link RuntimeDataSourceResolver} 按注册 id（la_datasources.ID）解析对应连接池；
 * 解析端口缺席（未装配动态数据源组件）而传入非空 id 时报错。
 *
 * <p>列元数据走 {@code formTableMeta} 短 TTL 缓存（lambda-cloud-starter-cache，TTL 由
 * {@code lambda.cache.caches.form-table-meta} 配置，默认 60s），缓存键为 {@code 数据源id:表名}，
 * 跨源同名表互不串用。目标表结构演进走正常部署，DDL 变更后最长一个 TTL 周期生效。
 * CacheManager 缺失（缓存 starter 未装配）时直通实时读取。
 *
 * @author Zx
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class TableMetadataReader {

    /** 表元数据内省缓存名（lambda.cache.caches 下按名配置 TTL） */
    public static final String CACHE_NAME = "formTableMeta";

    private final DataSource dataSource;
    private final FormProperties properties;
    private final ObjectProvider<CacheManager> cacheManagerProvider;
    private final ObjectProvider<RuntimeDataSourceResolver> runtimeResolverProvider;

    /**
     * 列出可映射的物理表名（排除元数据表与系统表）
     *
     * @param datasourceId 数据源注册 id（空=平台主库）
     */
    public List<String> listTables(String datasourceId) {
        List<String> tables = new ArrayList<>();
        try (Connection connection = openConnection(datasourceId)) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();
            String schema = connection.getSchema();
            requireScope(catalog, schema);
            try (ResultSet rs = metaData.getTables(catalog, pattern(metaData, schema), "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    // 保留数据库报告的原始大小写：Linux MySQL（lower_case_table_names=0）表名区分大小写，
                    // 存储的表名要能与 ${tableName} 处的原样 SQL 对上
                    if (inScope(rs, catalog, schema) && name != null && !isExcluded(name)) {
                        tables.add(name);
                    }
                }
            }
        } catch (SQLException e) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "读取表清单失败：" + e.getMessage());
        }
        tables.sort(String::compareTo);
        return tables;
    }

    /**
     * 列出目标表的列元数据（选表导列数据源）
     *
     * @param datasourceId 数据源注册 id（空=平台主库）
     * @param tableName 物理表名
     */
    public List<TableColumnView> listColumns(String datasourceId, String tableName) {
        if (isExcluded(tableName)) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "表不可映射：" + tableName);
        }
        Cache cache = metadataCache();
        String cacheKey = cacheKey(datasourceId, tableName);
        if (cache != null) {
            Cache.ValueWrapper hit = cache.get(cacheKey);
            if (hit != null) {
                @SuppressWarnings("unchecked")
                List<TableColumnView> cached = (List<TableColumnView>) hit.get();
                if (cached != null) {
                    return cached;
                }
            }
        }
        List<TableColumnView> columns = readColumns(datasourceId, tableName);
        if (cache != null) {
            cache.put(cacheKey, new ArrayList<>(columns));
        }
        return columns;
    }

    /** 元数据缓存（缓存 starter 未装配时为 null，直通实时读取） */
    private Cache metadataCache() {
        CacheManager manager = cacheManagerProvider.getIfAvailable();
        return manager == null ? null : manager.getCache(CACHE_NAME);
    }

    /** 缓存键：数据源 id + 表名（表名受 isIdentifier 约束不含冒号，跨源同名表互不串用） */
    private static String cacheKey(String datasourceId, String tableName) {
        return (datasourceId == null || datasourceId.isBlank() ? "" : datasourceId) + ":" + tableName;
    }

    /** 空 id 直用主库连接；非空 id 经解析端口按注册 id 取对应连接池（端口缺席即报错） */
    private Connection openConnection(String datasourceId) throws SQLException {
        if (datasourceId == null || datasourceId.isBlank()) {
            return dataSource.getConnection();
        }
        RuntimeDataSourceResolver resolver = runtimeResolverProvider.getIfAvailable();
        if (resolver == null) {
            throw RuntimeDataSourceResolver.missing();
        }
        return resolver.resolve(datasourceId).getConnection();
    }

    private List<TableColumnView> readColumns(String datasourceId, String tableName) {
        List<TableColumnView> columns = new ArrayList<>();
        try (Connection connection = openConnection(datasourceId)) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();
            String schema = connection.getSchema();
            requireScope(catalog, schema);
            String exactName = resolveTable(metaData, catalog, schema, tableName);
            Set<String> primaryKeys = new HashSet<>();
            try (ResultSet rs = metaData.getPrimaryKeys(catalog, schema, exactName)) {
                while (rs.next()) {
                    if (inScope(rs, catalog, schema) && exactName.equals(rs.getString("TABLE_NAME"))) {
                        primaryKeys.add(rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
                    }
                }
            }
            try (ResultSet rs =
                    metaData.getColumns(catalog, pattern(metaData, schema), pattern(metaData, exactName), "%")) {
                while (rs.next()) {
                    if (inScope(rs, catalog, schema) && exactName.equals(rs.getString("TABLE_NAME"))) {
                        columns.add(toColumnView(rs, primaryKeys));
                    }
                }
            }
        } catch (SQLException e) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "读取列元数据失败：" + e.getMessage());
        }
        if (columns.isEmpty()) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "表不存在或无列：" + tableName);
        }
        return columns;
    }

    /**
     * 目标表的唯一索引列组合（发布期唯一约束校验用；主键索引除外）
     *
     * @param datasourceId 数据源注册 id（空=平台主库）
     * @param tableName 物理表名
     * @return 每个元素为一个唯一索引的有序列名列表（大写）
     */
    public List<List<String>> listUniqueIndexColumns(String datasourceId, String tableName) {
        if (isExcluded(tableName)) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "表不可映射：" + tableName);
        }
        Map<String, List<String>> indexes = new java.util.LinkedHashMap<>();
        try (Connection connection = openConnection(datasourceId)) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();
            String schema = connection.getSchema();
            requireScope(catalog, schema);
            String exactName = resolveTable(metaData, catalog, schema, tableName);
            try (ResultSet rs = metaData.getIndexInfo(catalog, schema, exactName, true, false)) {
                while (rs.next()) {
                    if (!inScope(rs, catalog, schema) || !exactName.equals(rs.getString("TABLE_NAME"))) {
                        continue;
                    }
                    String indexName = rs.getString("INDEX_NAME");
                    String columnName = rs.getString("COLUMN_NAME");
                    if (indexName == null || columnName == null) {
                        continue;
                    }
                    indexes.computeIfAbsent(indexName, key -> new ArrayList<>())
                            .add(columnName.toUpperCase(Locale.ROOT));
                }
            }
        } catch (SQLException e) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "读取索引元数据失败：" + e.getMessage());
        }
        return List.copyOf(indexes.values());
    }

    private String resolveTable(DatabaseMetaData meta, String catalog, String schema, String name) throws SQLException {
        String normalized = meta.storesUpperCaseIdentifiers()
                ? name.toUpperCase(Locale.ROOT)
                : meta.storesLowerCaseIdentifiers() ? name.toLowerCase(Locale.ROOT) : name;
        try (ResultSet rs =
                meta.getTables(catalog, pattern(meta, schema), pattern(meta, normalized), new String[] {"TABLE"})) {
            while (rs.next()) {
                if (inScope(rs, catalog, schema) && normalized.equals(rs.getString("TABLE_NAME"))) {
                    return rs.getString("TABLE_NAME");
                }
            }
        }
        throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "当前库/schema 中不存在目标表：" + name);
    }

    private static boolean inScope(ResultSet rs, String catalog, String schema) throws SQLException {
        return (catalog == null || Objects.equals(catalog, rs.getString("TABLE_CAT")))
                && (schema == null || Objects.equals(schema, rs.getString("TABLE_SCHEM")));
    }

    private static void requireScope(String catalog, String schema) {
        if (catalog == null && schema == null) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "数据源必须指定默认 catalog 或 schema");
        }
    }

    private static String pattern(DatabaseMetaData meta, String identifier) throws SQLException {
        if (identifier == null) {
            return null;
        }
        String escape = meta.getSearchStringEscape();
        return identifier
                .replace(escape, escape + escape)
                .replace("_", escape + "_")
                .replace("%", escape + "%");
    }

    /**
     * 表名是否命中排除模式
     */
    public boolean isExcluded(String tableName) {
        return !properties.isMappableTable(tableName);
    }

    private TableColumnView toColumnView(ResultSet rs, Set<String> primaryKeys) throws SQLException {
        String columnName = rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT);
        String remarks = rs.getString("REMARKS");
        String dbType = rs.getString("TYPE_NAME");
        int length = rs.getInt("COLUMN_SIZE");
        boolean nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
        return new TableColumnView(
                columnName,
                remarks,
                dbType,
                suggestFieldType(dbType, length),
                primaryKeys.contains(columnName),
                nullable,
                length,
                rs.getInt("DATA_TYPE"),
                rs.getObject("DECIMAL_DIGITS") instanceof Number scale ? scale.intValue() : null,
                rs.getString("COLUMN_DEF"),
                "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT")),
                "YES".equalsIgnoreCase(rs.getString("IS_GENERATEDCOLUMN")));
    }

    /**
     * 数据库类型 → FORM 字段类型白名单的建议映射
     */
    static String suggestFieldType(String dbType, int length) {
        String type = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        if (type.contains("char") || type.contains("text") || type.contains("clob")) {
            return length > 500 ? "TEXT" : "STRING";
        }
        if (type.contains("bool") || type.contains("bit")) {
            return "BOOL";
        }
        if (type.contains("int")) {
            return "INT";
        }
        if (type.contains("decimal") || type.contains("numeric") || type.contains("double") || type.contains("float")) {
            return "DECIMAL";
        }
        if (type.contains("datetime") || type.contains("timestamp")) {
            return "DATETIME";
        }
        if (type.contains("date")) {
            return "DATE";
        }
        return "STRING";
    }
}
