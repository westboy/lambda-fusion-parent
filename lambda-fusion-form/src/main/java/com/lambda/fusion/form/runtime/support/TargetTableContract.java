package com.lambda.fusion.form.runtime.support;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.design.model.TableColumnView;
import com.lambda.fusion.form.design.support.TableMetadataReader;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.support.FormModel.FormField;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 发布、回滚、启用与写入共用的目标表契约；物理元数据不存入发布快照或长期缓存。 */
@Component
@RequiredArgsConstructor
public class TargetTableContract {

    private final TableMetadataReader metadataReader;
    private final SqlSessionFactory sqlSessionFactory;

    public Map<String, TableColumnView> validate(FormModel model) {
        Map<String, TableColumnView> columns = metadataReader.listColumns(model.tableName()).stream()
                .collect(Collectors.toMap(TableColumnView::getColumnName, Function.identity()));
        TableColumnView id = requireColumn(columns, FormConstants.COLUMN_ID);
        if (!Boolean.TRUE.equals(id.getPrimaryKey())
                || columns.values().stream()
                                .filter(c -> Boolean.TRUE.equals(c.getPrimaryKey()))
                                .count()
                        != 1) {
            throw invalid("ID 必须是唯一的主键列，不支持复合主键");
        }
        writable(id);
        boolean stringId = textual(id) && id.getLength() >= 19;
        boolean numericId = id.getJdbcType() == Types.BIGINT
                || exactDecimal(id) && id.getScale() != null && id.getScale() == 0 && id.getLength() >= 19;
        if (!stringId && !numericId) {
            throw invalid("ID 必须能无损容纳 19 位雪花 ID（字符列、BIGINT 或 DECIMAL(p>=19,0)）");
        }
        Set<String> supplied = new HashSet<>();
        supplied.add(FormConstants.COLUMN_ID);
        TenantLineHandler tenant = tenantHandler(model);
        if (tenant != null && !tenant.ignoreTable(model.tableName())) {
            TableColumnView column = requireColumn(columns, FormConstants.COLUMN_TENANT_ID);
            writable(column);
            if (!textual(column) || column.getLength() < 32) {
                throw invalid("TENANT_ID 必须是至少 32 字符的文本列");
            }
            Expression tenantId = tenant.getTenantId();
            if (!(tenantId instanceof StringValue value)) {
                throw invalid("当前表单契约要求字符串租户标识");
            }
            validateValue(column, value.getValue(), FormErrorCode.FORM_TABLE_INVALID);
            supplied.add(FormConstants.COLUMN_TENANT_ID);
        } else if (columns.containsKey(FormConstants.COLUMN_TENANT_ID)) {
            throw invalid("含 TENANT_ID 的目标表必须由实际租户插件过滤，不能作为共享表跳过");
        }
        if (!model.skipContractColumns()) {
            for (String name : FormConstants.AUDIT_COLUMNS) {
                TableColumnView column = requireColumn(columns, name);
                writable(column);
                if (name.endsWith("_BY")) {
                    if (!textual(column) || column.getLength() < 32) {
                        throw invalid(name + " 必须是至少 32 字符的文本列");
                    }
                    validateValue(column, AuthUtils.getSafeUsername(), FormErrorCode.FORM_TABLE_INVALID);
                } else if (column.getJdbcType() != Types.TIMESTAMP) {
                    throw invalid(name + " 必须是 DATETIME/TIMESTAMP 列");
                }
                supplied.add(name);
            }
        }
        for (FormField field : model.fields()) {
            TableColumnView column = requireColumn(columns, field.column());
            if (!compatible(field, column)) {
                throw invalid(field.name() + " 类型 " + field.type() + " 与列 " + column.getDbType() + " 不兼容");
            }
            if (!field.persisted()) {
                continue;
            }
            writable(column);
            supplied.add(field.column());
            boolean logicDelete = field.column().equals(model.logicDeleteColumn());
            if (!logicDelete && field.nullable() && !Boolean.TRUE.equals(column.getNullable())) {
                throw invalid(field.name() + " 允许清空，但物理列为 NOT NULL");
            }
            if (StringUtils.hasText(field.defaultValue())) {
                Object value =
                        FormFieldValidator.normalize(field, field.defaultValue(), FormErrorCode.FORM_TABLE_INVALID);
                validateValue(column, value, FormErrorCode.FORM_TABLE_INVALID);
            } else if (!logicDelete
                    && (!field.form().show() || field.form().readonly())
                    && (!field.nullable()
                            || Boolean.TRUE.equals(field.form().rules().get("required"))
                            || !Boolean.TRUE.equals(column.getNullable()))
                    && !hasDefault(column)) {
                throw invalid(field.name() + " 是无法输入的必填列，必须配置表单默认值或数据库默认值");
            }
        }
        if (StringUtils.hasText(model.logicDeleteColumn())) {
            TableColumnView deleted = requireColumn(columns, model.logicDeleteColumn());
            writable(deleted);
            supplied.add(model.logicDeleteColumn());
            validateValue(deleted, 0, FormErrorCode.FORM_TABLE_INVALID);
            validateValue(deleted, 1, FormErrorCode.FORM_TABLE_INVALID);
        }
        for (TableColumnView column : columns.values()) {
            if (!supplied.contains(column.getColumnName())
                    && !Boolean.TRUE.equals(column.getNullable())
                    && !hasDefault(column)
                    && !databaseGenerated(column)) {
                throw invalid("未赋值的必填列：" + column.getColumnName());
            }
        }
        return columns;
    }

