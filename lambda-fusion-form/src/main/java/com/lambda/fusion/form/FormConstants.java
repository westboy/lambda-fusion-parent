package com.lambda.fusion.form;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Form 模块常量：表单类型、状态、字段类型与操作符白名单
 *
 * @author Zx
 */
public final class FormConstants {

    private FormConstants() {}

    /** 标识符白名单：表名/列名只允许字母开头 + 字母数字下划线 */
    public static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    /**
     * 是否合法标识符：动态 SQL 中拼入表名/列名的字符串必须通过本校验（契约 §16）
     *
     * @param value 待校验标识符
     */
    public static boolean isIdentifier(String value) {
        return value != null && IDENTIFIER_PATTERN.matcher(value).matches();
    }

    /** 运行时权限码模板：form:{formKey}:{action} */
    public static final String RUNTIME_PERMISSION_TEMPLATE = "form:%s:%s";

    /** design 已用于管理接口命名空间，不能成为业务表单标识。 */
    public static boolean isFormKey(String value) {
        return isIdentifier(value) && !"design".equalsIgnoreCase(value);
    }

    /** 表单类型 */
    public static final String FORM_TYPE_SINGLE = "SINGLE";

    public static final String FORM_TYPE_TREE = "TREE";

    public static final String FORM_TYPE_MASTER_SUB = "MASTER_SUB";

    public static final Set<String> FORM_TYPES = Set.of(FORM_TYPE_SINGLE, FORM_TYPE_TREE, FORM_TYPE_MASTER_SUB);

    /** 表单状态 */
    public static final String STATUS_DRAFT = "DRAFT";

    public static final String STATUS_PUBLISHED = "PUBLISHED";

    public static final String STATUS_DISABLED = "DISABLED";

    /** 字段类型白名单 */
    public static final Set<String> FIELD_TYPES =
            Set.of("STRING", "TEXT", "INT", "DECIMAL", "DATE", "DATETIME", "DICT", "BOOL", "FILE");

    /** 查询操作符白名单 */
    public static final Set<String> QUERY_OPERATORS =
            Set.of("eq", "ne", "like", "in", "between", "ge", "le", "isNull", "isNotNull");

    /** 审计四列（目标表发布校验必备） */
    public static final String COLUMN_TENANT_ID = "TENANT_ID";

    public static final Set<String> AUDIT_COLUMNS = Set.of("CREATED_BY", "CREATED_AT", "UPDATED_BY", "UPDATED_AT");

    /** 主键列名（目标表约定） */
    public static final String COLUMN_ID = "ID";

    /** 系统列由引擎与框架维护，不允许作为业务字段映射。 */
    public static boolean isSystemColumn(String column) {
        if (column == null) {
            return false;
        }
        String normalized = column.toUpperCase(Locale.ROOT);
        return COLUMN_ID.equals(normalized)
                || COLUMN_TENANT_ID.equals(normalized)
                || AUDIT_COLUMNS.contains(normalized);
    }

    /** 级联策略 */
    public static final String CASCADE_DELETE = "CASCADE_DELETE";

    public static final String FORBID_DELETE = "FORBID_DELETE";
}
