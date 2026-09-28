package com.lambda.fusion.form.design.support;

import com.lambda.fusion.form.FormConstants;
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
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 物理表元数据只读读取（选表导列与发布校验共用）
 *
 * <p>经 JDBC DatabaseMetaData introspection，不执行任何 DDL；排除表由
 * {@link FormProperties#getTableExcludePattern()} 过滤。catalog/schema 传 null
 * 表示使用当前连接的默认库/schema。
 *
 * @author Zx
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class TableMetadataReader {

    private final DataSource dataSource;
    private final FormProperties properties;

    /**
     * 列出可映射的物理表名（排除元数据表与系统表）
     */
    public List<String> listTables() {
        List<String> tables = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getTables(null, null, "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    // 保留数据库报告的原始大小写：Linux MySQL（lower_case_table_names=0）表名区分大小写，
                    // 存储的表名要能与 ${tableName} 处的原样 SQL 对上
                    if (name != null && !isExcluded(name)) {
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
     * @param tableName 物理表名
     */
    public List<TableColumnView> listColumns(String tableName) {
        if (isExcluded(tableName)) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "表不可映射：" + tableName);
        }
        List<TableColumnView> columns = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getColumns(null, null, tableName, "%")) {
                while (rs.next()) {
                    columns.add(toColumnView(rs));
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
     * 目标表是否存在于当前库
     */
    public boolean tableExists(String tableName) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getTables(null, null, tableName, new String[] {"TABLE"})) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "表存在性校验失败：" + e.getMessage());
        }
    }

    /**
     * 表名是否命中排除模式
     */
    public boolean isExcluded(String tableName) {
        return tableName == null || properties.tableExclude().matcher(tableName).matches();
    }

    private TableColumnView toColumnView(ResultSet rs) throws SQLException {
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
                FormConstants.COLUMN_ID.equals(columnName),
                nullable,
                length);
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