    /** 读取也必须检查当前租户上下文，避免框架的缺失租户跳过策略扩大动态接口的范围。 */
    public void validateAccess(FormModel model) {
        TenantLineHandler handler = tenantHandler(model);
        if ((handler == null || handler.ignoreTable(model.tableName()))
                && metadataReader.listColumns(model.tableName()).stream()
                        .anyMatch(column -> FormConstants.COLUMN_TENANT_ID.equals(column.getColumnName()))) {
            throw invalid("含 TENANT_ID 的目标表不能绕过租户过滤读取");
        }
    }

    private TenantLineHandler tenantHandler(FormModel model) {
        List<TenantLineHandler> handlers = sqlSessionFactory.getConfiguration().getInterceptors().stream()
                .filter(MybatisPlusInterceptor.class::isInstance)
                .map(MybatisPlusInterceptor.class::cast)
                .flatMap(interceptor -> interceptor.getInterceptors().stream())
                .filter(TenantLineInnerInterceptor.class::isInstance)
                .map(TenantLineInnerInterceptor.class::cast)
                .map(TenantLineInnerInterceptor::getTenantLineHandler)
                .toList();
        if (handlers.size() > 1) {
            throw invalid("目标数据源存在多个租户插件，无法确定租户契约");
        }
        if (handlers.isEmpty()) {
            if (!model.skipContractColumns()) {
                throw invalid("目标数据源未启用租户插件");
            }
            return null;
        }
        TenantLineHandler handler = handlers.getFirst();
        Expression tenantId = handler.getTenantId();
        if (tenantId == null
                || tenantId instanceof NullValue
                || tenantId instanceof StringValue value && !StringUtils.hasText(value.getValue())) {
            throw invalid("缺少当前租户上下文");
        }
        if (!FormConstants.COLUMN_TENANT_ID.equalsIgnoreCase(handler.getTenantIdColumn())) {
            throw invalid("动态表单只支持 TENANT_ID 租户列");
        }
        if (handler.ignoreTable(model.tableName()) && !model.skipContractColumns()) {
            throw invalid("目标表被实际租户插件忽略，不符合租户表契约");
        }
        return handler;
    }

    public static boolean hasDefault(TableColumnView column) {
        return column.getDefaultValue() != null
                && !"NULL".equalsIgnoreCase(column.getDefaultValue().trim());
    }

