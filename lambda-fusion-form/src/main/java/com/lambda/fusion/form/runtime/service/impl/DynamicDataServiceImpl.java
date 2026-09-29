package com.lambda.fusion.form.runtime.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.mapper.DynamicTableMapper;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import com.lambda.fusion.form.runtime.service.DynamicDataService;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModel.FormField;
import com.lambda.fusion.form.runtime.support.FormModel.FormRelation;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.form.runtime.support.QueryAstValidator;
import com.lambda.fusion.form.runtime.support.QueryAstValidator.ResolvedCondition;
import com.lambda.fusion.form.runtime.support.QueryAstValidator.ResolvedSort;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    @Override
    public PageView<Map<String, Object>> page(FormModel model, FormDataPageQuery query) {
        astValidator.validate(model, query);
        String selectColumns = String.join(", ", selectColumns(model));
        Map<String, Object> params = new HashMap<>();
        String whereSql = buildWhere(astValidator.resolve(model, query.getConditions()), params);
        String orderSql = buildOrder(astValidator.resolveSorts(model, query.getSorts()));

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
                .records(records)
                .build();
    }

    @Override
    public Map<String, Object> getById(FormModel model, String id) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        Map<String, Object> row =
                dynamicTableMapper.selectById(model.tableName(), String.join(", ", selectColumns(model)), params);
        if (row == null) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_NOT_FOUND);
        }
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String create(FormModel model, Map<String, Object> data) {
        String id = IdWorker.getIdStr();
        Map<String, Object> row = normalize(model, data, true);
        List<String> columns = new ArrayList<>();
        List<String> values = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        columns.add(FormConstants.COLUMN_ID);
        values.add(bind("id", id, params));
        row.forEach((column, value) -> {
            columns.add(column);
            values.add(bind("p" + params.size(), value, params));
        });
        fillAudit(columns, values, params, true);
        dynamicTableMapper.insertDynamic(model.tableName(), columns, values, params);
        return id;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(FormModel model, String id, Map<String, Object> data) {
        getById(model, id);
        Map<String, Object> row = normalize(model, data, false);
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        List<String> assignments = new ArrayList<>();
        row.forEach((column, value) -> assignments.add(column + " = " + bind("p" + params.size(), value, params)));
        assignments.add("UPDATED_BY = " + bind("p" + params.size(), AuthUtils.getSafeUsername(), params));
        assignments.add("UPDATED_AT = " + bind("p" + params.size(), LocalDateTime.now(), params));
        dynamicTableMapper.updateDynamic(model.tableName(), String.join(", ", assignments), params);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(FormModel model, String id) {
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
            assignments.add("UPDATED_BY = " + bind("p" + params.size(), AuthUtils.getSafeUsername(), params));
            assignments.add("UPDATED_AT = " + bind("p" + params.size(), LocalDateTime.now(), params));
            dynamicTableMapper.updateDynamic(model.tableName(), String.join(", ", assignments), params);
            return;
        }
        dynamicTableMapper.deleteById(model.tableName(), params);
    }

    @Override
    public List<Map<String, Object>> tree(FormModel model, String parentValue) {
        if (model.tree() == null || !model.tree().selfRelation()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "该表单不是自关联树类型");
        }
        Map<String, Object> params = new HashMap<>();
        String whereSql = " AND " + model.tree().parentColumn() + " = " + bind("p0", parentValue, params);
        return dynamicTableMapper.selectList(
                model.tableName(),
                String.join(", ", selectColumns(model)),
                whereSql,
                " ORDER BY " + model.tree().nodeColumn() + " ASC, ID DESC",
                params);
    }

    /**
     * 子表是否存在引用记录（子表物理表名经 FormModelProvider 解析）
     */
    private boolean hasChildRows(FormRelation relation, String id) {
        FormModel subModel = modelProvider.require(relation.subFormKey());
        Map<String, Object> params = new HashMap<>();
        params.put("value", id);
        return dynamicTableMapper.countByColumn(subModel.tableName(), relation.foreignKey(), params) > 0;
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
     * 入参归一：按字段白名单裁剪，未知键丢弃；create 补默认值；非空字段缺失一律拒绝
     */
    private Map<String, Object> normalize(FormModel model, Map<String, Object> data, boolean create) {
        Map<String, Object> source = data == null ? Map.of() : data;
        Map<String, Object> row = new LinkedHashMap<>();
        for (FormField field : model.fields()) {
            if (!field.persisted()) {
                continue;
            }
            Object value = lookup(source, field.name());
            if (value == null && create && StringUtils.hasText(field.defaultValue())) {
                value = field.defaultValue();
            }
            if (!field.nullable() && (value == null || (value instanceof String s && s.isBlank()))) {
                throw new FormBusinessException(FormErrorCode.FORM_DATA_INVALID, field.name() + " 不能为空");
            }
            if (value != null) {
                row.put(field.column(), value);
            }
        }
        if (create && row.isEmpty()) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_INVALID, "提交数据为空");
        }
        return row;
    }

    /**
     * 入参键匹配：同时接受字段标识与物理列名（大小写不敏感）
     */
    private Object lookup(Map<String, Object> source, String fieldName) {
        if (source.containsKey(fieldName)) {
            return source.get(fieldName);
        }
        String upper = fieldName.toUpperCase(Locale.ROOT);
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() != null
                    && entry.getKey().toUpperCase(Locale.ROOT).equals(upper)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 审计四列填充（创建/更新人、时间）
     */
    private void fillAudit(List<String> columns, List<String> values, Map<String, Object> params, boolean create) {
        String operator = AuthUtils.getSafeUsername();
        LocalDateTime now = LocalDateTime.now();
        if (create) {
            columns.add("CREATED_BY");
            values.add(bind("p" + params.size(), operator, params));
            columns.add("CREATED_AT");
            values.add(bind("p" + params.size(), now, params));
        }
        columns.add("UPDATED_BY");
        values.add(bind("p" + params.size(), operator, params));
        columns.add("UPDATED_AT");
        values.add(bind("p" + params.size(), now, params));
    }

    private String bind(String key, Object value, Map<String, Object> params) {
        params.put(key, value);
        return "#{params." + key + "}";
    }

    private String buildWhere(List<ResolvedCondition> conditions, Map<String, Object> params) {
        if (conditions.isEmpty()) {
            return null;
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
                            .append(" LIKE CONCAT('%', ")
                            .append(bind(nextKey(params), condition.value(), params))
                            .append(", '%')");
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
                case "in" ->
                    sql.append(" AND ")
                            .append(column)
                            .append(" IN (")
                            .append(bind(nextKey(params), asCollection(condition.value()), params))
                            .append(")");
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

    private String buildOrder(List<ResolvedSort> sorts) {
        if (sorts.isEmpty()) {
            return " ORDER BY CREATED_AT DESC, ID DESC";
        }
        return " ORDER BY "
                + sorts.stream()
                        .map(sort -> sort.column() + (sort.asc() ? " ASC" : " DESC"))
                        .collect(Collectors.joining(", "))
                + ", ID DESC";
    }

    private Collection<?> asCollection(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection;
        }
        if (value instanceof Object[] array) {
            return List.of(array);
        }
        if (value instanceof String text && text.contains(",")) {
            return List.of(text.split(","));
        }
        throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "in 操作符需要数组值");
    }

    private List<Object> asRange(Object value) {
        if (value instanceof Collection<?> collection && collection.size() == 2) {
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
