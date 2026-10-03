package com.lambda.fusion.form.runtime.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.FormProperties;
import com.lambda.fusion.form.design.model.TableColumnView;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.mapper.DynamicTableMapper;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import com.lambda.fusion.form.runtime.service.DynamicDataService;
import com.lambda.fusion.form.runtime.support.FormFieldValidator;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModel.FormField;
import com.lambda.fusion.form.runtime.support.FormModel.FormRelation;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.form.runtime.support.QueryAstValidator;
import com.lambda.fusion.form.runtime.support.QueryAstValidator.ResolvedCondition;
import com.lambda.fusion.form.runtime.support.QueryAstValidator.ResolvedSort;
import com.lambda.fusion.form.runtime.support.TargetTableContract;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 动态表单数据读写实现
 *
 * <p>表名/列名全部来自 {@link FormModel} 白名单，值全部参数化绑定；
 * 审计四列在此显式填充（GlobalMetaObjectHandler 只覆盖实体路径）；
 * 租户过滤与 INSERT 租户列填充由框架租户插件承担，本类不写租户处理。
 *
 * @author Zx
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP")
public class DynamicDataServiceImpl implements DynamicDataService {

    private static final int MAX_PAGE_SIZE = 500;

    private final DynamicTableMapper dynamicTableMapper;
    private final QueryAstValidator astValidator;
    private final FormModelProvider modelProvider;
    private final TargetTableContract targetTableContract;
    private final FormProperties formProperties;

    @Override
    public PageView<Map<String, Object>> page(FormModel model, FormDataPageQuery query) {
        Map<String, TableColumnView> tableColumns = targetTableContract.validateAccess(model);
        astValidator.validate(model, query);
        String selectColumns = String.join(", ", selectColumns(model));
        Map<String, Object> params = new HashMap<>();
        String whereSql = activeRows(model)
                + buildWhere(astValidator.resolve(model, query.getConditions()), params)
                + buildTreeFilter(model, query.getTreeNode(), params);
        String orderSql = buildOrder(astValidator.resolveSorts(model, query.getSorts()), tableColumns);

        long pageNum = currentPage(query);
        long pageSize = pageSize(query);
        long total = dynamicTableMapper.countBy(model.tableName(), whereSql, params);
        if (total == 0) {
            return PageView.<Map<String, Object>>builder()
                    .pageNum(pageNum)
                    .pageSize(pageSize)
                    .total(0)
                    .totalPages(0)
                    .records(List.of())
                    .build();
        }
        String pageSql = orderSql + " LIMIT " + pageSize + " OFFSET " + ((pageNum - 1) * pageSize);
        List<Map<String, Object>> records =
                dynamicTableMapper.selectList(model.tableName(), selectColumns, whereSql, pageSql, params);
        return PageView.<Map<String, Object>>builder()
                .pageNum(pageNum)
                .pageSize(pageSize)
                .total(total)
                .totalPages((total + pageSize - 1) / pageSize)
                .records(records.stream().map(row -> project(model, row)).toList())
                .build();
    }