    /** 返回可直接绑定的值；数值不截断、不四舍五入。 */
    public static Object validateValue(TableColumnView column, Object value, FormErrorCode error) {
        if (value == null) {
            if (!Boolean.TRUE.equals(column.getNullable())) {
                throw new FormBusinessException(error, column.getColumnName() + " 不允许 null");
            }
            return null;
        }
        try {
            if (textual(column)) {
                if (!(value instanceof String text) || text.codePointCount(0, text.length()) > column.getLength()) {
                    throw new IllegalArgumentException("文本超出列容量或类型不匹配");
                }
            } else if (integer(column)) {
                BigInteger number = value instanceof Boolean bool
                        ? BigInteger.valueOf(bool ? 1 : 0)
                        : new BigDecimal(value.toString()).toBigIntegerExact();
                int bits =
                        switch (column.getJdbcType()) {
                            case Types.TINYINT -> 8;
                            case Types.SMALLINT -> 16;
                            case Types.INTEGER ->
                                column.getDbType().toUpperCase(Locale.ROOT).contains("MEDIUMINT") ? 24 : 32;
                            default -> 64;
                        };
                boolean unsigned = column.getDbType().toUpperCase(Locale.ROOT).contains("UNSIGNED");
                BigInteger min = unsigned
                        ? BigInteger.ZERO
                        : BigInteger.ONE.shiftLeft(bits - 1).negate();
                BigInteger max =
                        BigInteger.ONE.shiftLeft(unsigned ? bits : bits - 1).subtract(BigInteger.ONE);
                if (number.compareTo(min) < 0 || number.compareTo(max) > 0) {
                    throw new IllegalArgumentException("整数超出列范围");
                }
                return number.longValueExact();
            } else if (exactDecimal(column)) {
                BigDecimal number = new BigDecimal(value.toString()).stripTrailingZeros();
                int scale = column.getScale() == null ? 0 : column.getScale();
                int integerDigits = number.signum() == 0 ? 0 : Math.max(0, number.precision() - number.scale());
                if (Math.max(0, number.scale()) > scale
                        || integerDigits > column.getLength() - scale
                        || column.getDbType().toUpperCase(Locale.ROOT).contains("UNSIGNED") && number.signum() < 0) {
                    throw new IllegalArgumentException("数值超出列精度或小数位数");
                }
                return number;
            } else if (value instanceof LocalDateTime dateTime
                    && column.getScale() != null
                    && dateTime.getNano() % (int) Math.pow(10, 9 - Math.min(9, column.getScale())) != 0) {
                throw new IllegalArgumentException("时间超出列的小数秒精度");
            }
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new FormBusinessException(error, column.getColumnName() + " " + e.getMessage());
        }
        return value;
    }

    private static boolean compatible(FormField field, TableColumnView column) {
        return switch (field.type()) {
            case "STRING", "TEXT", "FILE" -> textual(column);
            case "DICT" -> textual(column) || integer(column);
            case "INT" -> integer(column) || exactDecimal(column);
            case "DECIMAL" -> exactDecimal(column);
            case "BOOL" ->
                column.getJdbcType() == Types.BOOLEAN
                        || column.getJdbcType() == Types.TINYINT
                        || column.getJdbcType() == Types.BIT && column.getLength() == 1;
            case "DATE" -> column.getJdbcType() == Types.DATE && !"YEAR".equalsIgnoreCase(column.getDbType());
            case "DATETIME" -> column.getJdbcType() == Types.TIMESTAMP;
            default -> false;
        };
    }

    private static boolean textual(TableColumnView column) {
        return Set.of(
                                Types.CHAR,
                                Types.VARCHAR,
                                Types.LONGVARCHAR,
                                Types.NCHAR,
                                Types.NVARCHAR,
                                Types.LONGNVARCHAR,
                                Types.CLOB,
                                Types.NCLOB)
                        .contains(column.getJdbcType())
                && !Set.of("JSON", "ENUM", "SET").contains(column.getDbType().toUpperCase(Locale.ROOT));
    }

    private static boolean integer(TableColumnView column) {
        return Set.of(Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT)
                .contains(column.getJdbcType());
    }

    private static boolean exactDecimal(TableColumnView column) {
        return column.getJdbcType() == Types.DECIMAL || column.getJdbcType() == Types.NUMERIC;
    }

    private static boolean databaseGenerated(TableColumnView column) {
        return Boolean.TRUE.equals(column.getAutoIncrement()) || Boolean.TRUE.equals(column.getGenerated());
    }

    private static void writable(TableColumnView column) {
        if (databaseGenerated(column)) {
            throw invalid("引擎写入列不能是自增列或数据库生成列：" + column.getColumnName());
        }
    }

    private static TableColumnView requireColumn(Map<String, TableColumnView> columns, String name) {
        TableColumnView column = columns.get(name);
        if (column == null) {
            throw invalid("缺少列：" + name);
        }
        return column;
    }

    private static FormBusinessException invalid(String message) {
        return new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, message);
    }
}
