package com.lambda.fusion.form.runtime.service.impl;

import cn.hutool.poi.excel.ExcelUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.FormProperties;
import com.lambda.fusion.form.design.model.TableColumnView;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.biz.BizDataProvider;
import com.lambda.fusion.form.runtime.integration.FormDataInterceptors;
import com.lambda.fusion.form.runtime.mapper.DynamicTableMapper;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import com.lambda.fusion.form.runtime.model.FormDataPageView;
import com.lambda.fusion.form.runtime.service.DynamicDataService;
import com.lambda.fusion.form.runtime.support.Csv;
import com.lambda.fusion.form.runtime.support.FormFieldValidator;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModel.FormField;
import com.lambda.fusion.form.runtime.support.FormModel.FormRelation;
import com.lambda.fusion.form.runtime.support.FormModel.TreeConfig;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.form.runtime.support.QueryAstValidator;
import com.lambda.fusion.form.runtime.support.QueryAstValidator.ResolvedCondition;
import com.lambda.fusion.form.runtime.support.QueryAstValidator.ResolvedSort;
import com.lambda.fusion.form.runtime.support.SubTableConfigs;
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
import org.springframework.beans.factory.ObjectProvider;
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

    /** 上级节点展示名随行下发的保留键（SELF/FORM 树；双下划线前缀，字段命名规则不会撞，同 __childCount） */
    private static final String TREE_PARENT_LABEL_KEY = "__treeParentLabel";

    /** 系统主数据（ORG/USER）字段的名称随行下发前缀：__bizLabel_{字段标识} */
    private static final String BIZ_LABEL_PREFIX = "__bizLabel_";

    /** 关联字段（LOOKUP）回显名随行下发前缀 */
    private static final String LOOKUP_LABEL_PREFIX = "__lookupLabel_";

    private final DynamicTableMapper dynamicTableMapper;
    private final QueryAstValidator astValidator;
    private final FormModelProvider modelProvider;
    private final TargetTableContract targetTableContract;
    private final FormProperties formProperties;
    private final FormDataInterceptors interceptors;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<BizDataProvider> bizDataProviders;

    @Override
    public FormDataPageView page(FormModel model, FormDataPageQuery query) {
        Map<String, TableColumnView> tableColumns = targetTableContract.validateAccess(model);
        astValidator.validate(model, query);
        String selectColumns = String.join(", ", selectColumns(model));
        Map<String, Object> params = new HashMap<>();
        String whereSql = activeRows(model)
                + buildWhere(astValidator.resolve(model, query.getConditions()), params)
                + buildTreeFilter(model, query.getTreeNode(), params)
                + dataScopeFilter(model);
        String orderSql = buildOrder(model, astValidator.resolveSorts(model, query.getSorts()), tableColumns);
        Map<String, Object> summaries = summaries(model, whereSql, params);

        long pageNum = currentPage(query);
        long pageSize = pageSize(query);
        long total = dynamicTableMapper.countBy(model.tableName(), whereSql, params);
        if (total == 0) {
            return FormDataPageView.builder()
                    .pageNum(pageNum)
                    .pageSize(pageSize)
                    .total(0)
                    .totalPages(0)
                    .records(List.of())
                    .summaries(summaries)
                    .build();
        }
        String pageSql = orderSql + " LIMIT " + pageSize + " OFFSET " + ((pageNum - 1) * pageSize);
        List<Map<String, Object>> records =
                dynamicTableMapper.selectList(model.tableName(), selectColumns, whereSql, pageSql, params);
        List<Map<String, Object>> projected =
                records.stream().map(row -> project(model, row)).toList();
        // 列表只挂 FLAT 一对一从字段（每主行至多一条，载荷有界）；INLINE 子行与行指纹不做整页装配——
        // 「页大小 × maxInlineSubRows」乘积放大与逐行规范化序列化的成本只服务于编辑，而编辑协议
        // 是打开弹窗时按 id 取详情，列表行不参与指纹回传
        attachSubRows(model, projected, false);
        attachTreeParentLabels(model, projected);
        attachBizLabels(model, projected);
        attachLookupLabels(model, projected);
        stripRoleHidden(model, projected);
        return FormDataPageView.builder()
                .pageNum(pageNum)
                .pageSize(pageSize)
                .total(total)
                .totalPages((total + pageSize - 1) / pageSize)
                .records(projected)
                .summaries(summaries)
                .build();
    }

    /**
     * 导出当前筛选全集为 CSV（UTF-8 BOM，Excel 直开）：复用 page 的条件/排序/树过滤，
     * 绕过深分页钳制按 500 行分批拉取；列 = 列表可见的主表字段（label 表头），
     * BOOL 转是否、日期时间友好格式、其余原值；总行数超 max-export-rows 先拒绝（不写半个文件）。
     */
    @Override
    public void export(FormModel model, FormDataPageQuery query, String format, java.io.OutputStream out)
            throws java.io.IOException {
        Map<String, TableColumnView> tableColumns = targetTableContract.validateAccess(model);
        astValidator.validate(model, query);
        String selectColumns = String.join(", ", selectColumns(model));
        Map<String, Object> params = new HashMap<>();
        String whereSql = activeRows(model)
                + buildWhere(astValidator.resolve(model, query.getConditions()), params)
                + buildTreeFilter(model, query.getTreeNode(), params)
                + dataScopeFilter(model);
        long total = dynamicTableMapper.countBy(model.tableName(), whereSql, params);
        if (total > formProperties.getMaxExportRows()) {
            throw new FormBusinessException(
                    FormErrorCode.FORM_EXPORT_LIMIT, String.valueOf(formProperties.getMaxExportRows()));
        }
        String orderSql = buildOrder(model, astValidator.resolveSorts(model, query.getSorts()), tableColumns);
        List<FormField> columns = model.fields().stream()
                .filter(field -> field.subFormKey() == null && field.list().show())
                .toList();
        String parentFieldName = treeParentFieldName(model);
        java.util.function.Function<Map<String, Object>, List<Object>> toRowValues = row -> columns.stream()
                .map(field -> formatExportValue(field, exportCellValue(parentFieldName, field, row)))
                .toList();
        try {
            if (FormConstants.EXPORT_FORMAT_XLSX.equals(format)) {
                try (cn.hutool.poi.excel.ExcelWriter writer = ExcelUtil.getBigWriter()) {
                    writer.writeHeadRow(columns.stream().map(FormField::label).toList());
                    forEachExportPage(
                            model,
                            selectColumns,
                            whereSql,
                            orderSql,
                            params,
                            rows -> rows.forEach(row -> writer.writeRow(toRowValues.apply(row))));
                    writer.flush(out);
                }
            } else {
                java.io.Writer writer = new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8);
                writer.write((char) 0xFEFF);
                writer.write(
                        columns.stream().map(field -> csvCell(field.label())).collect(Collectors.joining(",")));
                writer.write("\n");
                forEachExportPage(model, selectColumns, whereSql, orderSql, params, rows -> {
                    for (Map<String, Object> row : rows) {
                        try {
                            writer.write(toRowValues.apply(row).stream()
                                    .map(this::csvCell)
                                    .collect(Collectors.joining(",")));
                            writer.write("\n");
                        } catch (java.io.IOException error) {
                            throw new java.io.UncheckedIOException(error);
                        }
                    }
                });
                writer.flush();
            }
        } catch (java.io.UncheckedIOException error) {
            throw error.getCause();
        }
    }

    /** 导出分页拉取（每页 500，短页终止）：行投影 + 树父/业务标签装配后交消费方 */
    private void forEachExportPage(
            FormModel model,
            String selectColumns,
            String whereSql,
            String orderSql,
            Map<String, Object> params,
            java.util.function.Consumer<List<Map<String, Object>>> consumer) {
        long offset = 0;
        while (true) {
            String pageSql = orderSql + " LIMIT 500 OFFSET " + offset;
            List<Map<String, Object>> records =
                    dynamicTableMapper.selectList(model.tableName(), selectColumns, whereSql, pageSql, params);
            if (records.isEmpty()) {
                break;
            }
            List<Map<String, Object>> rows =
                    records.stream().map(row -> project(model, row)).toList();
            attachTreeParentLabels(model, rows);
            attachBizLabels(model, rows);
            attachLookupLabels(model, rows);
            stripRoleHidden(model, rows);
            consumer.accept(rows);
            offset += records.size();
            if (records.size() < 500) {
                break;
            }
        }
    }

    /**
     * CSV 导入（事务内整批成功或整体回滚）：表头 = 字段标识或唯一列标签（重复标签歧义即拒绝），
     * 数据行复用 {@link #create} 的全套校验（类型/必填/唯一/树父/拦截器），BOOL 兼容「是/否」，
     * 其余值按存储原值写入（字典/机构/用户给存储值而非展示名）。行级失败以「第 N 行」包装回传。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int importCsv(FormModel model, java.io.Reader reader) {
        targetTableContract.validate(model);
        return importRows(model, Csv.parse(reader));
    }

    /** Excel 导入：单元格数值/日期按展示口径转文本原值后，走与 CSV 完全一致的行导入管线 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int importExcel(FormModel model, java.io.InputStream inputStream) {
        return importRows(model, parseExcel(inputStream));
    }

    /** 行导入管线：表头映射、行数上限、逐行 create（失败包「第 N 行」），事务由公共入口开启 */
    private int importRows(FormModel model, List<List<String>> rows) {
        if (rows.isEmpty()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "导入文件为空");
        }
        List<String> headers = rows.get(0).stream().map(String::trim).toList();
        List<FormField> columns = mapImportHeaders(model, headers);
        int dataRows = rows.size() - 1;
        if (dataRows == 0) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "导入文件没有数据行");
        }
        if (dataRows > formProperties.getMaxImportRows()) {
            throw new FormBusinessException(
                    FormErrorCode.FORM_IMPORT_LIMIT, String.valueOf(formProperties.getMaxImportRows()));
        }
        int imported = 0;
        Map<String, Map<String, String>> lookupCache = new HashMap<>();
        for (int row = 1; row < rows.size(); row++) {
            List<String> cells = rows.get(row);
            if (cells.stream().allMatch(cell -> cell == null || cell.isBlank())) {
                continue;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            for (int column = 0; column < columns.size(); column++) {
                String cell = column < cells.size() ? cells.get(column) : null;
                if (cell == null || cell.isBlank()) {
                    continue;
                }
                data.put(columns.get(column).name(), importCellValue(columns.get(column), cell));
            }
            try {
                resolveLookupImportValues(model, data, lookupCache);
                create(model, data);
            } catch (FormBusinessException error) {
                throw new FormBusinessException(
                        FormErrorCode.FORM_ROW_INVALID, String.valueOf(row + 1), formatFormMessage(error));
            }
            imported++;
        }
        return imported;
    }

    /**
     * 导入时关联字段取值解析：值能命中目标行 id 则原样使用；否则按回显字段精确反查——
     * 唯一命中转为其行 id，多条命中报歧义（要求填行 id），无命中保留原值（由写入校验报不存在）。
     * 反查结果按字段+文本缓存（同值整批只查一次）。
     */
    private void resolveLookupImportValues(
            FormModel model, Map<String, Object> data, Map<String, Map<String, String>> lookupCache) {
        for (FormField field : model.fields()) {
            if (field.lookup() == null || !data.containsKey(field.name())) {
                continue;
            }
            Object raw = data.get(field.name());
            if (raw == null || raw.toString().isBlank()) {
                continue;
            }
            String text = raw.toString();
            Map<String, String> cache = lookupCache.computeIfAbsent(field.name(), key -> new LinkedHashMap<>());
            String resolved = cache.get(text);
            if (resolved == null) {
                resolved = resolveLookupReference(model, field, text);
                cache.put(text, resolved);
            }
            data.put(field.name(), resolved);
        }
    }

    /** 关联引用解析：值即行 id 原样返回；否则按回显字段精确匹配——唯一命中转 id，多条命中报歧义 */
    private String resolveLookupReference(FormModel model, FormField field, String text) {
        FormModel targetModel = modelProvider.require(field.lookup().targetFormKey());
        Map<String, Map<String, Object>> byId =
                findRows(targetModel, List.of(text), Set.of(field.lookup().displayField()));
        if (byId.containsKey(text)) {
            return text;
        }
        FormField displayField = targetModel.fields().stream()
                .filter(candidate -> candidate.name().equals(field.lookup().displayField()))
                .findFirst()
                .orElseThrow(() -> new FormBusinessException(FormErrorCode.FORM_DATA_INVALID, "关联回显字段不可用"));
        Map<String, Object> params = new HashMap<>();
        params.put("v", text);
        String where = activeRows(targetModel) + " AND " + displayField.column() + " = " + bind("v", text, params);
        List<Map<String, Object>> hits = dynamicTableMapper.selectList(
                targetModel.tableName(),
                FormConstants.COLUMN_ID + ", " + displayField.column(),
                where,
                " LIMIT 2",
                params);
        if (hits.size() > 1) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_INVALID, text + " 对应多条记录，请填写记录 id");
        }
        return hits.isEmpty()
                ? text
                : String.valueOf(project(targetModel, hits.get(0)).get("id"));
    }

    /** 表头映射：先按字段标识精确匹配，再按列标签（唯一时）；未知列与歧义标签直接拒绝 */
    private List<FormField> mapImportHeaders(FormModel model, List<String> headers) {
        Map<String, FormField> byName = new HashMap<>();
        Map<String, List<FormField>> byLabel = new LinkedHashMap<>();
        for (FormField field : model.fields()) {
            if (field.subFormKey() != null
                    || !field.persisted()
                    || field.column().equals(model.logicDeleteColumn())) {
                continue;
            }
            byName.put(field.name(), field);
            byLabel.computeIfAbsent(field.label(), key -> new ArrayList<>()).add(field);
        }
        List<FormField> mapped = new ArrayList<>();
        for (String header : headers) {
            if (header == null || header.isBlank()) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "导入表头存在空列");
            }
            FormField field = byName.get(header);
            if (field == null) {
                List<FormField> byLabelFields = byLabel.get(header);
                if (byLabelFields == null) {
                    throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "导入表头存在未知列：" + header);
                }
                if (byLabelFields.size() > 1) {
                    throw new FormBusinessException(
                            FormErrorCode.INVALID_PARAMETER, "导入表头存在歧义列标签：" + header + "，请使用字段标识");
                }
                field = byLabelFields.get(0);
            }
            if (mapped.contains(field)) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "导入表头存在重复列：" + header);
            }
            mapped.add(field);
        }
        return mapped;
    }

    /** 导入取值：BOOL 兼容「是/否」，其余按原值交由 create 的类型校验 */
    private String importCellValue(FormField field, String cell) {
        if ("BOOL".equals(field.type())) {
            if ("是".equals(cell.trim())) {
                return "true";
            }
            if ("否".equals(cell.trim())) {
                return "false";
            }
        }
        return cell;
    }

    /** 行错误包装：把内层错误模板按其参数格式化为可读原因（无参数原样返回） */
    private String formatFormMessage(FormBusinessException error) {
        Object[] args = error.getArgs();
        if (args == null || args.length == 0) {
            return error.getMessage();
        }
        try {
            return java.text.MessageFormat.format(error.getMessage(), args);
        } catch (IllegalArgumentException formatError) {
            return error.getMessage();
        }
    }

    private List<List<String>> parseExcel(java.io.InputStream inputStream) {
        try (cn.hutool.poi.excel.ExcelReader reader = ExcelUtil.getReader(inputStream)) {
            List<List<Object>> raw = reader.read();
            List<List<String>> rows = new ArrayList<>(raw.size());
            for (List<Object> cells : raw) {
                List<String> row = new ArrayList<>(cells.size());
                for (Object cell : cells) {
                    row.add(excelCellText(cell));
                }
                rows.add(row);
            }
            return rows;
        }
    }

    /** Excel 单元格 → 导入文本：日期（零点即日期）、数值去无效零（防电话号码科学计数）、布尔转 true/false */
    private String excelCellText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.time.LocalDateTime dateTime) {
            boolean midnight = dateTime.toLocalTime().equals(java.time.LocalTime.MIDNIGHT);
            return dateTime.format(
                    java.time.format.DateTimeFormatter.ofPattern(midnight ? "yyyy-MM-dd" : "yyyy-MM-dd HH:mm:ss"));
        }
        if (value instanceof java.util.Date date) {
            return excelCellText(java.time.LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault()));
        }
        if (value instanceof Boolean bool) {
            return bool ? "true" : "false";
        }
        if (value instanceof Number number) {
            java.math.BigDecimal decimal = new java.math.BigDecimal(number.toString()).stripTrailingZeros();
            return decimal.scale() <= 0 ? decimal.toBigInteger().toString() : decimal.toPlainString();
        }
        return value.toString();
    }

    /** 导出取值：树父字段/ORG/USER 优先用随行解析的名称（未命中回退原值），其余字段取原值 */
    private Object exportCellValue(String parentFieldName, FormField field, Map<String, Object> row) {
        if (parentFieldName != null && parentFieldName.equals(field.name())) {
            Object label = row.get(TREE_PARENT_LABEL_KEY);
            if (label != null) {
                return label;
            }
        }
        if (field.lookup() != null) {
            Object label = row.get(LOOKUP_LABEL_PREFIX + field.name());
            if (label != null) {
                return label;
            }
        }
        if (isBizField(field)) {
            Object label = row.get(BIZ_LABEL_PREFIX + field.name());
            if (label instanceof List<?> list) {
                return String.join("、", list.stream().map(Object::toString).toList());
            }
            if (label != null) {
                return label;
            }
        }
        return row.get(field.name());
    }

    private static boolean isBizField(FormField field) {
        return "ORG".equals(field.type()) || "USER".equals(field.type()) || "ROLE".equals(field.type());
    }

    /** 导出值格式化：布尔转是/否、日期时间友好格式，其余原样 */
    private Object formatExportValue(FormField field, Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean bool) {
            return bool ? "是" : "否";
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        return value;
    }

    /** CSV 单元格转义：含逗号/引号/换行时按 RFC4180 加引号并双写引号 */
    private String csvCell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    /**
     * 合计行（list.summary=true 的主表落库字段）：数值列 SUM、其余 COUNT，按筛选后的全量集合计算。
     * 列名全部来自模型白名单，值参数化沿用 where 的绑定。
     */
    private Map<String, Object> summaries(FormModel model, String whereSql, Map<String, Object> params) {
        List<FormField> summaryFields = model.fields().stream()
                .filter(field -> field.subFormKey() == null
                        && field.persisted()
                        && field.list().summary())
                .toList();
        if (summaryFields.isEmpty()) {
            return Map.of();
        }
        String selectSql = summaryFields.stream()
                .map(field -> {
                    boolean numeric = "INT".equals(field.type()) || "DECIMAL".equals(field.type());
                    return (numeric ? "SUM(" : "COUNT(") + field.column() + ") AS " + field.column();
                })
                .collect(Collectors.joining(", "));
        Map<String, Object> row = dynamicTableMapper.aggregate(model.tableName(), selectSql, whereSql, params);
        if (row == null) {
            return Map.of();
        }
        Map<String, Object> byField = new LinkedHashMap<>();
        summaryFields.forEach(field -> byField.put(field.name(), row.get(field.column())));
        return byField;
    }

    @Override
    public Map<String, Map<String, Object>> findRows(FormModel model, Collection<String> ids, Set<String> fieldNames) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        targetTableContract.validateAccess(model);
        Set<String> columns = new LinkedHashSet<>();
        columns.add(FormConstants.COLUMN_ID);
        model.fields().stream()
                .filter(field -> field.subFormKey() == null && fieldNames.contains(field.name()))
                .forEach(field -> columns.add(field.column()));
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : dynamicTableMapper.selectByFk(
                model.tableName(), String.join(", ", columns), FormConstants.COLUMN_ID, ids, activeRows(model), null)) {
            Map<String, Object> projected = project(model, row);
            byId.put(String.valueOf(projected.get("id")), projected);
        }
        return byId;
    }

    @Override
    public Map<String, Object> getById(FormModel model, String id) {
        targetTableContract.validateAccess(model);
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        Map<String, Object> row = dynamicTableMapper.selectById(
                model.tableName(),
                String.join(", ", selectColumns(model)),
                activeRows(model) + dataScopeFilter(model),
                params);
        if (row == null) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_NOT_FOUND);
        }
        Map<String, Object> result = project(model, row);
        attachSubRows(model, List.of(result), true);
        attachTreeParentLabels(model, List.of(result));
        attachBizLabels(model, List.of(result));
        attachLookupLabels(model, List.of(result));
        stripRoleHidden(model, List.of(result));
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
        stripRoleRestricted(model, data);
        Map<String, Object> row = normalize(model, data, true, tableColumns);
        backfillDataScope(model, row, tableColumns);
        requireUniqueValues(model, row, null);
        // SELF 自关联树：上级引用须存在（空=根节点）；新增不可能成环（新 id 尚无人引用）
        requireValidTreeParent(model, null, row);
        requireValidLookupValues(model, row);
        stripRoleRestricted(model, row);
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
            // 写入口径与读窗口（maxInlineSubRows 截断）对称：超限拒绝，防止单事务内无限量 INSERT
            if (rows.size() > formProperties.getMaxInlineSubRows()) {
                throw new FormBusinessException(
                        FormErrorCode.FORM_DATA_INVALID,
                        relation.subFormKey() + " 提交子行数超过上限：" + formProperties.getMaxInlineSubRows());
            }
            if (rows.isEmpty()) {
                continue;
            }
            FormModel subModel = overlaySubModel(relation, requireSubModel(model, relation));
            // 外键列由服务端回填主行 id（写入前剥离客户端值）：豁免契约的「无法输入的必填列」判定
            Map<String, TableColumnView> subColumns = targetTableContract.validate(subModel, subBoundColumns(relation));
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
            // 仅回读缺省列：完整读组装（子表挂载/树标签/biz 标签/行指纹）的产物在这里全部用不到
            Map<String, Object> probeParams = new HashMap<>();
            probeParams.put("id", id);
            List<String> probeColumns = new ArrayList<>();
            probeColumns.add(FormConstants.COLUMN_ID);
            defaulted.forEach(field -> probeColumns.add(field.column()));
            Map<String, Object> raw = dynamicTableMapper.selectById(
                    model.tableName(), String.join(", ", probeColumns), activeRows(model), probeParams);
            if (raw == null) {
                throw new FormBusinessException(FormErrorCode.FORM_DATA_NOT_FOUND);
            }
            Map<String, Object> inserted = project(model, raw);
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
        stripRoleRestricted(model, data);
        Map<String, Object> row = normalize(model, data, false, tableColumns);
        if (row.isEmpty()) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_INVALID, "没有可更新的字段");
        }
        requireUniqueValues(model, row, id);
        // SELF 自关联树：提交了上级字段时校验存在性，且不得指向自身或子孙（防环）
        requireValidTreeParent(model, id, row);
        requireValidLookupValues(model, row);
        stripRoleRestricted(model, row);
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
            FormModel subModel = overlaySubModel(relation, requireSubModel(model, relation));
            Map<String, TableColumnView> subColumns = targetTableContract.validate(subModel, subBoundColumns(relation));
            diffSubRows(subModel, relation, subColumns, id, subRows(data, relation), expectedEtag != null);
        }
        interceptors.resolve(model.interceptor()).ifPresent(i -> i.afterUpdate(model, id, data));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(FormModel model, String id) {
        Map<String, TableColumnView> tableColumns = targetTableContract.validate(model);
        getById(model, id);
        // SELF 自关联树：有未删除下级即拒绝（父删后子行在树上不可达，成为幽灵数据）
        requireNoChildNodes(model, id);
        // 主从级联：CASCADE_DELETE 先清理子行（尊重子表逻辑删除语义），FORBID_DELETE 有子行即拒绝
        for (FormRelation relation : model.relations()) {
            FormModel subModel = requireSubModel(model, relation);
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
        String whereSql =
                activeRows(model) + " AND " + model.tree().parentColumn() + condition + dataScopeFilter(model);
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
                            activeRows(model) + " AND " + parentColumn + " IN (" + countIn + ")"
                                    + dataScopeFilter(model),
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
     * 从主模型现构子模型：字段集 = 主清单中归属该关系的从表字段，表名直连物理表。
     * 外键已映射（编译期校验），逻辑删除列沿用主表口径（主子表同名要求由保存期校验拦截）
     */
    /** 子表契约的服务端绑定列：外键列的值由引擎回填主行 id，不经表单输入，豁免「无法输入的必填列」判定 */
    private static Set<String> subBoundColumns(FormRelation relation) {
        return Set.of(relation.foreignKey().toUpperCase(Locale.ROOT));
    }

    private FormModel requireSubModel(FormModel model, FormRelation relation) {
        // 子表直连物理表：须在动态读写允许清单内（发布快照被篡改时此处拦截）
        if (!formProperties.isMappableTable(relation.subTable())) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "子表未开放动态读写：" + relation.subTable());
        }
        List<FormField> subFields = new ArrayList<>();
        model.fields().stream()
                .filter(field -> relation.subFormKey().equals(field.subFormKey()))
                // 归属标记只约束主清单的读写路径（主表投影跳过从字段）；现构子模型里它们是主字段，必须清掉
                .map(field -> new FormField(
                        field.name(),
                        field.label(),
                        field.column(),
                        field.type(),
                        field.nullable(),
                        field.defaultValue(),
                        field.dictType(),
                        field.sort(),
                        field.persisted(),
                        null,
                        field.query(),
                        field.list(),
                        field.form(),
                        null,
                        null))
                .forEach(subFields::add);
        // 目标表契约要求主键映射：清单未显式导入主键列时，注入不落库的主键展示字段
        // （与主表清单的主键契约同口径）。设计时已按主键列导入的关系不重复注入，保证单一事实来源。
        if (subFields.stream().noneMatch(field -> FormConstants.COLUMN_ID.equals(field.column()))) {
            subFields.add(new FormField(
                    "rowId",
                    "主键",
                    FormConstants.COLUMN_ID,
                    "STRING",
                    true,
                    null,
                    null,
                    0,
                    false,
                    null,
                    new FormModel.QueryConf(false, null, null),
                    new FormModel.ListConf(true, null, null, null, null, false, null, 0),
                    new FormModel.FormConf(true, null, Map.of(), Map.of(), false, null),
                    null,
                    null));
        }
        return new FormModel(
                relation.subFormKey(),
                relation.subFormKey(),
                model.version(),
                FormConstants.FORM_TYPE_SINGLE,
                relation.subTable(),
                model.datasourceId(),
                model.logicDeleteColumn(),
                model.listConfig(),
                null,
                null,
                subFields,
                List.of(),
                null,
                null,
                null,
                null);
    }

    /**
     * 子模型叠加场景覆盖（subTableConfig 列覆盖键：required/pattern/min/max/message/defaultValue/readonly），
     * 子行写入校验与前端呈现同一事实源；无覆盖直接返回原模型
     */
    private FormModel overlaySubModel(FormRelation relation, FormModel subModel) {
        Map<String, Map<String, Object>> overrides =
                SubTableConfigs.overridesOf(objectMapper, relation.subTableConfig());
        if (overrides.isEmpty()) {
            return subModel;
        }
        List<FormField> fields = subModel.fields().stream()
                .map(field -> overlayField(field, overrides.get(field.name())))
                .toList();
        return new FormModel(
                subModel.formKey(),
                subModel.formName(),
                subModel.version(),
                subModel.formType(),
                subModel.tableName(),
                subModel.datasourceId(),
                subModel.logicDeleteColumn(),
                subModel.listConfig(),
                subModel.detailConfig(),
                subModel.formLayout(),
                fields,
                subModel.relations(),
                subModel.tree(),
                subModel.interceptor(),
                subModel.dataScopeType(),
                subModel.dataScopeColumn());
    }

    /** 单列覆盖落位：校验键并进 rules，required 收窄可空，defaultValue/readonly 覆盖 */
    private FormField overlayField(FormField field, Map<String, Object> override) {
        if (override == null) {
            return field;
        }
        Map<String, Object> rules = new LinkedHashMap<>(field.form().rules());
        for (String key : List.of("required", "pattern", "min", "max", "message")) {
            if (override.containsKey(key)) {
                rules.put(key, override.get(key));
            }
        }
        boolean nullable = Boolean.TRUE.equals(override.get("required")) ? false : field.nullable();
        String defaultValue = override.get("defaultValue") instanceof String value ? value : field.defaultValue();
        boolean readonly = override.get("readonly") instanceof Boolean value
                ? value
                : field.form().readonly();
        return new FormField(
                field.name(),
                field.label(),
                field.column(),
                field.type(),
                nullable,
                defaultValue,
                field.dictType(),
                field.sort(),
                field.persisted(),
                field.subFormKey(),
                field.query(),
                field.list(),
                new FormModel.FormConf(
                        field.form().show(),
                        field.form().component(),
                        field.form().props(),
                        rules,
                        readonly,
                        // 子表区块不参与节点填写：从字段不带节点权限（渲染端子表恒只读）
                        null),
                null,
                null);
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
        FormModel subModel = requireSubModel(model, relation);
        Map<String, TableColumnView> subColumns = targetTableContract.validate(subModel, subBoundColumns(relation));
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
        Map<String, Object> row = normalize(subModel, subData, false, subColumns, subBoundColumns(relation));
        // 外键列不接受客户端改写：FLAT 从行归属由既有外键值绑定（与 insertSubRows/updateSubRow 同口径）
        row.keySet().removeIf(column -> column.equalsIgnoreCase(relation.foreignKey()));
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
        FormModel subModel = requireSubModel(model, relation);
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
                .map(row -> {
                    // 子行独立编辑的乐观锁凭证：指纹口径与 requireSubRowUnchanged 预读投影一致
                    Map<String, Object> projected = project(subModel, row);
                    projected.put(ETAG_KEY, etagOf(projected));
                    return projected;
                })
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
        FormModel subModel = overlaySubModel(relation, requireSubModel(model, relation));
        // 子表内嵌主表单后无独立实体：写权限并入主表（端点已校验主表 ADD），此处不再二次校验
        getById(model, id);
        Map<String, TableColumnView> subColumns = targetTableContract.validate(subModel, subBoundColumns(relation));
        insertSubRows(relation, subModel, subColumns, id, List.of(data));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSubRow(FormModel model, String id, String subFormKey, String subId, Map<String, Object> data) {
        FormRelation relation = requireRelation(model, subFormKey);
        FormModel subModel = overlaySubModel(relation, requireSubModel(model, relation));
        getById(model, id);
        String expectedEtag = peekEtag(data);
        if (expectedEtag != null) {
            requireSubRowUnchanged(subModel, relation, id, subId, expectedEtag);
        }
        Map<String, TableColumnView> subColumns = targetTableContract.validate(subModel, subBoundColumns(relation));
        Map<String, Object> row = normalize(subModel, data, false, subColumns);
        // 外键列不接受客户端改写：子行归属由路径上的主行 id 绑定
        row.keySet().removeIf(column -> column.equalsIgnoreCase(relation.foreignKey()));
        if (row.isEmpty()) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_INVALID, "没有可更新的字段");
        }
        requireUniqueValues(subModel, row, subId);
        Map<String, Object> params = new HashMap<>();
        params.put("id", subId);
        params.put("fkValue", id);
        List<String> assignments = new ArrayList<>();
        row.forEach((column, value) -> assignments.add(column + " = " + bind("p" + params.size(), value, params)));
        if (subColumns.containsKey("UPDATED_BY")) {
            assignments.add("UPDATED_BY = " + bind("p" + params.size(), AuthUtils.getSafeUsername(), params));
        }
        if (subColumns.containsKey("UPDATED_AT")) {
            assignments.add("UPDATED_AT = " + bind("p" + params.size(), LocalDateTime.now(), params));
        }
        // 归属防线：WHERE 同时带外键条件，跨主行的子行 id 命中 0 行按不存在处理
        if (dynamicTableMapper.updateDynamic(
                        subModel.tableName(), String.join(", ", assignments), subRowScope(subModel, relation), params)
                == 0) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_NOT_FOUND);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSubRow(FormModel model, String id, String subFormKey, String subId) {
        FormRelation relation = requireRelation(model, subFormKey);
        FormModel subModel = requireSubModel(model, relation);
        getById(model, id);
        Map<String, TableColumnView> subColumns = targetTableContract.validate(subModel, subBoundColumns(relation));
        Map<String, Object> params = new HashMap<>();
        params.put("id", subId);
        params.put("fkValue", id);
        String scope = subRowScope(subModel, relation);
        if (StringUtils.hasText(subModel.logicDeleteColumn())) {
            List<String> assignments = new ArrayList<>();
            assignments.add(subModel.logicDeleteColumn() + " = 1");
            if (subColumns.containsKey("UPDATED_BY")) {
                assignments.add("UPDATED_BY = " + bind("p" + params.size(), AuthUtils.getSafeUsername(), params));
            }
            if (subColumns.containsKey("UPDATED_AT")) {
                assignments.add("UPDATED_AT = " + bind("p" + params.size(), LocalDateTime.now(), params));
            }
            if (dynamicTableMapper.updateDynamic(subModel.tableName(), String.join(", ", assignments), scope, params)
                    == 0) {
                throw new FormBusinessException(FormErrorCode.FORM_DATA_NOT_FOUND);
            }
            return;
        }
        if (dynamicTableMapper.deleteById(subModel.tableName(), scope, params) == 0) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_NOT_FOUND);
        }
    }

    /** 台账式子行的作用域条件：有效行且挂在路径主行之下（外键由服务端绑定，不信任客户端） */
    private String subRowScope(FormModel subModel, FormRelation relation) {
        return activeRows(subModel) + " AND " + relation.foreignKey() + " = #{params.fkValue}";
    }

    /** 子行乐观锁：行锁预读（带归属条件）后比对指纹，口径与台账式分页下发一致 */
    private void requireSubRowUnchanged(
            FormModel subModel, FormRelation relation, String mainId, String subId, String expectedEtag) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", subId);
        params.put("fkValue", mainId);
        Map<String, Object> raw = dynamicTableMapper.selectForUpdate(
                subModel.tableName(),
                String.join(", ", selectColumns(subModel)),
                subRowScope(subModel, relation),
                params);
        if (raw == null) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_NOT_FOUND);
        }
        if (!etagOf(project(subModel, raw)).equals(expectedEtag)) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_CONFLICT);
        }
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
        // 写入口径与读窗口（maxInlineSubRows 截断）对称：超限拒绝，防止单事务内无限量子行写入
        if (rows.size() > formProperties.getMaxInlineSubRows()) {
            throw new FormBusinessException(
                    FormErrorCode.FORM_DATA_INVALID,
                    relation.subFormKey() + " 提交子行数超过上限：" + formProperties.getMaxInlineSubRows());
        }
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
            Map<String, Object> row = normalize(subModel, item, true, subColumns, subBoundColumns(relation));
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
     *
     * <p>{@code withInlineRows=false}（列表页）跳过 INLINE 子行查询：整页内联挂载受
     * 「页大小 × maxInlineSubRows」乘积放大，且内联子行只在编辑/详情按 id 单行加载；INLINE 关系
     * 与台账式同形仅置空占位。FLAT 一对一从字段并入主表呈现（列表/详情/编辑融合），始终挂载。
     */
    private void attachSubRows(FormModel model, List<Map<String, Object>> rows, boolean withInlineRows) {
        if (model.relations().isEmpty() || rows.isEmpty()) {
            return;
        }
        for (FormRelation relation : model.relations()) {
            if (relation.listMode()) {
                rows.forEach(row -> row.put(relation.subFormKey(), List.of()));
                continue;
            }
            if (!relation.flatMode() && !withInlineRows) {
                rows.forEach(row -> row.put(relation.subFormKey(), List.of()));
                continue;
            }
            FormModel subModel;
            try {
                subModel = requireSubModel(model, relation);
            } catch (FormBusinessException error) {
                log.warn("主从子表单不可用，读取跳过该关系：{} - {}", relation.subFormKey(), error.getMessage());
                rows.forEach(row -> row.put(relation.subFormKey(), List.of()));
                continue;
            }
            List<String> ids =
                    rows.stream().map(row -> String.valueOf(row.get("id"))).toList();
            // FLAT 一对一每主行只取最新一条（窗口降序取首行）；INLINE 内联每主行截断到上限（完整数据走台账式端点）
            boolean flat = relation.flatMode();
            List<Map<String, Object>> subRows = dynamicTableMapper.selectByFkWindowed(
                    subModel.tableName(),
                    String.join(", ", selectColumns(subModel)),
                    relation.foreignKey(),
                    ids,
                    activeRows(subModel),
                    flat ? 1 : formProperties.getMaxInlineSubRows(),
                    flat ? "DESC" : "ASC");
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
                List<Map<String, Object>> subs = grouped.getOrDefault(String.valueOf(row.get("id")), List.of());
                // 子行的 ORG/USER/ROLE 同样按 id→名称解析（内联子网格/详情子表按名称呈现）
                attachBizLabels(subModel, subs);
                row.put(relation.subFormKey(), subs);
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
        return normalize(model, data, create, columns, Set.of());
    }

    /**
     * 入参归一（子表口径）：serverBoundColumns 为服务端绑定列（外键，值由引擎在写入前回填主行 id）。
     * 绑定列未提交时直接跳过归一——物理 NOT NULL 外键字段（nullable=false）在此处先于回填，
     * 若走必填/非空校验会对合法提交误报「不能为空」；提交了绑定列则照常校验，随后由写入方剥离防伪造归属。
     */
    private Map<String, Object> normalize(
            FormModel model,
            Map<String, Object> data,
            boolean create,
            Map<String, TableColumnView> columns,
            Set<String> serverBoundColumns) {
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
            // 绑定列按列名大小写不敏感匹配（与剥列的 equalsIgnoreCase 同口径，编译期已统一大写为双保险）
            if (!submitted
                    && (!create
                            || serverBoundColumns.stream().anyMatch(bound -> bound.equalsIgnoreCase(field.column())))) {
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
     * 乐观锁：行锁预读后比对行指纹（口径与详情读取一致，覆盖主行与 INLINE 子行），
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
        attachSubRows(model, new ArrayList<>(List.of(projected)), true);
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
                // 保留键不参与指纹：__etag 本身、随行下发的展示辅助键（树上级名/系统主数据名，非落库数据）
                if (ETAG_KEY.equals(key) || TREE_PARENT_LABEL_KEY.equals(key) || isBizLabelKey(String.valueOf(key))) {
                    return;
                }
                sorted.put(String.valueOf(key), canonicalize(item));
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

    /** 行级数据权限过滤（SQL 标记模式）：由 DataScopeInterceptor 把 'permissions|{type}' 替换为授权子查询 */
    private String dataScopeFilter(FormModel model) {
        if (!model.dataScopeEnabled()) {
            return "";
        }
        return " AND " + model.dataScopeColumn() + " IN ('permissions|" + model.dataScopeType() + "')";
    }

    /**
     * 行级数据权限启用时回填归属列：客户端未提供值则取当前用户组织 id（行默认归属创建人所在组织），
     * 无法确定归属（无组织）显式拒绝——否则行不可被任何非 owner 用户看到，成为幽灵数据。
     */
    private void backfillDataScope(
            FormModel model, Map<String, Object> row, Map<String, TableColumnView> tableColumns) {
        if (!model.dataScopeEnabled() || row.containsKey(model.dataScopeColumn())) {
            return;
        }
        String orgId = AuthUtils.getUser() == null ? null : AuthUtils.getUser().getOrgId();
        if (orgId == null || orgId.isBlank()) {
            throw new FormBusinessException(FormErrorCode.FORM_DATA_INVALID, "行级数据权限已启用且无法确定数据归属组织");
        }
        TargetTableContract.validateValue(
                tableColumns.get(model.dataScopeColumn()), orgId, FormErrorCode.FORM_DATA_INVALID);
        row.put(model.dataScopeColumn(), orgId);
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
                case "like" -> {
                    // 转义通配符，避免用户输入 %/_ 构造低成本全表扫描
                    String pattern = "%" + escapeLike(String.valueOf(condition.value())) + "%";
                    sql.append(" AND ")
                            .append(column)
                            .append(" LIKE ")
                            .append(bind(nextKey(params), pattern, params))
                            .append(" ESCAPE '\\'");
                }
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

    /** SELF 自关联树：存在未删除下级即拒绝删除（父删后子行在树上不可达，成为幽灵数据） */
    private void requireNoChildNodes(FormModel model, String id) {
        TreeConfig tree = model.tree();
        if (tree == null || !tree.selfRelation()) {
            return;
        }
        Map<String, Object> params = new HashMap<>();
        String where = activeRows(model) + " AND " + tree.parentColumn() + " = " + bind(nextKey(params), id, params);
        if (dynamicTableMapper.countBy(model.tableName(), where, params) > 0) {
            throw new FormBusinessException(FormErrorCode.FORM_TREE_DELETE_FORBIDDEN);
        }
    }

    /**
     * SELF 自关联树：校验提交的上级引用。空串归一为 NULL（空=根节点，避免空串悬挂在树上不可达）；
     * 非空时上级须存在；更新时（selfId 非空）上级不得是自身或自身子孙（子孙集解析沿用既有上限，
     * 超限按 FORM_TREE_DESCENDANT_LIMIT 拒绝）。create 传 selfId=null：新 id 尚无人引用，只校验存在性。
     */
    /**
     * 当前用户角色集；无 Sa-Token 上下文（系统任务/测试）返回 null，调用方据此不施加角色限制
     */
    private Set<String> currentRoles() {
        try {
            UserDetails user = AuthUtils.getUser();
            return user == null ? null : user.getRoles();
        } catch (Exception error) {
            return null;
        }
    }

    /** 字段级角色权限：剔除当前用户不可编辑字段的提交值（保留原值/缺省；无登录上下文的系统路径不处理） */
    private void stripRoleRestricted(FormModel model, Map<String, Object> data) {
        Set<String> roles = currentRoles();
        if (roles == null) {
            return;
        }
        for (FormField field : model.fields()) {
            if (field.rolePerms() != null && !field.rolePerms().editableBy(roles)) {
                data.remove(field.name());
            }
        }
    }

    /** 字段级角色权限：剔除当前用户不可见字段的下发值与回显名（无登录上下文的系统路径不处理） */
    private void stripRoleHidden(FormModel model, List<Map<String, Object>> rows) {
        Set<String> roles = currentRoles();
        if (roles == null || rows.isEmpty()) {
            return;
        }
        List<String> hidden = model.fields().stream()
                .filter(field -> field.rolePerms() != null && !field.rolePerms().visibleBy(roles))
                .map(FormField::name)
                .toList();
        if (hidden.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : rows) {
            for (String name : hidden) {
                row.remove(name);
                row.remove(BIZ_LABEL_PREFIX + name);
                row.remove(LOOKUP_LABEL_PREFIX + name);
            }
        }
    }

    /** 关联字段（LOOKUP）写入校验：提交了引用值的，目标表单中必须存在该行 */
    private void requireValidLookupValues(FormModel model, Map<String, Object> row) {
        for (FormField field : model.fields()) {
            if (field.lookup() == null || !row.containsKey(field.column())) {
                continue;
            }
            Object value = row.get(field.column());
            if (value == null || value.toString().isBlank()) {
                continue;
            }
            String id = value.toString();
            FormModel targetModel = modelProvider.require(field.lookup().targetFormKey());
            Map<String, Map<String, Object>> targetRows =
                    findRows(targetModel, List.of(id), Set.of(field.lookup().displayField()));
            if (!targetRows.containsKey(id)) {
                throw new FormBusinessException(FormErrorCode.FORM_DATA_INVALID, field.name() + " 关联的记录不存在：" + id);
            }
        }
    }

    /**
     * 关联字段回显：按引用 id 批量取目标表单行，随行下发 __lookupLabel_{字段}（未命中保留原 id）。
     * 目标表单不可用（未发布/停用/已删）时跳过该字段回显，不阻断主表读取。
     */
    private void attachLookupLabels(FormModel model, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        for (FormField field : model.fields()) {
            if (field.lookup() == null || field.subFormKey() != null) {
                continue;
            }
            Set<String> ids = rows.stream()
                    .map(row -> row.get(field.name()))
                    .filter(java.util.Objects::nonNull)
                    .map(Object::toString)
                    .filter(id -> !id.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (ids.isEmpty()) {
                continue;
            }
            String key = LOOKUP_LABEL_PREFIX + field.name();
            try {
                FormModel targetModel =
                        modelProvider.require(field.lookup().targetFormKey()).withoutDataScope();
                Map<String, Map<String, Object>> targetRows =
                        findRows(targetModel, ids, Set.of(field.lookup().displayField()));
                rows.forEach(row -> {
                    Object raw = row.get(field.name());
                    if (raw == null) {
                        return;
                    }
                    Map<String, Object> target = targetRows.get(raw.toString());
                    Object display =
                            target == null ? null : target.get(field.lookup().displayField());
                    row.put(key, display == null ? raw.toString() : display);
                });
            } catch (FormBusinessException error) {
                log.warn(
                        "关联字段回显跳过：目标表单不可用 field={} target={} - {}",
                        field.name(),
                        field.lookup().targetFormKey(),
                        error.getMessage());
            }
        }
    }

    private void requireValidTreeParent(FormModel model, String selfId, Map<String, Object> row) {
        TreeConfig tree = model.tree();
        if (tree == null || !tree.selfRelation() || !row.containsKey(tree.parentColumn())) {
            return;
        }
        Object parent = row.get(tree.parentColumn());
        if (parent == null) {
            return;
        }
        String parentId = String.valueOf(parent).trim();
        if (parentId.isEmpty()) {
            row.put(tree.parentColumn(), null);
            return;
        }
        Map<String, Object> params = new HashMap<>();
        String where =
                activeRows(model) + " AND " + FormConstants.COLUMN_ID + " = " + bind(nextKey(params), parentId, params);
        if (dynamicTableMapper.countBy(model.tableName(), where, params) == 0) {
            throw new FormBusinessException(FormErrorCode.FORM_TREE_PARENT_NOT_FOUND);
        }
        if (selfId != null && resolveSubtreeIds(model, selfId, new HashMap<>()).contains(parentId)) {
            throw new FormBusinessException(FormErrorCode.FORM_TREE_PARENT_CYCLE);
        }
    }

    /** SELF/FORM 树的上级字段标识（DICT 值是字典代码、无上级行概念，返回 null）：SELF 取父列映射字段，FORM 取节点字段 */
    private String treeParentFieldName(FormModel model) {
        TreeConfig tree = model.tree();
        if (tree == null || tree.dictSource()) {
            return null;
        }
        if (tree.selfRelation()) {
            return model.fields().stream()
                    .filter(field ->
                            field.subFormKey() == null && field.column().equalsIgnoreCase(tree.parentColumn()))
                    .findFirst()
                    .map(FormField::name)
                    .orElse(null);
        }
        return tree.nodeIdField();
    }

    /**
     * ORG/USER 字段 id→名称解析（随行下发 __bizLabel_{字段}）：机构走树打平、用户按用户名 IN 批量查。
     * 数据源缺席（authority 裁剪）时跳过，列表/详情/导出回退原值显示。
     */
    private void attachBizLabels(FormModel model, List<Map<String, Object>> rows) {
        List<FormField> bizFields = model.fields().stream()
                .filter(field -> field.subFormKey() == null && isBizField(field))
                .toList();
        if (bizFields.isEmpty() || rows.isEmpty()) {
            return;
        }
        BizDataProvider provider = bizDataProviders.getIfAvailable();
        if (provider == null) {
            return;
        }
        for (FormField field : bizFields) {
            // ROLE 多选：值为 authority 集合，扁平化为 id 集
            Set<String> ids = rows.stream()
                    .flatMap(row -> bizIds(row.get(field.name())))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (ids.isEmpty()) {
                continue;
            }
            Map<String, String> labels = provider.labels(field.type(), ids);
            String key = BIZ_LABEL_PREFIX + field.name();
            rows.forEach(row -> {
                Object raw = row.get(field.name());
                if (raw instanceof List<?> list) {
                    // 多选：按提交顺序翻译，未命中的 id 保留原值
                    row.put(
                            key,
                            list.stream()
                                    .map(java.util.Objects::toString)
                                    .map(id -> labels.getOrDefault(id, id))
                                    .toList());
                } else {
                    row.put(key, labels.get(String.valueOf(raw)));
                }
            });
        }
    }

    /** biz 字段值的 id 流：单值字符串或多选集合拍平 */
    private static java.util.stream.Stream<String> bizIds(Object value) {
        if (value == null) {
            return java.util.stream.Stream.empty();
        }
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(java.util.Objects::toString);
        }
        return java.util.stream.Stream.of(value.toString());
    }

    private static boolean isBizLabelKey(String key) {
        return key != null && key.startsWith(BIZ_LABEL_PREFIX);
    }

    /**
     * 为行附加上级节点展示名（TREE_PARENT_LABEL_KEY）：SELF 取本表节点展示列，FORM 取关联树表单的
     * 节点展示列；未命中的上级（无行/已删）不覆盖。供平铺列表、详情与导出统一「上级=名称」的展示口径。
     */
    private void attachTreeParentLabels(FormModel model, List<Map<String, Object>> rows) {
        TreeConfig tree = model.tree();
        if (tree == null || tree.dictSource() || rows.isEmpty()) {
            return;
        }
        String parentFieldName = treeParentFieldName(model);
        if (parentFieldName == null) {
            return;
        }
        Set<String> parentValues = rows.stream()
                .map(row -> row.get(parentFieldName))
                .filter(value -> value != null && StringUtils.hasText(value.toString()))
                .map(value -> value.toString())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (parentValues.isEmpty()) {
            return;
        }
        final FormModel labelModel;
        final String labelColumn;
        if (tree.formSource()) {
            labelModel = modelProvider.require(tree.treeFormKey());
            if (labelModel.tree() == null || !labelModel.tree().selfRelation()) {
                throw new FormBusinessException(
                        FormErrorCode.FORM_TABLE_INVALID, "关联树表单须为自关联树类型：" + tree.treeFormKey());
            }
            labelColumn = labelModel.tree().nodeColumn();
        } else {
            labelModel = model;
            labelColumn = tree.nodeColumn();
        }
        if (!StringUtils.hasText(labelColumn)) {
            return;
        }
        Map<String, Object> params = new HashMap<>();
        String inSql = parentValues.stream()
                .map(value -> bind(nextKey(params), value, params))
                .collect(Collectors.joining(", "));
        Map<String, String> labels = new HashMap<>();
        dynamicTableMapper
                .selectList(
                        labelModel.tableName(),
                        FormConstants.COLUMN_ID + ", " + labelColumn,
                        activeRows(labelModel) + " AND " + FormConstants.COLUMN_ID + " IN (" + inSql + ")",
                        null,
                        params)
                .forEach(row -> {
                    Object id = columnValue(row, FormConstants.COLUMN_ID);
                    Object label = columnValue(row, labelColumn);
                    if (id != null && label != null) {
                        labels.put(id.toString(), String.valueOf(label));
                    }
                });
        rows.forEach(row -> row.put(TREE_PARENT_LABEL_KEY, labels.get(String.valueOf(row.get(parentFieldName)))));
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

    private String buildOrder(FormModel model, List<ResolvedSort> sorts, Map<String, TableColumnView> tableColumns) {
        if (sorts.isEmpty()) {
            // 客户端未排序时按设计期默认排序（sortOrder 升序定优先级），均未配置再回退创建时间
            List<FormField> defaultSorts = model.fields().stream()
                    .filter(field -> field.sortable() && field.list().defaultSort() != null)
                    .sorted(Comparator.comparingInt(field -> field.list().sortOrder()))
                    .toList();
            if (!defaultSorts.isEmpty()) {
                return " ORDER BY "
                        + defaultSorts.stream()
                                .map(field -> field.column()
                                        + ("asc".equals(field.list().defaultSort()) ? " ASC" : " DESC"))
                                .collect(Collectors.joining(", "))
                        + ", ID DESC";
            }
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

    /** LIKE 通配符转义（配合 ESCAPE '\'） */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private Collection<?> asCollection(Object value) {
        Collection<?> collection;
        if (value instanceof Collection<?> list
                && !list.isEmpty()
                && list.stream().noneMatch(java.util.Objects::isNull)) {
            collection = list;
        } else if (value instanceof Object[] array) {
            collection = asCollection(Arrays.asList(array));
        } else {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "in 操作符需要不含 null 的非空数组");
        }
        if (collection.size() > formProperties.getMaxInValues()) {
            throw new FormBusinessException(FormErrorCode.FORM_QUERY_CONDITION_LIMIT, "in 条件值数超过上限");
        }
        return collection;
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
        // 深分页钳制：超出上限的页码按上限处理（OFFSET 深度由此封顶）
        return Math.min(Math.max(1L, pageNum), formProperties.getMaxPageNum());
    }

    private long pageSize(FormDataPageQuery query) {
        Long size = query.getPageSize() == null ? 20L : query.getPageSize().longValue();
        return Math.min(Math.max(1L, size), MAX_PAGE_SIZE);
    }
}
