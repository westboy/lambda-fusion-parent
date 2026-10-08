package com.lambda.fusion.form.runtime.support;

import com.lambda.autoconfig.MybatisPlusExtendProperties;
import com.lambda.cloud.mybatis.tenant.MissingTenantStrategy;
import com.lambda.cloud.mybatis.tenant.TenantContextHolder;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.design.model.TableColumnView;
import com.lambda.fusion.form.design.support.TableMetadataReader;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.support.FormModel.FormField;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 发布、回滚、启用与写入共用的目标表契约；物理元数据不存入发布快照或长期缓存。 */
@Component
@RequiredArgsConstructor
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "读取框架统一租户配置，不修改配置对象")
public class TargetTableContract {

    private final TableMetadataReader metadataReader;
    private final MybatisPlusExtendProperties mybatisProperties;

    public Map<String, TableColumnView> validate(FormModel model) {
        return validate(model, Set.of());
    }

    /**
     * @param serverBoundColumns 值由服务端绑定而非表单输入的列（如主从外键列由引擎回填主行 id）。
     *   这些列允许「落库 + 表单不可见 + 数据库 NOT NULL」而无默认值——用户本就不该输入它们。
     */
    public Map<String, TableColumnView> validate(FormModel model, Set<String> serverBoundColumns) {
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
        TableColumnView tenantColumn = validateTenantRule(model, columns);
        if (tenantColumn != null) {
            supplied.add(tenantColumn.getColumnName());
        }
        // 审计列按物理存在自动适配：存在则校验并由引擎填充，不存在则跳过要求
        for (String name : FormConstants.AUDIT_COLUMNS) {
            TableColumnView column = columns.get(name);
            if (column == null) {
                continue;
            }
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
        for (FormField field : model.fields()) {
            // FLAT 从表字段的列在子表上，主表契约不校验（存在性由保存期分来源校验兜住）
            if (field.subFormKey() != null) {
                continue;
            }
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
                    && !serverBoundColumns.contains(field.column().toUpperCase(Locale.ROOT))
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
        // SELF 自关联树：父列必须允许「无父」（根节点语义）——物理 NOT NULL 且无默认值的父列创建根节点必失败
        if (model.tree() != null && model.tree().selfRelation()) {
            TableColumnView parentColumn = columns.get(model.tree().parentColumn());
            if (parentColumn != null && !Boolean.TRUE.equals(parentColumn.getNullable()) && !hasDefault(parentColumn)) {
                throw invalid("SELF 树父列 " + model.tree().parentColumn() + " 必须可空或带数据库默认值（根节点要求父列允许空值）");
            }
        }
        return columns;
    }

    /**
     * 读取只校验租户契约（无租户上下文的平台管理模式直接放行），避免含 TENANT_ID 列的表在业务
     * 模式下被无过滤读写；返回列元数据供默认排序等复用。
     */
    public Map<String, TableColumnView> validateAccess(FormModel model) {
        Map<String, TableColumnView> columns = metadataReader.listColumns(model.tableName()).stream()
                .collect(Collectors.toMap(TableColumnView::getColumnName, Function.identity()));
        validateTenantRule(model, columns);
        return columns;
    }

    /**
     * 发布期补充校验（仅发布流程调用，不进运行时读写路径）：标记唯一（rules.unique）的主表落库字段，
     * 目标表必须有覆盖该列的唯一索引——索引列集合 ⊆ {该列, TENANT_ID}（多租户下联合唯一才有意义；
     * 引擎的写入查重只是应用层兜底，并发防线最终由数据库唯一索引承担）。
     */
    public void validateUniqueIndexes(FormModel model) {
        List<String> uniqueColumns = model.fields().stream()
                .filter(field -> field.subFormKey() == null
                        && field.persisted()
                        && Boolean.TRUE.equals(field.form().rules().get("unique")))
                .map(FormModel.FormField::column)
                .toList();
        if (uniqueColumns.isEmpty()) {
            return;
        }
        List<List<String>> indexes = metadataReader.listUniqueIndexColumns(model.tableName());
        for (String column : uniqueColumns) {
            boolean covered = indexes.stream()
                    .anyMatch(index -> index.contains(column)
                            && index.stream()
                                    .allMatch(indexColumn -> indexColumn.equals(column)
                                            || FormConstants.COLUMN_TENANT_ID.equals(indexColumn)));
            if (!covered) {
                throw invalid("唯一字段缺少唯一索引：" + column + "（可建该列单列唯一索引，或与 TENANT_ID 联合唯一索引）");
            }
        }
    }

    /**
     * 租户契约区分两种模式。请求无租户上下文即平台管理/开发者模式（契约 §5.1 的受控平台流程，
     * 登录与权限入口仍生效）：框架策略本就全表跳过注入，引擎不代填租户列也不设防，租户列约束由
     * 数据库表态。请求带租户上下文时按业务契约校验：未豁免的表必须有可写的 TENANT_ID 列并校验
     * 当前租户值；表被豁免清单跳过时，含 TENANT_ID 列等于对全租户关掉隔离，必须拒绝，不含该列的
     * 全局表直接放行。插件装配与开关一致性由框架自动配置保证，这里只读取其配置。
     *
     * @return 注入生效时要求的目标表 TENANT_ID 列（需豁免出赋值检查），否则 null
     */
    private TableColumnView validateTenantRule(FormModel model, Map<String, TableColumnView> columns) {
        MybatisPlusExtendProperties.TenantConfig tenant = mybatisProperties.getTenant();
        if (!Boolean.TRUE.equals(tenant.getEnabled()) || MissingTenantStrategy.isMissingTenantContext()) {
            // 单租户运行或平台管理/开发者模式：框架全表跳过注入，引擎不代填租户列，约束由数据库表态。
            return null;
        }
        if (tenant.getIgnoreTables().contains(model.tableName().toLowerCase(Locale.ROOT))) {
            if (columns.containsKey(FormConstants.COLUMN_TENANT_ID)) {
                throw invalid("目标表含 TENANT_ID 列，但在租户豁免清单（mybatis-plus.tenant.ignore-tables 或框架默认清单）中，"
                        + "租户过滤不会生效，禁止动态读写；请将表移出豁免清单，或删除 TENANT_ID 列改作全局表");
            }
            return null;
        }
        TableColumnView column = columns.get(FormConstants.COLUMN_TENANT_ID);
        if (column == null) {
            throw invalid("目标表已纳入租户过滤但缺少 TENANT_ID 列：请为表补充该列，" + "或将表加入 mybatis-plus.tenant.ignore-tables 作为全局表");
        }
        writable(column);
        if (!textual(column) || column.getLength() < 32) {
            throw invalid("TENANT_ID 必须是至少 32 字符的文本列");
        }
        validateValue(column, TenantContextHolder.getCurrentTenantId(), FormErrorCode.FORM_TABLE_INVALID);
        return column;
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
            // ORG/USER/ROLE 存 id/authority（字符串），与文本列同兼容口径
            case "STRING", "TEXT", "FILE", "ORG", "USER", "ROLE" -> textual(column);
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
