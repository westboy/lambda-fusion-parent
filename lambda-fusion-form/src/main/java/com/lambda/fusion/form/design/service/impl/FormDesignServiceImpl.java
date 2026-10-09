package com.lambda.fusion.form.design.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.cloud.core.shared.BaseVO;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.core.service.AbstractCrudService;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.FormProperties;
import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.entity.FormDefVersionEntity;
import com.lambda.fusion.form.design.entity.FormFieldEntity;
import com.lambda.fusion.form.design.entity.FormRelationEntity;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.design.mapper.FormDefVersionMapper;
import com.lambda.fusion.form.design.mapper.FormFieldMapper;
import com.lambda.fusion.form.design.mapper.FormRelationMapper;
import com.lambda.fusion.form.design.model.FormDefPage;
import com.lambda.fusion.form.design.model.FormDefSaveCommand;
import com.lambda.fusion.form.design.model.FormDefView;
import com.lambda.fusion.form.design.model.FormFieldItem;
import com.lambda.fusion.form.design.model.FormRelationItem;
import com.lambda.fusion.form.design.model.TableColumnView;
import com.lambda.fusion.form.design.service.FormDesignService;
import com.lambda.fusion.form.design.service.FormPublishService;
import com.lambda.fusion.form.design.support.DataSourceCatalog;
import com.lambda.fusion.form.design.support.TableMetadataReader;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.integration.FormDataInterceptors;
import com.lambda.fusion.form.runtime.support.FieldConfigs;
import com.lambda.fusion.form.runtime.support.FormLayouts;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.form.runtime.support.SubTableConfigs;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 表单草稿设计服务实现
 *
 * <p>草稿工作行就地编辑 def/field/relation 三表；租户过滤与审计填充由框架承担。
 * 发布物不可变，运行时只读快照（见 FormPublishService）。
 *
 * @author Zx
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class FormDesignServiceImpl extends AbstractCrudService<FormDefEntity, FormDefView, FormDefMapper>
        implements FormDesignService {

    private final FormFieldMapper formFieldMapper;
    private final FormRelationMapper formRelationMapper;
    private final FormDefVersionMapper formDefVersionMapper;
    private final TableMetadataReader tableMetadataReader;
    private final ObjectMapper objectMapper;
    private final FormPublishService formPublishService;
    private final FormModelProvider formModelProvider;
    private final FormDataInterceptors formDataInterceptors;
    private final FormProperties formProperties;
    private final ObjectProvider<DataSourceCatalog> dataSourceCatalogProvider;

    @Override
    public PageView<FormDefView> page(FormDefPage page) {
        return pageView(page.getPage(), page.getLambdaQueryWrapper());
    }

    @Override
    public FormDefView detail(String id) {
        FormDefView view = getByIdForVO(id);
        if (view == null) {
            throw new FormBusinessException(FormErrorCode.FORM_NOT_FOUND, id);
        }
        view.setFields(BaseVO.fromEntityList(
                FormFieldItem.class,
                formFieldMapper.selectList(new LambdaQueryWrapper<FormFieldEntity>()
                        .eq(FormFieldEntity::getFormId, id)
                        .orderByAsc(FormFieldEntity::getSort))));
        view.setRelations(BaseVO.fromEntityList(
                FormRelationItem.class,
                formRelationMapper.selectList(
                        new LambdaQueryWrapper<FormRelationEntity>().eq(FormRelationEntity::getFormId, id))));
        return view;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String save(FormDefSaveCommand command) {
        validate(command);
        requireKeyFree(command.getFormKey(), null);
        FormDefEntity def = new FormDefEntity();
        apply(def, command);
        def.setStatus(FormConstants.STATUS_DRAFT);
        save(def);
        replaceChildren(def.getId(), command);
        if (Boolean.TRUE.equals(command.getPublishOnSave())) {
            formPublishService.publish(def.getId());
        }
        return def.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(String id, FormDefSaveCommand command) {
        FormDefEntity def = requireExists(id);
        validate(command);
        requireKeyFree(command.getFormKey(), id);
        if (command.getVersion() == null) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "更新必须携带草稿版本");
        }
        if (!command.getVersion().equals(def.getVersion())) {
            throw new FormBusinessException(FormErrorCode.FORM_DRAFT_CONFLICT);
        }
        if (!Objects.equals(def.getFormKey(), command.getFormKey())
                || !Objects.equals(def.getFormType(), command.getFormType())
                || !Objects.equals(def.getTableName(), command.getTableName())
                || !Objects.equals(def.getDatasourceId(), normalizeDatasource(command.getDatasourceId()))) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "表单标识、类型、目标表和数据源创建后不可修改");
        }
        apply(def, command);
        def.setUpdatedAt(null);
        def.setUpdatedBy(null);
        if (!updateById(def)) {
            throw new FormBusinessException(FormErrorCode.FORM_DRAFT_CONFLICT);
        }
        replaceChildren(id, command);
        if (Boolean.TRUE.equals(command.getPublishOnSave())) {
            formPublishService.publish(id);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(String id) {
        FormDefEntity def = requireExists(id);
        if (def.getRouteResourceId() != null) {
            throw new FormBusinessException(FormErrorCode.FORM_ROUTE_EXISTS);
        }
        if (FormConstants.STATUS_PUBLISHED.equals(def.getStatus())) {
            throw new FormBusinessException(FormErrorCode.FORM_DELETE_FORBIDDEN);
        }
        formFieldMapper.delete(new LambdaQueryWrapper<FormFieldEntity>().eq(FormFieldEntity::getFormId, id));
        formRelationMapper.delete(new LambdaQueryWrapper<FormRelationEntity>().eq(FormRelationEntity::getFormId, id));
        formDefVersionMapper.delete(
                new LambdaQueryWrapper<FormDefVersionEntity>().eq(FormDefVersionEntity::getFormId, id));
        removeById(id);
        // 停用表单删除后释放可能残留的编译结果缓存
        formModelProvider.evictAfterCommit(id);
    }

    @Override
    public List<String> listTables(String datasourceId) {
        // 下拉列出排除平台内部表后的物理表（排除口径见 FormProperties#isMappableTable，
        // 元数据读取器内部同口径过滤）
        return tableMetadataReader.listTables(datasourceId);
    }

    @Override
    public List<TableColumnView> listColumns(String datasourceId, String tableName) {
        return tableMetadataReader.listColumns(datasourceId, tableName);
    }

    @Override
    public List<DataSourceCatalog.Option> listDataSources() {
        // 数据源组件缺席（未装配枚举端口）时降级为空清单，设计器仅展示平台主库
        DataSourceCatalog catalog = dataSourceCatalogProvider.getIfAvailable();
        return catalog == null ? List.of() : catalog.listEnabled();
    }

    private void replaceChildren(String formId, FormDefSaveCommand command) {
        formFieldMapper.delete(new LambdaQueryWrapper<FormFieldEntity>().eq(FormFieldEntity::getFormId, formId));
        formRelationMapper.delete(
                new LambdaQueryWrapper<FormRelationEntity>().eq(FormRelationEntity::getFormId, formId));
        for (FormFieldItem item : command.getFields()) {
            FormFieldEntity entity = new FormFieldEntity();
            entity.setFormId(formId);
            entity.setFieldName(item.getFieldName());
            entity.setLabel(item.getLabel());
            entity.setColumnName(item.getColumnName().toUpperCase(Locale.ROOT));
            entity.setFieldType(item.getFieldType().toUpperCase(Locale.ROOT));
            entity.setNullable(!Boolean.FALSE.equals(item.getNullable()));
            entity.setDefaultValue(item.getDefaultValue());
            entity.setDictType(item.getDictType());
            entity.setQueryConfig(item.getQueryConfig());
            entity.setListConfig(item.getListConfig());
            entity.setFormConfig(item.getFormConfig());
            entity.setPersisted(!Boolean.FALSE.equals(item.getPersisted()));
            // 从表归属标记：漏写会让发布快照丢掉主子表分组（从字段全按主表字段编译）
            entity.setSubFormKey(item.getSubFormKey());
            entity.setSort(item.getSort() == null ? Integer.valueOf(0) : item.getSort());
            formFieldMapper.insert(entity);
        }
        for (FormRelationItem item : command.getRelations()) {
            FormRelationEntity entity = new FormRelationEntity();
            entity.setFormId(formId);
            entity.setSubFormKey(item.getSubFormKey());
            // 子表直连物理表名：漏写会让编译期判定「子表物理表名非法或缺失」而无法发布
            entity.setSubTable(item.getSubTable());
            entity.setForeignKey(item.getForeignKey().toUpperCase(Locale.ROOT));
            entity.setCascadeStrategy(
                    item.getCascadeStrategy() == null ? FormConstants.CASCADE_DELETE : item.getCascadeStrategy());
            entity.setMode(item.getMode() == null ? FormConstants.RELATION_MODE_INLINE : item.getMode());
            entity.setSubTableConfig(item.getSubTableConfig());
            formRelationMapper.insert(entity);
        }
    }

    private void validate(FormDefSaveCommand command) {
        if ("design".equalsIgnoreCase(command.getFormKey())) {
            throw new FormBusinessException(FormErrorCode.FORM_KEY_RESERVED);
        }
        if (command.getInterceptor() != null
                && !command.getInterceptor().isBlank()
                && formDataInterceptors.resolve(command.getInterceptor()).isEmpty()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "拦截器未注册：" + command.getInterceptor());
        }
        if (!FormConstants.FORM_TYPES.contains(command.getFormType())) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "表单类型非法：" + command.getFormType());
        }
        if (!FormConstants.isIdentifier(command.getTableName())) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "表名非法：" + command.getTableName());
        }
        // 主表收口：目标表不得命中平台内部表排除集（置于元数据读取前，避免下游 isExcluded
        // 抛出泛化的「表不可映射」分不清是内部表保护还是标识非法）
        if (!formProperties.isMappableTable(command.getTableName())) {
            throw new FormBusinessException(
                    FormErrorCode.INVALID_PARAMETER, "主表不可映射（平台内部表受保护或标识非法）：" + command.getTableName());
        }
        if (FormConstants.FORM_TYPE_TREE.equals(command.getFormType())
                && (command.getTreeConfig() == null || command.getTreeConfig().isBlank())) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "TREE 类型必须配置 treeConfig");
        }
        validateTree(command);
        if (FormConstants.FORM_TYPE_MASTER_SUB.equals(command.getFormType())
                && command.getRelations().isEmpty()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "MASTER_SUB 类型必须配置主从关系");
        }
        validateFlatRelations(command);
        validateDetailConfig(command.getDetailConfig());
        // 列名唯一性与编译期 FormModelCompiler 同口径：按 (subFormKey, column) 复合键判定，
        // 主子表直连不同（或自关联同一）物理表时允许同名列；字段标识仍全局唯一
        long distinctNames = command.getFields().stream()
                .map(FormFieldItem::getFieldName)
                .distinct()
                .count();
        long distinctColumns = command.getFields().stream()
                .map(item -> (item.getSubFormKey() == null ? "" : item.getSubFormKey() + ":")
                        + item.getColumnName().toUpperCase(Locale.ROOT))
                .distinct()
                .count();
        if (distinctNames != command.getFields().size()
                || distinctColumns != command.getFields().size()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "字段标识或列名重复");
        }
        for (FormFieldItem item : command.getFields()) {
            String type = item.getFieldType().toUpperCase(Locale.ROOT);
            if (!FormConstants.FIELD_TYPES.contains(type)) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "字段类型非法：" + item.getFieldType());
            }
            if (!FormConstants.isIdentifier(item.getColumnName())) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "列名非法：" + item.getColumnName());
            }
            // 租户列结构性禁映射；字段名 id 会覆盖行标识，同样禁止。
            // 主键与审计列由引擎独占写入，仅允许映射为不落库的展示字段（主键值本就投影在行上）
            boolean persistedField = !Boolean.FALSE.equals(item.getPersisted());
            String mappedColumn = item.getColumnName().toUpperCase(Locale.ROOT);
            if (FormConstants.COLUMN_TENANT_ID.equals(mappedColumn) || "id".equalsIgnoreCase(item.getFieldName())) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "禁止映射系统字段：" + item.getColumnName());
            }
            boolean engineWritten =
                    FormConstants.COLUMN_ID.equals(mappedColumn) || FormConstants.AUDIT_COLUMNS.contains(mappedColumn);
            if (engineWritten && persistedField) {
                throw new FormBusinessException(
                        FormErrorCode.INVALID_PARAMETER, "列 " + item.getColumnName() + " 由引擎写入，仅可作为不落库的展示字段（落库=否）");
            }
            FieldConfigs.validate(objectMapper, item.getQueryConfig(), item.getListConfig(), item.getFormConfig());
        }
        validateColumnsExist(command);
        if (command.getLogicDeleteColumn() != null
                && !command.getLogicDeleteColumn().isBlank()) {
            if (!FormConstants.isIdentifier(command.getLogicDeleteColumn())) {
                throw new FormBusinessException(
                        FormErrorCode.INVALID_PARAMETER, "逻辑删除列非法：" + command.getLogicDeleteColumn());
            }
            String logicDelete = command.getLogicDeleteColumn();
            boolean mapped = command.getFields().stream()
                    .anyMatch(item -> item.getColumnName().equalsIgnoreCase(logicDelete));
            if (!mapped) {
                throw new FormBusinessException(
                        FormErrorCode.INVALID_PARAMETER, "逻辑删除列未映射字段：" + command.getLogicDeleteColumn());
            }
        }
        validateListConfig(command.getListConfig());
        var layout = FormLayouts.compile(
                FormLayouts.read(objectMapper, command.getFormLayout()),
                command.getFields().stream()
                        .sorted(Comparator.comparingInt(field -> field.getSort() == null ? 0 : field.getSort()))
                        .map(FormFieldItem::getFieldName)
                        .toList());
        command.setFormLayout(objectMapper.writeValueAsString(layout));
        validateRelations(command);
    }

    /**
     * 主从关系保存期校验：格式、自引用与重复之外，把运行时 {@code requireSubModel} 才暴露的错误
     * （子表单缺失、类型非单表、外键未映射）前置到草稿保存时拦截。
     */
    /**
     * 主从关系保存期校验：关系标识/级联策略/呈现方式格式与重复之外，把运行时错误前置到草稿保存——
     * 子表直连物理表（须在允许清单且存在）、外键列须是该关系从表字段中落库字段的映射列、
     * 子表列配置对照该关系的从字段标识；主表配置逻辑删除列时子表须含同名列（级联语义一致）。
     */
    private void validateRelations(FormDefSaveCommand command) {
        Set<String> subFormKeys = new HashSet<>();
        Map<String, Set<String>> subTableColumns = new HashMap<>();
        for (FormRelationItem item : command.getRelations()) {
            if (!FormConstants.CASCADE_DELETE.equals(item.getCascadeStrategy())
                    && !FormConstants.FORBID_DELETE.equals(item.getCascadeStrategy())) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "级联策略非法：" + item.getCascadeStrategy());
            }
            if (!FormConstants.isFormKey(item.getSubFormKey())
                    || !FormConstants.isIdentifier(item.getForeignKey())
                    || !FormConstants.isIdentifier(item.getSubTable())) {
                throw new FormBusinessException(
                        FormErrorCode.INVALID_PARAMETER, "主从关系标识非法：" + item.getSubFormKey() + "/" + item.getSubTable());
            }
            if (!subFormKeys.add(item.getSubFormKey())) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "子表关系标识重复：" + item.getSubFormKey());
            }
            // 子表直连物理表：不得命中平台内部表排除集
            if (!formProperties.isMappableTable(item.getSubTable())) {
                throw new FormBusinessException(
                        FormErrorCode.INVALID_PARAMETER, "子表不可映射（平台内部表受保护或标识非法）：" + item.getSubTable());
            }
            Set<String> physical = subTableColumns.computeIfAbsent(
                    item.getSubTable(),
                    table -> tableMetadataReader.listColumns(command.getDatasourceId(), table).stream()
                            .map(TableColumnView::getColumnName)
                            .map(name -> name.toUpperCase(Locale.ROOT))
                            .collect(Collectors.toSet()));
            if (physical.isEmpty()) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "子表不存在或无列：" + item.getSubTable());
            }
            // 主子表逻辑删除列同名一致性（级联删除/两段式读的活动行口径一致）
            if (StringUtils.hasText(command.getLogicDeleteColumn())
                    && !physical.contains(command.getLogicDeleteColumn().toUpperCase(Locale.ROOT))) {
                throw new FormBusinessException(
                        FormErrorCode.INVALID_PARAMETER,
                        "子表 " + item.getSubTable() + " 缺少逻辑删除列 " + command.getLogicDeleteColumn());
            }
            if (item.getMode() == null || item.getMode().isBlank()) {
                item.setMode(FormConstants.RELATION_MODE_INLINE);
            }
            if (!FormConstants.RELATION_MODES.contains(item.getMode())) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "主从呈现方式非法：" + item.getMode());
            }
            String foreignKey = item.getForeignKey().toUpperCase(Locale.ROOT);
            boolean foreignKeyMapped = command.getFields().stream()
                    .filter(field -> item.getSubFormKey().equals(field.getSubFormKey())
                            && !Boolean.FALSE.equals(field.getPersisted()))
                    .map(FormFieldItem::getColumnName)
                    .anyMatch(column -> foreignKey.equals(column.toUpperCase(Locale.ROOT)));
            if (!foreignKeyMapped) {
                throw new FormBusinessException(
                        FormErrorCode.INVALID_PARAMETER,
                        "子表外键未在该关系的从表字段中映射：" + item.getSubFormKey() + "/" + item.getForeignKey());
            }
            List<String> subTableColumnFields = SubTableConfigs.columnFields(objectMapper, item.getSubTableConfig());
            if (!subTableColumnFields.isEmpty()) {
                Set<String> subFieldNames = command.getFields().stream()
                        .filter(field -> item.getSubFormKey().equals(field.getSubFormKey())
                                && !Boolean.FALSE.equals(field.getPersisted()))
                        .map(FormFieldItem::getFieldName)
                        .collect(Collectors.toSet());
                for (String column : subTableColumnFields) {
                    if (!subFieldNames.contains(column)) {
                        throw new FormBusinessException(
                                FormErrorCode.INVALID_PARAMETER, "子表列表列未在该关系的从表字段中映射：" + column);
                    }
                }
            }
        }
    }

    /**
     * 从表字段归属保存期校验：标记字段须对应一条关系（三种模式的从表字段都内嵌主清单）；
     * 从表字段不支持查询条件与排序（它们的查询/排序 SQL 只落在主表上）。字段标识全局唯一由调用方校验兜住。
     */
    private void validateFlatRelations(FormDefSaveCommand command) {
        Set<String> relationKeys = command.getRelations().stream()
                .map(FormRelationItem::getSubFormKey)
                .collect(Collectors.toSet());
        Set<String> markedKeys = command.getFields().stream()
                .map(FormFieldItem::getSubFormKey)
                .filter(key -> key != null && !key.isBlank())
                .collect(Collectors.toSet());
        for (String key : markedKeys) {
            if (!relationKeys.contains(key)) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "从表字段标记无对应主从关系：" + key);
            }
        }
        for (FormFieldItem item : command.getFields()) {
            if (item.getSubFormKey() == null || item.getSubFormKey().isBlank()) {
                continue;
            }
            if (Boolean.TRUE.equals(FieldConfigs.toQueryConf(objectMapper, item.getQueryConfig())
                    .getShow())) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "从表字段不支持查询条件：" + item.getFieldName());
            }
        }
    }

    /**
     * 树配置保存期校验：SELF 的父列/节点列须已映射；FORM 的关联树表单须存在且为 TREE 类型（不得自引用）、
     * DICT 的字典编码必填；FORM/DICT 的节点字段须在本表映射且落库。结构细节由编译期统一校验。
     */
    private void validateTree(FormDefSaveCommand command) {
        if (!FormConstants.FORM_TYPE_TREE.equals(command.getFormType())
                || command.getTreeConfig() == null
                || command.getTreeConfig().isBlank()) {
            return;
        }
        final FormModel.TreeConfig tree;
        try {
            tree = objectMapper.readValue(command.getTreeConfig(), FormModel.TreeConfig.class);
        } catch (JacksonException e) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "treeConfig 不是合法 JSON");
        }
        String sourceType = tree.sourceType() == null || tree.sourceType().isBlank()
                ? FormConstants.TREE_SOURCE_SELF
                : tree.sourceType().toUpperCase(Locale.ROOT);
        if (!FormConstants.TREE_SOURCES.contains(sourceType)) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "树来源非法：" + tree.sourceType());
        }
        Set<String> mappedColumns = command.getFields().stream()
                .filter(item -> !Boolean.FALSE.equals(item.getPersisted()))
                .map(item -> item.getColumnName().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        switch (sourceType) {
            case FormConstants.TREE_SOURCE_FORM -> {
                if (!FormConstants.isFormKey(tree.treeFormKey())) {
                    throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "关联树表单标识非法或缺失");
                }
                if (tree.treeFormKey().equals(command.getFormKey())) {
                    throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "关联树表单不能是自身");
                }
                FormDefEntity treeDef = getBaseMapper()
                        .selectOne(new LambdaQueryWrapper<FormDefEntity>()
                                .eq(FormDefEntity::getFormKey, tree.treeFormKey()));
                if (treeDef == null) {
                    throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "关联树表单不存在：" + tree.treeFormKey());
                }
                if (!FormConstants.FORM_TYPE_TREE.equals(treeDef.getFormType())) {
                    throw new FormBusinessException(
                            FormErrorCode.INVALID_PARAMETER, "关联树表单须为左树右表类型：" + tree.treeFormKey());
                }
            }
            case FormConstants.TREE_SOURCE_DICT -> {
                if (tree.dictType() == null || tree.dictType().isBlank()) {
                    throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "字典树必须配置字典编码");
                }
            }
            default -> {
                if (tree.parentColumn() == null
                        || tree.parentColumn().isBlank()
                        || tree.nodeColumn() == null
                        || tree.nodeColumn().isBlank()) {
                    throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "自关联树缺少父列/节点展示列");
                }
                if (!mappedColumns.contains(tree.parentColumn().toUpperCase(Locale.ROOT))
                        || !mappedColumns.contains(tree.nodeColumn().toUpperCase(Locale.ROOT))) {
                    throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "自关联树父列/节点列未映射字段");
                }
            }
        }
        if (FormConstants.TREE_SOURCE_FORM.equals(sourceType) || FormConstants.TREE_SOURCE_DICT.equals(sourceType)) {
            if (tree.nodeIdField() == null || !FormConstants.isIdentifier(tree.nodeIdField())) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "树节点字段非法或缺失");
            }
            boolean nodeIdMapped = command.getFields().stream()
                    .filter(item -> !Boolean.FALSE.equals(item.getPersisted()))
                    .anyMatch(item -> item.getFieldName().equals(tree.nodeIdField()));
            if (!nodeIdMapped) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "树节点字段未映射或未落库：" + tree.nodeIdField());
            }
        }
    }

    /**
     * 落库字段的映射列必须真实存在于目标表（把发布/预览期目标表契约的「缺少列」前置到草稿保存）。
     * 手改列名或更换目标表后未重新导列都会在此拦截，错误信息给出可操作指引。
     */
    /** 落库字段的映射列须存在于各自的目标表：主表字段对照本表，FLAT 从表字段对照其子表单的目标表 */
    private void validateColumnsExist(FormDefSaveCommand command) {
        Set<String> physicalColumns =
                tableMetadataReader.listColumns(command.getDatasourceId(), command.getTableName()).stream()
                        .map(TableColumnView::getColumnName)
                        .map(column -> column.toUpperCase(Locale.ROOT))
                        .collect(Collectors.toSet());
        Map<String, Set<String>> subColumns = new HashMap<>();
        for (FormFieldItem item : command.getFields()) {
            if (Boolean.FALSE.equals(item.getPersisted())) {
                continue;
            }
            String subKey = item.getSubFormKey();
            String column = item.getColumnName().toUpperCase(Locale.ROOT);
            if (subKey == null || subKey.isBlank()) {
                if (!physicalColumns.contains(column)) {
                    throw new FormBusinessException(
                            FormErrorCode.INVALID_PARAMETER,
                            "字段 " + item.getFieldName() + " 映射的列 " + column + " 在目标表 " + command.getTableName()
                                    + " 中不存在，请修正列名或重新「选表导列」");
                }
                continue;
            }
            Set<String> subPhysical = subColumns.computeIfAbsent(subKey, key -> {
                FormRelationItem relation = command.getRelations().stream()
                        .filter(relationItem -> key.equals(relationItem.getSubFormKey()))
                        .findFirst()
                        .orElseThrow(() ->
                                new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "从表字段归属的关系不存在：" + key));
                return tableMetadataReader.listColumns(command.getDatasourceId(), relation.getSubTable()).stream()
                        .map(TableColumnView::getColumnName)
                        .map(name -> name.toUpperCase(Locale.ROOT))
                        .collect(Collectors.toSet());
            });
            if (!subPhysical.contains(column)) {
                throw new FormBusinessException(
                        FormErrorCode.INVALID_PARAMETER,
                        "从表字段 " + item.getFieldName() + " 映射的列 " + column + " 在关系 " + subKey + " 的目标子表中不存在，请修正列名或重新导列");
            }
        }
    }

    private FormDefEntity requireExists(String id) {
        FormDefEntity def = getById(id);
        if (def == null) {
            throw new FormBusinessException(FormErrorCode.FORM_NOT_FOUND, id);
        }
        return def;
    }

    /** 数据源 id 归一：空白视为 null（=平台主库） */
    private static String normalizeDatasource(String datasourceId) {
        return datasourceId == null || datasourceId.isBlank() ? null : datasourceId;
    }

    private void requireKeyFree(String formKey, String excludeId) {
        FormDefEntity existing = getBaseMapper()
                .selectOne(new LambdaQueryWrapper<FormDefEntity>().eq(FormDefEntity::getFormKey, formKey));
        if (existing != null && !existing.getId().equals(excludeId)) {
            throw new FormBusinessException(FormErrorCode.FORM_KEY_DUPLICATED, formKey);
        }
    }

    private void apply(FormDefEntity def, FormDefSaveCommand command) {
        def.setFormKey(command.getFormKey());
        def.setFormName(command.getFormName());
        def.setFormType(command.getFormType());
        def.setTableName(command.getTableName());
        def.setDatasourceId(normalizeDatasource(command.getDatasourceId()));
        def.setTreeConfig(command.getTreeConfig());
        def.setProcessForm(command.getProcessForm());
        def.setTitleTemplate(command.getTitleTemplate());
        def.setInterceptor(command.getInterceptor());
        def.setPublishOnSave(command.getPublishOnSave());
        def.setLogicDeleteColumn(command.getLogicDeleteColumn());
        def.setListConfig(command.getListConfig());
        def.setDetailConfig(command.getDetailConfig());
        def.setFormLayout(command.getFormLayout());
    }

    /** 详情呈现配置 JSON 预校验：保存期即拒绝非法 JSON，不拖到发布期 */
    private void validateDetailConfig(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new FormBusinessException(
                    FormErrorCode.INVALID_PARAMETER, "detailConfig 不是合法 JSON：" + e.getMessage());
        }
    }

    /** 列表级配置 JSON 的顶层键白名单校验 */
    private void validateListConfig(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        final JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "listConfig 不是合法 JSON");
        }
        if (!node.isObject()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "listConfig 必须是 JSON 对象");
        }
        Set<String> allowed = Set.of("layout", "pageSizes", "advancedQuery", "fullQuery", "showSeq", "buttons");
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "listConfig 存在非法键：" + entry.getKey());
            }
        }
    }
}
