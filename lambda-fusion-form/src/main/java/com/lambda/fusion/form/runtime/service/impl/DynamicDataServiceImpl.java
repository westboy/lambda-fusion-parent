package com.lambda.fusion.form.runtime.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.FormProperties;
import com.lambda.fusion.form.design.model.TableColumnView;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.integration.FormDataInterceptors;
import com.lambda.fusion.form.runtime.mapper.DynamicTableMapper;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import com.lambda.fusion.form.runtime.service.DynamicDataService;
import com.lambda.fusion.form.runtime.support.FormAction;
import com.lambda.fusion.form.runtime.support.FormFieldValidator;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModel.FormField;
import com.lambda.fusion.form.runtime.support.FormModel.FormRelation;
import com.lambda.fusion.form.runtime.support.FormModel.TreeConfig;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.form.runtime.support.QueryAstValidator;
import com.lambda.fusion.form.runtime.support.QueryAstValidator.ResolvedCondition;
import com.lambda.fusion.form.runtime.support.QueryAstValidator.ResolvedSort;
import com.lambda.fusion.form.runtime.support.TargetTableContract;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

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

    /** 行指纹在提交体中的保留键（下划线开头，不可能是合法字段标识） */
    private static final String ETAG_KEY = "__etag";

    private final DynamicTableMapper dynamicTableMapper;
    private final QueryAstValidator astValidator;
    private final FormModelProvider modelProvider;
    private final TargetTableContract targetTableContract;
    private final FormProperties formProperties;
    private final FormDataInterceptors interceptors;
    private final ObjectMapper objectMapper;

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
        List<Map<String, Object>> projected =
                records.stream().map(row -> project(model, row)).toList();
        attachSubRows(model, projected);
        projected.forEach(row -> row.put(ETAG_KEY, etagOf(row)));
        return PageView.<Map<String, Object>>builder()
                .pageNum(pageNum)
                .pageSize(pageSize)
                .total(total)
                .totalPages((total + pageSize - 1) / pageSize)
                .records(projected)
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
        Map<String, Object> result = project(model, row);
        attachSubRows(model, List.of(result));
        result.put(ETAG_KEY, etagOf(result));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String create(FormModel model, Map<String, Object> data) {
        rejectListModeSubData(model, data);
        interceptors.resolve(model.interceptor()).ifPresent(i -> i.beforeCreate(model, data));
        String id = IdWorker.getIdStr();
        Map<String, TableColumnView> tableColumns = targetTableContract.validate(model);
        Map<String, Object> row = normalize(model, data, true, tableColumns);
        requireUniqueValues(model, row, null);
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
        for (FormRelation relation : model.relations()) {
            if (relation.flatMode()) {
                upsertFlatSubRow(model, relation, id, data, true);
                continue;
            }
            List<Map<String, Object>> rows = subRows(data, relation);
            if (rows.isEmpty()) {
                continue;
            }
            FormModel subModel = requireSubModel(relation);
            Map<String, TableColumnView> subColumns = targetTableContract.validate(subModel);
            insertSubRows(relation, subModel, subColumns, id, rows);
        }
        List<FormField> defaulted = model.fields().stream()
                .filter(field -> field.persisted()
                        && field.subFormKey() == null
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
        interceptors.resolve(model.interceptor()).ifPresent(i -> i.afterCreate(model, id, data));
        return id;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(FormModel model, String id, Map<String, Object> data) {
        rejectListModeSubData(model, data);
        String expectedEtag = peekEtag(data);
        if (expectedEtag == null) {
            // 兼容未携带指纹的旧客户端：仅做存在性校验（无并发保护）
            getById(model, id);
        } else {
            requireUnchanged(model, id, expectedEtag);
        }
        interceptors.resolve(model.interceptor()).ifPresent(i -> i.beforeUpdate(model, id, data));
        Map<String, TableColumnView> tableColumns = targetTableContract.validate(model);
        Map<String, Object> row = normalize(model, data, false, tableColumns);
        if (row.isEmpty()) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_INVALID, "没有可更新的字段");
        }
        requireUniqueValues(model, row, id);
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
        // 主从子表差量更新：仅当请求显式携带子表键（key=subFormKey）时才比对子行集合——
        // 带 id 且属于本主单的既有子行更新、无 id 新增、既有但未提交的删除；
        // 子行 id 保持稳定，外部引用与逻辑删除历史不再因编辑主单而作废。
        // FLAT 一对一扁平：从表字段平铺在主提交里，按外键找既有从行做单条 upsert。
        for (FormRelation relation : model.relations()) {
            if (relation.flatMode()) {
                upsertFlatSubRow(model, relation, id, data, false);
                continue;
            }
            if (!data.containsKey(relation.subFormKey())) {
                continue;
            }
            FormModel subModel = requireSubModel(relation);
            Map<String, TableColumnView> subColumns = targetTableContract.validate(subModel);
            diffSubRows(subModel, relation, subColumns, id, subRows(data, relation), expectedEtag != null);
        }
        interceptors.resolve(model.interceptor()).ifPresent(i -> i.afterUpdate(model, id, data));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(FormModel model, String id) {
        Map<String, TableColumnView> tableColumns = targetTableContract.validate(model);
        getById(model, id);
        // 主从级联：CASCADE_DELETE 先清理子行（尊重子表逻辑删除语义），FORBID_DELETE 有子行即拒绝
        for (FormRelation relation : model.relations()) {
            FormModel subModel = requireSubModel(relation);
            if (hasChildRows(subModel, relation, id)) {
                if (FormConstants.CASCADE_DELETE.equals(relation.cascadeStrategy())) {
                    deleteSubRows(subModel, relation, id);
                } else {
                    throw new FormBusinessException(FormErrorCode.FORM_SUB_DELETE_FORBIDDEN, relation.subFormKey());
                }
            }
        }
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        interceptors.resolve(model.interceptor()).ifPresent(i -> i.beforeDelete(model, id));
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
            interceptors.resolve(model.interceptor()).ifPresent(i -> i.afterDelete(model, id));
            return;
        }
        if (dynamicTableMapper.deleteById(model.tableName(), activeRows(model), params) == 0) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_NOT_FOUND);
        }
        interceptors.resolve(model.interceptor()).ifPresent(i -> i.afterDelete(model, id));
    }

    @Override
    public List<Map<String, Object>> tree(FormModel model, String parentValue) {
        // FORM 来源：左树即关联树表单的数据，直接委派它的树端点（其权限由它自己的端点路径承担）
        if (model.tree() != null && model.tree().formSource()) {
            return tree(modelProvider.require(model.tree().treeFormKey()), parentValue);
        }
        // DICT 来源：层级数据由前端从字典构建，不走业务表树端点
        if (model.tree() != null && model.tree().dictSource()) {
            return List.of();
        }
        targetTableContract.validateAccess(model);
        if (model.tree() == null || !model.tree().selfRelation()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "该表单不是自关联树类型");
        }
        Map<String, Object> params = new HashMap<>();
        // parentValue 为 null 或空串都表示根节点（parent IS NULL），供前端懒加载首层
        String condition = StringUtils.hasText(parentValue) ? " = " + bind("p0", parentValue, params) : " IS NULL";
        String whereSql = activeRows(model) + " AND " + model.tree().parentColumn() + condition;
        List<Map<String, Object>> rows = dynamicTableMapper
                .selectList(
                        model.tableName(),
                        String.join(", ", selectColumns(model)),
                        whereSql,
                        " ORDER BY " + model.tree().nodeColumn() + " ASC, ID DESC",
                        params)
                .stream()
                .map(row -> project(model, row))
                .collect(Collectors.toList());
        // 树表格呈现需判断哪行可展开：按父列分组计数附加 __childCount（双下划线前缀，字段命名规则不会撞）
        if (!rows.isEmpty()) {
            String parentColumn = model.tree().parentColumn();
            List<String> rowIds = rows.stream()
                    .map(row -> String.valueOf(columnValue(row, FormConstants.COLUMN_ID)))
                    .toList();
            Map<String, Object> countParams = new HashMap<>();
            String countIn = rowIds.stream()
                    .map(id -> bind("c" + countParams.size(), id, countParams))
                    .collect(Collectors.joining(", "));
            Map<String, Long> countsByParent = new HashMap<>();
            dynamicTableMapper
                    .selectList(
                            model.tableName(),
                            parentColumn + ", COUNT(*) AS __CNT",
                            activeRows(model) + " AND " + parentColumn + " IN (" + countIn + ")",
                            " GROUP BY " + parentColumn,
                            countParams)
                    .forEach(row -> {
                        Object parent = columnValue(row, parentColumn);
                        Object count = columnValue(row, "__CNT");
                        if (parent != null && count != null) {
                            countsByParent.put(parent.toString(), Long.parseLong(String.valueOf(count)));
                        }
                    });
            rows.forEach(row -> {
                String id = String.valueOf(columnValue(row, FormConstants.COLUMN_ID));
                row.put("__childCount", countsByParent.getOrDefault(id, 0L));
            });
        }
        return rows;
    }

    /**
     * 解析关系子表单模型：须存在、已发布且表在允许清单（require 校验）、外键已映射、自身为单表类型
     */
    private FormModel requireSubModel(FormRelation relation) {
        FormModel subModel = modelProvider.require(relation.subFormKey());
        if (!FormConstants.FORM_TYPE_SINGLE.equals(subModel.formType())) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "子表单须为单表类型：" + relation.subFormKey());
        }
        if (subModel.fields().stream().noneMatch(field -> field.column().equals(relation.foreignKey()))) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "子表外键未映射：" + relation.foreignKey());
        }
        return subModel;
    }

    /** 按子表单标识定位主从关系；不存在即非法参数 */
    private FormRelation requireRelation(FormModel model, String subFormKey) {
        return model.relations().stream()
                .filter(relation -> relation.subFormKey().equals(subFormKey))
                .findFirst()
                .orElseThrow(() -> new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "主从关系不存在：" + subFormKey));
    }

    /** 台账式子行独立增删改、扁平式从字段平铺提交：显式携带子表键即拒绝 */
    private void rejectListModeSubData(FormModel model, Map<String, Object> data) {
        for (FormRelation relation : model.relations()) {
            if (relation.listMode() && data.containsKey(relation.subFormKey())) {
                throw new FormBusinessException(
                        FormErrorCode.INVALID_PARAMETER, "台账式子表数据不随主表提交：" + relation.subFormKey());
            }
            if (relation.flatMode() && data.containsKey(relation.subFormKey())) {
                throw new FormBusinessException(
                        FormErrorCode.INVALID_PARAMETER, "扁平模式从表字段随主表平铺提交，不接受子表键：" + relation.subFormKey());
            }
        }
    }

    /**
     * 一对一扁平从行单条 upsert：从表字段平铺在主提交里，按外键找既有从行——
     * 存在则部分更新（仅提交的字段）、不存在则插入（外键回填主行 id）；
     * 未提交任何从表字段即不触碰；历史多行按外键取最新一条。
     */
    private void upsertFlatSubRow(
            FormModel model, FormRelation relation, String mainId, Map<String, Object> data, boolean create) {
        Map<String, Object> subData = new LinkedHashMap<>();
        for (FormField field : model.fields()) {
            if (relation.subFormKey().equals(field.subFormKey()) && data.containsKey(field.name())) {
                subData.put(field.name(), data.get(field.name()));
            }
        }
        if (subData.isEmpty()) {
            return;
        }
        FormModel subModel = requireSubModel(relation);
        Map<String, TableColumnView> subColumns = targetTableContract.validate(subModel);
        List<Map<String, Object>> existing = dynamicTableMapper.selectByFk(
                subModel.tableName(),
                FormConstants.COLUMN_ID,
                relation.foreignKey(),
                List.of(mainId),
                activeRows(subModel),
                " ORDER BY ID DESC");
        if (existing.isEmpty()) {
            insertSubRows(relation, subModel, subColumns, mainId, List.of(subData));
            return;
        }
        Map<String, Object> row = normalize(subModel, subData, false, subColumns);
        if (row.isEmpty()) {
            return;
        }
        String subId = String.valueOf(columnValue(existing.getFirst(), FormConstants.COLUMN_ID));
        Map<String, Object> params = new HashMap<>();
        params.put("id", subId);
        List<String> assignments = new ArrayList<>();
        row.forEach((column, value) -> assignments.add(column + " = " + bind("p" + params.size(), value, params)));
        if (subColumns.containsKey("UPDATED_BY")) {
            assignments.add("UPDATED_BY = " + bind("p" + params.size(), AuthUtils.getSafeUsername(), params));
        }
        if (subColumns.containsKey("UPDATED_AT")) {
            assignments.add("UPDATED_AT = " + bind("p" + params.size(), LocalDateTime.now(), params));
        }
        if (dynamicTableMapper.updateDynamic(
                        subModel.tableName(), String.join(", ", assignments), activeRows(subModel), params)
                == 0) {
            // 从行在预读后被并发删除：整笔事务回滚，不静默丢失本次更新
            throw new FormBusinessException(FormErrorCode.FORM_DATA_NOT_FOUND);
        }
    }

    @Override
    public PageView<Map<String, Object>> pageSubRows(
            FormModel model, String id, String subFormKey, long pageNum, long pageSize) {
        FormRelation relation = requireRelation(model, subFormKey);
        FormModel subModel = requireSubModel(relation);
        targetTableContract.validateAccess(subModel);
        Map<String, Object> params = new HashMap<>();
        params.put("fkValue", id);
        String whereSql = activeRows(subModel) + " AND " + relation.foreignKey() + " = #{params.fkValue}";
        long page = Math.max(1, pageNum);
        long size = Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));
        long total = dynamicTableMapper.countBy(subModel.tableName(), whereSql, params);
        if (total == 0) {
            return PageView.<Map<String, Object>>builder()
                    .pageNum(page)
                    .pageSize(size)
                    .total(0)
                    .totalPages(0)
                    .records(List.of())
                    .build();
        }
        List<Map<String, Object>> records = dynamicTableMapper
                .selectList(
                        subModel.tableName(),
                        String.join(", ", selectColumns(subModel)),
                        whereSql,
                        " ORDER BY ID ASC LIMIT " + size + " OFFSET " + ((page - 1) * size),
                        params)
                .stream()
                .map(row -> project(subModel, row))
                .toList();
        return PageView.<Map<String, Object>>builder()
                .pageNum(page)
                .pageSize(size)
                .total(total)
                .totalPages((total + size - 1) / size)
                .records(records)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createSubRow(FormModel model, String id, String subFormKey, Map<String, Object> data) {
        FormRelation relation = requireRelation(model, subFormKey);
        FormModel subModel = requireSubModel(relation);
        // 从主表详情入口写子表数据：主表新增权限由端点路径校验，子表数据写权限在此显式校验
        AuthUtils.checkPermission(FormAction.ADD.permission(subFormKey));
        getById(model, id);
        Map<String, TableColumnView> subColumns = targetTableContract.validate(subModel);
        insertSubRows(relation, subModel, subColumns, id, List.of(data));
    }

    /**
     * 子表是否存在引用记录（外键列已由 {@link #requireSubModel} 校验）
     */
    private boolean hasChildRows(FormModel subModel, FormRelation relation, String id) {
        Map<String, Object> params = new HashMap<>();
        params.put("value", id);
        return dynamicTableMapper.countBy(
                        subModel.tableName(),
                        activeRows(subModel) + " AND " + relation.foreignKey() + " = #{params.value}",
                        params)
                > 0;
    }

    /** 提交数据中该关系的子行集合（key=subFormKey；非数组即非法参数，子行 id 不被信任） */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> subRows(Map<String, Object> data, FormRelation relation) {
        Object raw = data.get(relation.subFormKey());
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "子表数据须为数组：" + relation.subFormKey());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map)) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "子行须为对象：" + relation.subFormKey());
            }
            rows.add((Map<String, Object>) item);
        }
        return rows;
    }

    /**
     * INLINE 子表差量更新：提交行带 id 且命中本主单既有活跃子行 → 更新；无 id → 新增；
     * 既有但未提交 → 删除（尊重子表逻辑删除语义）。子行 id 仅在属于本主单时才被信任。
     * 防线：提交空数组（清空全部子行）仅在携带行指纹（__etag，证明提交者看到了当前数据）
     * 或本就无子行时放行，避免旧客户端/误提交静默清空。
     */
    private void diffSubRows(
            FormModel subModel,
            FormRelation relation,
            Map<String, TableColumnView> subColumns,
            String mainId,
            List<Map<String, Object>> rows,
            boolean etagPresent) {
        Map<String, Map<String, Object>> existing = activeSubRowsById(subModel, relation, mainId);
        if (rows.isEmpty()) {
            if (!existing.isEmpty() && !etagPresent) {
                throw new FormBusinessException(FormErrorCode.FORM_DATA_INVALID, "清空全部子行需携带行指纹（__etag）");
            }
            existing.keySet().forEach(subRowId -> deleteSubRow(subModel, subColumns, subRowId));
            return;
        }
        Set<String> submittedIds = new HashSet<>();
        for (Map<String, Object> item : rows) {
            Object subId = item.get("id");
            if (subId == null || subId.toString().isBlank()) {
                insertSubRows(relation, subModel, subColumns, mainId, List.of(item));
                continue;
            }
            String subRowId = subId.toString();
            if (!existing.containsKey(subRowId)) {
                throw new FormBusinessException(FormErrorCode.FORM_DATA_INVALID, "子行不存在或不属于该主单：" + subRowId);
            }
            submittedIds.add(subRowId);
            updateSubRow(subModel, relation, subColumns, subRowId, item);
        }
        for (String staleId : existing.keySet()) {
            if (!submittedIds.contains(staleId)) {
                deleteSubRow(subModel, subColumns, staleId);
            }
        }
    }

    /** 本主单的既有活跃子行（key=子行 id；两段式读组装的同一口径） */
    private Map<String, Map<String, Object>> activeSubRowsById(
            FormModel subModel, FormRelation relation, String mainId) {
        List<Map<String, Object>> raw = dynamicTableMapper.selectByFk(
                subModel.tableName(),
                String.join(", ", selectColumns(subModel)),
                relation.foreignKey(),
                List.of(mainId),
                activeRows(subModel),
                null);
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        raw.forEach(row -> byId.put(String.valueOf(project(subModel, row).get("id")), row));
        return byId;
    }

    /** 单条子行部分更新：外键列不可改（剥离后由服务端关系保持绑定），无字段变化则不触碰 */
    private void updateSubRow(
            FormModel subModel,
            FormRelation relation,
            Map<String, TableColumnView> subColumns,
            String subRowId,
            Map<String, Object> item) {
        Map<String, Object> row = normalize(subModel, item, false, subColumns);
        row.keySet().removeIf(column -> column.equalsIgnoreCase(relation.foreignKey()));
        if (row.isEmpty()) {
            return;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("id", subRowId);
        List<String> assignments = new ArrayList<>();
        row.forEach((column, value) -> assignments.add(column + " = " + bind("p" + params.size(), value, params)));
        if (subColumns.containsKey("UPDATED_BY")) {
            assignments.add("UPDATED_BY = " + bind("p" + params.size(), AuthUtils.getSafeUsername(), params));
        }
        if (subColumns.containsKey("UPDATED_AT")) {
            assignments.add("UPDATED_AT = " + bind("p" + params.size(), LocalDateTime.now(), params));
        }
        if (dynamicTableMapper.updateDynamic(
                        subModel.tableName(), String.join(", ", assignments), activeRows(subModel), params)
                == 0) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_NOT_FOUND);
        }
    }

    /** 删除单条子行：子表有逻辑删除列时置删除标记，否则物理删除 */
    private void deleteSubRow(FormModel subModel, Map<String, TableColumnView> subColumns, String subRowId) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", subRowId);
        if (StringUtils.hasText(subModel.logicDeleteColumn())) {
            List<String> assignments = new ArrayList<>();
            assignments.add(subModel.logicDeleteColumn() + " = 1");
            if (subColumns.containsKey("UPDATED_BY")) {
                assignments.add("UPDATED_BY = " + bind("p" + params.size(), AuthUtils.getSafeUsername(), params));
            }
            if (subColumns.containsKey("UPDATED_AT")) {
                assignments.add("UPDATED_AT = " + bind("p" + params.size(), LocalDateTime.now(), params));
            }
            if (dynamicTableMapper.updateDynamic(
                            subModel.tableName(), String.join(", ", assignments), activeRows(subModel), params)
                    == 0) {
                throw new FormBusinessException(FormErrorCode.FORM_DATA_NOT_FOUND);
            }
            return;
        }
        if (dynamicTableMapper.deleteById(subModel.tableName(), activeRows(subModel), params) == 0) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_NOT_FOUND);
        }
    }

    /** 逐行写入子表：外键回填主表 id，复用子模型校验、逻辑删除初始化与审计填充（租户由插件承担） */
    private void insertSubRows(
            FormRelation relation,
            FormModel subModel,
            Map<String, TableColumnView> subColumns,
            String mainId,
            List<Map<String, Object>> rows) {
        for (Map<String, Object> item : rows) {
            Map<String, Object> row = normalize(subModel, item, true, subColumns);
            // 外键由服务端绑定为主行 id：先剥离客户端提交的外键列，避免与绑定列重复（也不被伪造）
            row.keySet().removeIf(column -> column.equalsIgnoreCase(relation.foreignKey()));
            List<String> columns = new ArrayList<>();
            List<String> values = new ArrayList<>();
            Map<String, Object> params = new HashMap<>();
            columns.add(FormConstants.COLUMN_ID);
            values.add(bind("id", IdWorker.getIdStr(), params));
            row.forEach((column, value) -> {
                columns.add(column);
                values.add(bind("p" + params.size(), value, params));
            });
            if (StringUtils.hasText(subModel.logicDeleteColumn())) {
                columns.add(subModel.logicDeleteColumn());
                values.add(bind("p" + params.size(), 0, params));
            }
            columns.add(relation.foreignKey());
            values.add(bind("fk", mainId, params));
            fillAudit(subColumns, columns, values, params, true);
            dynamicTableMapper.insertDynamic(subModel.tableName(), columns, values, params);
        }
    }

    /** 清理子行：子表有逻辑删除列时置删除标记，否则物理删除 */
    private void deleteSubRows(FormModel subModel, FormRelation relation, String mainId) {
        Map<String, Object> params = new HashMap<>();
        params.put("value", mainId);
        if (StringUtils.hasText(subModel.logicDeleteColumn())) {
            dynamicTableMapper.updateByFk(
                    subModel.tableName(),
                    subModel.logicDeleteColumn() + " = 1",
                    relation.foreignKey(),
                    activeRows(subModel),
                    params);
        } else {
            dynamicTableMapper.deleteByFk(subModel.tableName(), relation.foreignKey(), activeRows(subModel), params);
        }
    }

    /**
     * 两段式读组装：主行 id 集合按外键 in 查回子行，投影后挂到主行（key=subFormKey）。
     * 台账式关系不分页不嵌套（载荷有界），仅置空占位；子表单不可用（未发布/未映射）时跳过该关系并置空，
     * 均不阻断主表读取。
     */
    private void attachSubRows(FormModel model, List<Map<String, Object>> rows) {
        if (model.relations().isEmpty() || rows.isEmpty()) {
            return;
        }
        for (FormRelation relation : model.relations()) {
            if (relation.listMode()) {
                rows.forEach(row -> row.put(relation.subFormKey(), List.of()));
                continue;
            }
            FormModel subModel;
            try {
                subModel = requireSubModel(relation);
            } catch (FormBusinessException error) {
                log.warn("主从子表单不可用，读取跳过该关系：{} - {}", relation.subFormKey(), error.getMessage());
                rows.forEach(row -> row.put(relation.subFormKey(), List.of()));
                continue;
            }
            List<String> ids =
                    rows.stream().map(row -> String.valueOf(row.get("id"))).toList();
            List<Map<String, Object>> subRows = dynamicTableMapper.selectByFk(
                    subModel.tableName(),
                    String.join(", ", selectColumns(subModel)),
                    relation.foreignKey(),
                    ids,
                    activeRows(subModel),
                    " ORDER BY ID ASC");
            Map<String, List<Map<String, Object>>> grouped = new HashMap<>();
            for (Map<String, Object> subRow : subRows) {
                Object fk = columnValue(subRow, relation.foreignKey());
                if (fk == null) {
                    continue;
                }
                grouped.computeIfAbsent(fk.toString(), key -> new ArrayList<>()).add(project(subModel, subRow));
            }
            if (relation.flatMode()) {
                // FLAT 一对一：从行字段提升到主行顶层（id 不覆盖主行标识；历史多行取最新一条）
                for (Map<String, Object> row : rows) {
                    List<Map<String, Object>> subs = grouped.get(String.valueOf(row.get("id")));
                    if (subs == null || subs.isEmpty()) {
                        continue;
                    }
                    Map<String, Object> latest = subs.getLast();
                    for (Map.Entry<String, Object> entry : latest.entrySet()) {
                        if (!"id".equals(entry.getKey())) {
                            row.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
                continue;
            }
            for (Map<String, Object> row : rows) {
                row.put(relation.subFormKey(), grouped.getOrDefault(String.valueOf(row.get("id")), List.of()));
            }
        }
    }

    /** 原始行键大小写随驱动漂移，按列名大小写不敏感取值 */
    private Object columnValue(Map<String, Object> row, String column) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().toUpperCase(Locale.ROOT).equals(column)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 目标表列清单（白名单：ID + 已映射字段）
     */
    private List<String> selectColumns(FormModel model) {
        // 字段可能映射主键列（不落库展示），与引擎的 ID 去重避免 SELECT 同列两次；
        // FLAT 从表字段的列在子表上，主表查询不带
        Set<String> columns = new LinkedHashSet<>();
        columns.add(FormConstants.COLUMN_ID);
        model.fields().forEach(field -> {
            if (field.subFormKey() == null) {
                columns.add(field.column());
            }
        });
        return new ArrayList<>(columns);
    }

    /**
     * 入参归一：仅接受字段标识；新增补默认值，更新时未提交字段保持原值、显式 null 表示清空。
     */
    private Map<String, Object> normalize(
            FormModel model, Map<String, Object> data, boolean create, Map<String, TableColumnView> columns) {
        Map<String, Object> source = data == null ? Map.of() : data;
        Map<String, Object> row = new LinkedHashMap<>();
        for (FormField field : model.fields()) {
            // 不落库展示字段（可映射主键/审计列）与 FLAT 从表字段都不参与本表写入，先于系统列守卫放行
            if (!field.persisted()
                    || field.subFormKey() != null
                    || field.column().equalsIgnoreCase(model.logicDeleteColumn())) {
                continue;
            }
            if (FormConstants.isSystemColumn(field.column()) || "id".equalsIgnoreCase(field.name())) {
                throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "禁止映射系统字段：" + field.column());
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
        // FLAT 从表字段由 attachSubRows 从子表读投影合并，本表投影跳过
        model.fields().forEach(field -> {
            if (field.subFormKey() == null) {
                result.put(field.name(), columns.get(field.column()));
            }
        });
        return result;
    }

    /**
     * 唯一约束（rules.unique=true）：写入前查重。仅主表落库字段（FLAT 从字段跨表不支持，子表行暂不支持）；
     * 更新时排除自身行；已逻辑删除的行不占用值。租户条件由插件追加，唯一性天然限定在租户内。
     */
    private void requireUniqueValues(FormModel model, Map<String, Object> row, String currentId) {
        for (FormField field : model.fields()) {
            if (field.subFormKey() != null
                    || !Boolean.TRUE.equals(field.form().rules().get("unique"))
                    || !row.containsKey(field.column())
                    || row.get(field.column()) == null) {
                continue;
            }
            Map<String, Object> params = new HashMap<>();
            String where = activeRows(model) + " AND " + field.column() + " = "
                    + bind("p" + params.size(), row.get(field.column()), params);
            if (currentId != null) {
                params.put("id", currentId);
                where += " AND ID != #{params.id}";
            }
            if (dynamicTableMapper.countBy(model.tableName(), where, params) > 0) {
                throw new FormBusinessException(FormErrorCode.FORM_DATA_INVALID, field.name() + " 值已存在，须唯一");
            }
        }
    }

    /** 从提交体读取行指纹（保留键不参与字段归一，不改动调用方 Map） */
    private String peekEtag(Map<String, Object> data) {
        Object etag = data.get(ETAG_KEY);
        return etag == null || etag.toString().isBlank() ? null : etag.toString();
    }

    /**
     * 乐观锁：行锁预读后比对行指纹（投影口径与读取路径一致，覆盖主行与 INLINE 子行），
     * 不一致说明读取后已被他人改过。行锁仅持有到本事务提交，串行化同一行的并发更新。
     */
    private void requireUnchanged(FormModel model, String id, String expectedEtag) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        Map<String, Object> raw = dynamicTableMapper.selectForUpdate(
                model.tableName(), String.join(", ", selectColumns(model)), activeRows(model), params);
        if (raw == null) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_NOT_FOUND);
        }
        Map<String, Object> projected = project(model, raw);
        attachSubRows(model, new ArrayList<>(List.of(projected)));
        if (!etagOf(projected).equals(expectedEtag)) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_CONFLICT);
        }
    }

    /** 行指纹：整行规范化 JSON 的 MD5（读取端随 __etag 下发，更新端回传比对） */
    private String etagOf(Map<String, Object> row) {
        return DigestUtils.md5DigestAsHex(
                objectMapper.writeValueAsString(canonicalize(row)).getBytes(StandardCharsets.UTF_8));
    }

    /** 规范化：Map 按键排序；元素全部带 id 的列表（INLINE 子行）按 id 排序，其余列表保序 */
    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> {
                if (!ETAG_KEY.equals(key)) {
                    sorted.put(String.valueOf(key), canonicalize(item));
                }
            });
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> items =
                    new ArrayList<>(list.stream().map(this::canonicalize).toList());
            boolean keyedSubRows = !items.isEmpty()
                    && items.stream().allMatch(item -> item instanceof Map<?, ?> itemMap && itemMap.containsKey("id"));
            if (keyedSubRows) {
                items.sort(Comparator.comparing(item -> String.valueOf(((Map<?, ?>) item).get("id"))));
            }
            return items;
        }
        return value;
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
     * （每层一条参数化 in 查询），对主键 ID in 过滤；子孙集上限
     * lambda.fusion.form.max-tree-descendants，超限抛 FORM_TREE_DESCENDANT_LIMIT。
     * 环状数据由上限兜底终止（无环时集合收敛即停）。
     */
    private String buildTreeFilter(
            FormModel model, FormDataPageQuery.TreeNodeFilter treeNode, Map<String, Object> params) {
        if (treeNode == null || treeNode.value() == null) {
            return "";
        }
        if (model.tree() == null) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "该表单未配置树");
        }
        // SELF/FORM 由引擎解析子树；DICT 层级在字典侧，前端随请求携带子树代码集，引擎只做 IN 过滤
        if (model.tree().dictSource()) {
            return buildDictTreeFilter(model, treeNode, params);
        }
        if (model.tree().formSource()) {
            return buildFormTreeFilter(model, treeNode.value(), params);
        }
        return buildSelfTreeFilter(model, treeNode.value(), params);
    }

    /** SELF 自关联：在业务表上按父列逐层收集「自身 + 全部子孙」的主键，按主键 IN 过滤 */
    private String buildSelfTreeFilter(FormModel model, String nodeValue, Map<String, Object> params) {
        Set<String> ids = resolveSubtreeIds(model, nodeValue, params);
        String inSql = ids.stream().map(id -> bind(nextKey(params), id, params)).collect(Collectors.joining(", "));
        return " AND " + FormConstants.COLUMN_ID + " IN (" + inSql + ")";
    }

    /**
     * FORM 关联树表单：先在树表单的表上解析「节点 + 子孙」的节点 id 集（其父子结构由它自己的树配置决定），
     * 再按本表的节点字段 IN 过滤；上限与 SELF 同口径
     */
    private String buildFormTreeFilter(FormModel model, String nodeValue, Map<String, Object> params) {
        TreeConfig tree = model.tree();
        FormModel treeModel = modelProvider.require(tree.treeFormKey());
        if (treeModel.tree() == null || !treeModel.tree().selfRelation()) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "关联树表单须为自关联树类型：" + tree.treeFormKey());
        }
        Set<String> nodeIds = resolveSubtreeIds(treeModel, nodeValue, params);
        String nodeIdColumn = requireNodeIdColumn(model);
        String inSql =
                nodeIds.stream().map(id -> bind(nextKey(params), id, params)).collect(Collectors.joining(", "));
        return " AND " + nodeIdColumn + " IN (" + inSql + ")";
    }

    /** DICT 字典树：子树代码集由前端从已载入的字典层级计算，引擎校验数量后按节点字段 IN 过滤 */
    private String buildDictTreeFilter(
            FormModel model, FormDataPageQuery.TreeNodeFilter treeNode, Map<String, Object> params) {
        List<String> codes = treeNode.values();
        if (codes == null || codes.isEmpty()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "字典树过滤缺少子树代码集");
        }
        if (codes.size() > formProperties.getMaxTreeDescendants()) {
            throw new FormBusinessException(FormErrorCode.FORM_TREE_DESCENDANT_LIMIT);
        }
        String nodeIdColumn = requireNodeIdColumn(model);
        String inSql =
                codes.stream().map(code -> bind(nextKey(params), code, params)).collect(Collectors.joining(", "));
        return " AND " + nodeIdColumn + " IN (" + inSql + ")";
    }

    /** 本表存储树节点 id 的列（FORM/DICT 来源），按节点字段标识解析 */
    private String requireNodeIdColumn(FormModel model) {
        return model.fields().stream()
                .filter(field -> field.name().equals(model.tree().nodeIdField()))
                .findFirst()
                .orElseThrow(() -> new FormBusinessException(
                        FormErrorCode.FORM_TABLE_INVALID,
                        "树节点字段未映射：" + model.tree().nodeIdField()))
                .column();
    }

    /** 在树模型（自关联）上解析「节点 + 全部子孙」的主键集合，逐层参数化 in 查询，超限即拒绝 */
    private Set<String> resolveSubtreeIds(FormModel treeModel, String nodeValue, Map<String, Object> params) {
        Set<String> ids = new HashSet<>();
        List<String> frontier = List.of(nodeValue);
        int max = formProperties.getMaxTreeDescendants();
        while (!frontier.isEmpty()) {
            if (ids.size() + frontier.size() > max) {
                throw new FormBusinessException(FormErrorCode.FORM_TREE_DESCENDANT_LIMIT);
            }
            String inSql = frontier.stream()
                    .map(value -> bind(nextKey(params), value, params))
                    .collect(Collectors.joining(", "));
            List<Map<String, Object>> children = dynamicTableMapper.selectList(
                    treeModel.tableName(),
                    FormConstants.COLUMN_ID + ", " + treeModel.tree().parentColumn(),
                    activeRows(treeModel) + " AND " + treeModel.tree().parentColumn() + " IN (" + inSql + ")",
                    null,
                    params);
            // 原始行键大小写随驱动漂移，与 project() 同样归一后再取 ID
            frontier = children.stream()
                    .map(row -> columnValue(row, FormConstants.COLUMN_ID))
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .filter(ids::add)
                    .collect(Collectors.toList());
        }
        ids.add(nodeValue);
        return ids;
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