    @Override
    public Map<String, Object> getById(FormModel model, String id) {
        targetTableContract.validateAccess(model);
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        Map<String, Object> row = dynamicTableMapper.selectById(
                model.tableName(), String.join(", ", selectColumns(model)), activeRows(model), params);
        if (row == null) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_NOT_FOUND);
        }
        return project(model, row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String create(FormModel model, Map<String, Object> data) {
        String id = IdWorker.getIdStr();
        Map<String, TableColumnView> tableColumns = targetTableContract.validate(model);
        Map<String, Object> row = normalize(model, data, true, tableColumns);
        List<String> columns = new ArrayList<>();
        List<String> values = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        columns.add(FormConstants.COLUMN_ID);
        values.add(bind("id", id, params));
        row.forEach((column, value) -> {
            columns.add(column);
            values.add(bind("p" + params.size(), value, params));
        });
        if (StringUtils.hasText(model.logicDeleteColumn())) {
            columns.add(model.logicDeleteColumn());
            values.add(bind("p" + params.size(), 0, params));
        }
        fillAudit(tableColumns, columns, values, params, true);
        dynamicTableMapper.insertDynamic(model.tableName(), columns, values, params);
        List<FormField> defaulted = model.fields().stream()
                .filter(field -> field.persisted()
                        && !field.column().equals(model.logicDeleteColumn())
                        && !row.containsKey(field.column())
                        && TargetTableContract.hasDefault(tableColumns.get(field.column())))
                .toList();
        if (!defaulted.isEmpty()) {
            // SQL 默认值表达式只由数据库求值，实际结果仍遵守字段规则；失败随新增事务回滚。
            Map<String, Object> inserted = getById(model, id);
            defaulted.forEach(field -> {
                Object value = FormFieldValidator.normalize(
                        field, inserted.get(field.name()), FormErrorCode.FORM_DATA_INVALID);
                TargetTableContract.validateValue(
                        tableColumns.get(field.column()), value, FormErrorCode.FORM_DATA_INVALID);
            });
        }
        return id;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(FormModel model, String id, Map<String, Object> data) {
        getById(model, id);
        Map<String, TableColumnView> tableColumns = targetTableContract.validate(model);
        Map<String, Object> row = normalize(model, data, false, tableColumns);
        if (row.isEmpty()) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_INVALID, "没有可更新的字段");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        List<String> assignments = new ArrayList<>();
        row.forEach((column, value) -> assignments.add(column + " = " + bind("p" + params.size(), value, params)));
        if (tableColumns.containsKey("UPDATED_BY")) {
            assignments.add("UPDATED_BY = " + bind("p" + params.size(), AuthUtils.getSafeUsername(), params));
        }
        if (tableColumns.containsKey("UPDATED_AT")) {
            assignments.add("UPDATED_AT = " + bind("p" + params.size(), LocalDateTime.now(), params));
        }
        if (dynamicTableMapper.updateDynamic(
                        model.tableName(), String.join(", ", assignments), activeRows(model), params)
                == 0) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_NOT_FOUND);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(FormModel model, String id) {
        Map<String, TableColumnView> tableColumns = targetTableContract.validate(model);
        getById(model, id);
        // 级联删除依赖子表物理表名解析（P1 主从落地时补齐）；P0 一律拒绝删除有子表数据的记录
        for (FormRelation relation : model.relations()) {
            if (hasChildRows(relation, id)) {
                throw new FormBusinessException(FormErrorCode.FORM_SUB_DELETE_FORBIDDEN, relation.subFormKey());
            }
        }
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        String logicDeleteColumn = model.logicDeleteColumn();
        if (logicDeleteColumn != null && !logicDeleteColumn.isBlank()) {
            List<String> assignments = new ArrayList<>();
            assignments.add(logicDeleteColumn + " = 1");
            if (tableColumns.containsKey("UPDATED_BY")) {
                assignments.add("UPDATED_BY = " + bind("p" + params.size(), AuthUtils.getSafeUsername(), params));
            }
            if (tableColumns.containsKey("UPDATED_AT")) {
                assignments.add("UPDATED_AT = " + bind("p" + params.size(), LocalDateTime.now(), params));
            }
            if (dynamicTableMapper.updateDynamic(
                            model.tableName(), String.join(", ", assignments), activeRows(model), params)
                    == 0) {
                throw new FormBusinessException(FormErrorCode.FORM_DATA_NOT_FOUND);
            }
            return;
        }
        if (dynamicTableMapper.deleteById(model.tableName(), activeRows(model), params) == 0) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_NOT_FOUND);
        }
    }

    @Override
    public List<Map<String, Object>> tree(FormModel model, String parentValue) {
        targetTableContract.validateAccess(model);
        if (model.tree() == null || !model.tree().selfRelation()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "该表单不是自关联树类型");
        }
        Map<String, Object> params = new HashMap<>();
        // parentValue 为 null 或空串都表示根节点（parent IS NULL），供前端懒加载首层
        String condition = StringUtils.hasText(parentValue) ? " = " + bind("p0", parentValue, params) : " IS NULL";
        String whereSql = activeRows(model) + " AND " + model.tree().parentColumn() + condition;
        return dynamicTableMapper
                .selectList(
                        model.tableName(),
                        String.join(", ", selectColumns(model)),
                        whereSql,
                        " ORDER BY " + model.tree().nodeColumn() + " ASC, ID DESC",
                        params)
                .stream()
                .map(row -> project(model, row))
                .toList();
    }

    /**
     * 子表是否存在引用记录（子表物理表名经 FormModelProvider 解析）
     */
    private boolean hasChildRows(FormRelation relation, String id) {
        FormModel subModel = modelProvider.require(relation.subFormKey());
        if (subModel.fields().stream().noneMatch(field -> field.column().equals(relation.foreignKey()))) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "子表外键未映射：" + relation.foreignKey());
        }
        Map<String, Object> params = new HashMap<>();
        params.put("value", id);
        return dynamicTableMapper.countBy(
                        subModel.tableName(),
                        activeRows(subModel) + " AND " + relation.foreignKey() + " = #{params.value}",
                        params)
                > 0;
    }

    /**
     * 目标表列清单（白名单：ID + 已映射字段）
     */
    private List<String> selectColumns(FormModel model) {
        List<String> columns = new ArrayList<>();
        columns.add(FormConstants.COLUMN_ID);
        model.fields().forEach(field -> columns.add(field.column()));
        return columns;
    }

    /**
     * 入参归一：仅接受字段标识；新增补默认值，更新时未提交字段保持原值、显式 null 表示清空。
     */
    private Map<String, Object> normalize(
            FormModel model, Map<String, Object> data, boolean create, Map<String, TableColumnView> columns) {
        Map<String, Object> source = data == null ? Map.of() : data;
        Map<String, Object> row = new LinkedHashMap<>();
        for (FormField field : model.fields()) {
            if (FormConstants.isSystemColumn(field.column()) || "id".equalsIgnoreCase(field.name())) {
                throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "禁止映射系统字段：" + field.column());
            }
            if (!field.persisted() || field.column().equalsIgnoreCase(model.logicDeleteColumn())) {
                continue;
            }
            boolean submitted = source.containsKey(field.name());
            if (!create && !submitted) {
                continue;
            }
            Object value = source.get(field.name());
            if (!submitted && create && StringUtils.hasText(field.defaultValue())) {
                value = field.defaultValue();
            }
            TableColumnView column = columns.get(field.column());
            if (create && !submitted && value == null && TargetTableContract.hasDefault(column)) {
                continue;
            }
            value = FormFieldValidator.normalize(field, value, FormErrorCode.FORM_DATA_INVALID);
            value = TargetTableContract.validateValue(column, value, FormErrorCode.FORM_DATA_INVALID);
            if (submitted || value != null) {
                row.put(field.column(), value);
            }
        }
        return row;
    }

    /**
     * 结果按字段标识投影，屏蔽 JDBC 驱动的列名大小写差异，并保留空值键。
     */
    private Map<String, Object> project(FormModel model, Map<String, Object> row) {
        Map<String, Object> columns = new HashMap<>();
        row.forEach((key, value) -> columns.put(key.toUpperCase(Locale.ROOT), value));
        Map<String, Object> result = new LinkedHashMap<>();
        Object id = columns.get(FormConstants.COLUMN_ID);
        result.put("id", id == null ? null : id.toString());
        model.fields().forEach(field -> result.put(field.name(), columns.get(field.column())));
        return result;
    }

    /** 逻辑删除统一约定：0 有效、1 已删除，由引擎维护。 */
    private String activeRows(FormModel model) {
        return StringUtils.hasText(model.logicDeleteColumn()) ? " AND " + model.logicDeleteColumn() + " = 0" : "";
    }

    /**
     * 审计四列按物理存在填充（创建/更新人、时间）；目标表没有的列直接跳过。
     */
    private void fillAudit(
            Map<String, TableColumnView> tableColumns,
            List<String> columns,
            List<String> values,
            Map<String, Object> params,
            boolean create) {
        String operator = AuthUtils.getSafeUsername();
        LocalDateTime now = LocalDateTime.now();
        if (create && tableColumns.containsKey("CREATED_BY")) {
            columns.add("CREATED_BY");
            values.add(bind("p" + params.size(), operator, params));
        }
        if (create && tableColumns.containsKey("CREATED_AT")) {
            columns.add("CREATED_AT");
            values.add(bind("p" + params.size(), now, params));
        }
        if (tableColumns.containsKey("UPDATED_BY")) {
            columns.add("UPDATED_BY");
            values.add(bind("p" + params.size(), operator, params));
        }
        if (tableColumns.containsKey("UPDATED_AT")) {
            columns.add("UPDATED_AT");
            values.add(bind("p" + params.size(), now, params));
        }
    }

    private String bind(String key, Object value, Map<String, Object> params) {
        params.put(key, value);
        return "#{params." + key + "}";
    }

    private String buildWhere(List<ResolvedCondition> conditions, Map<String, Object> params) {
        if (conditions.isEmpty()) {
            return "";
        }
        StringBuilder sql = new StringBuilder();
        for (ResolvedCondition condition : conditions) {
            String column = condition.column();
            switch (condition.op()) {
                case "eq" ->
                    sql.append(" AND ")
                            .append(column)
                            .append(" = ")
                            .append(bind(nextKey(params), condition.value(), params));
                case "ne" ->
                    sql.append(" AND ")
                            .append(column)
                            .append(" <> ")
                            .append(bind(nextKey(params), condition.value(), params));
                case "like" ->
                    sql.append(" AND ")
                            .append(column)
                            .append(" LIKE ")
                            .append(bind(nextKey(params), "%" + condition.value() + "%", params));
                case "ge" ->
                    sql.append(" AND ")
                            .append(column)
                            .append(" >= ")
                            .append(bind(nextKey(params), condition.value(), params));
                case "le" ->
                    sql.append(" AND ")
                            .append(column)
                            .append(" <= ")
                            .append(bind(nextKey(params), condition.value(), params));
                case "isNull" -> sql.append(" AND ").append(column).append(" IS NULL");
                case "isNotNull" -> sql.append(" AND ").append(column).append(" IS NOT NULL");
                case "in" -> {
                    Collection<?> values = asCollection(condition.value());
                    sql.append(" AND ")
                            .append(column)
                            .append(" IN (")
                            .append(values.stream()
                                    .map(value -> bind(nextKey(params), value, params))
                                    .collect(Collectors.joining(", ")))
                            .append(")");
                }
                case "between" -> {
                    List<Object> range = asRange(condition.value());
                    sql.append(" AND ")
                            .append(column)
                            .append(" BETWEEN ")
                            .append(bind(nextKey(params), range.get(0), params))
                            .append(" AND ")
                            .append(bind(nextKey(params), range.get(1), params));
                }
                default ->
                    throw new FormBusinessException(FormErrorCode.FORM_QUERY_OPERATOR_UNSUPPORTED, condition.op());
            }
        }
        return sql.toString();
    }

    private String nextKey(Map<String, Object> params) {
        return "p" + params.size();
    }

    /**
     * TREE 类型左树选中节点时过滤「自身 + 全部子孙」：按 parentColumn 逐层递归取 id 集
     * （每层一条参数化 in 查询），对 filterColumn 列 in 过滤；子孙集上限
     * lambda.fusion.form.tree.max-descendants，超限抛 FORM_TREE_DESCENDANT_LIMIT。
     * 环状数据由上限兜底终止（无环时集合收敛即停）。
     */
    private String buildTreeFilter(
            FormModel model, FormDataPageQuery.TreeNodeFilter treeNode, Map<String, Object> params) {
        if (treeNode == null || treeNode.value() == null) {
            return "";
        }
        if (model.tree() == null || !model.tree().selfRelation()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "该表单不是自关联树类型");
        }
        Set<String> ids = new HashSet<>();
        List<String> frontier = List.of(treeNode.value());
        int max = formProperties.getMaxTreeDescendants();
        while (!frontier.isEmpty()) {
            if (ids.size() + frontier.size() > max) {
                throw new FormBusinessException(FormErrorCode.FORM_TREE_DESCENDANT_LIMIT);
            }
            String inSql = frontier.stream()
                    .map(value -> bind(nextKey(params), value, params))
                    .collect(Collectors.joining(", "));
            List<Map<String, Object>> children = dynamicTableMapper.selectList(
                    model.tableName(),
                    FormConstants.COLUMN_ID + ", " + model.tree().parentColumn(),
                    activeRows(model) + " AND " + model.tree().parentColumn() + " IN (" + inSql + ")",
                    null,
                    params);
            // 原始行键大小写随驱动漂移，与 project() 同样归一后再取 ID
            frontier = children.stream()
                    .map(row -> {
                        Object id = null;
                        for (Map.Entry<String, Object> entry : row.entrySet()) {
                            if (FormConstants.COLUMN_ID.equals(entry.getKey().toUpperCase(Locale.ROOT))) {
                                id = entry.getValue();
                                break;
                            }
                        }
                        return id == null ? null : id.toString();
                    })
                    .filter(Objects::nonNull)
                    .filter(ids::add)
                    .collect(Collectors.toList());
        }
        ids.add(treeNode.value());
        // SELF 自关联：树行即业务行，子树按主键过滤；filterColumn 预留给 DICT 等外部树来源
        String inSql = ids.stream().map(id -> bind(nextKey(params), id, params)).collect(Collectors.joining(", "));
        return " AND " + FormConstants.COLUMN_ID + " IN (" + inSql + ")";
    }

    private String buildOrder(List<ResolvedSort> sorts, Map<String, TableColumnView> tableColumns) {
        if (sorts.isEmpty()) {
            // 默认排序按目标表是否含 CREATED_AT 自动适配
            return tableColumns != null && tableColumns.containsKey("CREATED_AT")
                    ? " ORDER BY CREATED_AT DESC, ID DESC"
                    : " ORDER BY ID DESC";
        }
        return " ORDER BY "
                + sorts.stream()
                        .map(sort -> sort.column() + (sort.asc() ? " ASC" : " DESC"))
                        .collect(Collectors.joining(", "))
                + ", ID DESC";
    }

    private Collection<?> asCollection(Object value) {
        if (value instanceof Collection<?> collection
                && !collection.isEmpty()
                && collection.stream().noneMatch(java.util.Objects::isNull)) {
            return collection;
        }
        if (value instanceof Object[] array) {
            return asCollection(Arrays.asList(array));
        }
        throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "in 操作符需要不含 null 的非空数组");
    }

    private List<Object> asRange(Object value) {
        if (value instanceof Collection<?> collection
                && collection.size() == 2
                && collection.stream().noneMatch(java.util.Objects::isNull)) {
            return new ArrayList<>(collection);
        }
        throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "between 操作符需要长度 2 的数组");
    }

    private long currentPage(FormDataPageQuery query) {
        Long pageNum = query.getPageNum() == null ? 1L : query.getPageNum().longValue();
        return Math.max(1L, pageNum);
    }

    private long pageSize(FormDataPageQuery query) {
        Long size = query.getPageSize() == null ? 20L : query.getPageSize().longValue();
        return Math.min(Math.max(1L, size), MAX_PAGE_SIZE);
    }
}
