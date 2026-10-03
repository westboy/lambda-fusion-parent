package com.lambda.fusion.form.design.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.cloud.core.shared.BaseVO;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.core.service.AbstractCrudService;
import com.lambda.fusion.form.FormConstants;
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
import com.lambda.fusion.form.design.support.TableMetadataReader;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.support.FieldConfigs;
import com.lambda.fusion.form.runtime.support.FormLayouts;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
                || !Objects.equals(def.getTableName(), command.getTableName())) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "表单标识、类型和目标表创建后不可修改");
        }
        apply(def, command);
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
    }

    @Override
    public List<String> listTables() {
        return tableMetadataReader.listTables();
    }

    @Override
    public List<TableColumnView> listColumns(String tableName) {
        return tableMetadataReader.listColumns(tableName);
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
            entity.setSort(item.getSort() == null ? Integer.valueOf(0) : item.getSort());
            formFieldMapper.insert(entity);
        }
        for (FormRelationItem item : command.getRelations()) {
            FormRelationEntity entity = new FormRelationEntity();
            entity.setFormId(formId);
            entity.setSubFormKey(item.getSubFormKey());
            entity.setForeignKey(item.getForeignKey().toUpperCase(Locale.ROOT));
            entity.setCascadeStrategy(
                    item.getCascadeStrategy() == null ? FormConstants.CASCADE_DELETE : item.getCascadeStrategy());
            formRelationMapper.insert(entity);
        }
    }

    private void validate(FormDefSaveCommand command) {
        if ("design".equalsIgnoreCase(command.getFormKey())) {
            throw new FormBusinessException(FormErrorCode.FORM_KEY_RESERVED);
        }
        if (!FormConstants.FORM_TYPES.contains(command.getFormType())) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "表单类型非法：" + command.getFormType());
        }
        if (!FormConstants.isIdentifier(command.getTableName())) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "表名非法：" + command.getTableName());
        }
        if (tableMetadataReader.isExcluded(command.getTableName())) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "表未开放动态读写：" + command.getTableName());
        }
        if (FormConstants.FORM_TYPE_TREE.equals(command.getFormType())
                && (command.getTreeConfig() == null || command.getTreeConfig().isBlank())) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "TREE 类型必须配置 treeConfig");
        }
        if (FormConstants.FORM_TYPE_MASTER_SUB.equals(command.getFormType())
                && command.getRelations().isEmpty()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "MASTER_SUB 类型必须配置主从关系");
        }
        long distinctNames = command.getFields().stream()
                .map(FormFieldItem::getFieldName)
                .distinct()
                .count();
        long distinctColumns = command.getFields().stream()
                .map(item -> item.getColumnName().toUpperCase(Locale.ROOT))
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
            if (FormConstants.isSystemColumn(item.getColumnName()) || "id".equalsIgnoreCase(item.getFieldName())) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "禁止映射系统字段：" + item.getColumnName());
            }
            FieldConfigs.validate(objectMapper, item.getQueryConfig(), item.getListConfig(), item.getFormConfig());
        }
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
    private void validateRelations(FormDefSaveCommand command) {
        Set<String> subFormKeys = new HashSet<>();
        for (FormRelationItem item : command.getRelations()) {
            if (!FormConstants.CASCADE_DELETE.equals(item.getCascadeStrategy())
                    && !FormConstants.FORBID_DELETE.equals(item.getCascadeStrategy())) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "级联策略非法：" + item.getCascadeStrategy());
            }
            if (!FormConstants.isIdentifier(item.getSubFormKey())
                    || !FormConstants.isIdentifier(item.getForeignKey())) {
                throw new FormBusinessException(
                        FormErrorCode.INVALID_PARAMETER,
                        "主从关系标识非法：" + item.getSubFormKey() + "/" + item.getForeignKey());
            }
            if (item.getSubFormKey().equals(command.getFormKey())) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "子表单不能引用自身：" + item.getSubFormKey());
            }
            if (!subFormKeys.add(item.getSubFormKey())) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "子表单关系重复：" + item.getSubFormKey());
            }
            FormDefEntity subDef = getBaseMapper()
                    .selectOne(new LambdaQueryWrapper<FormDefEntity>()
                            .eq(FormDefEntity::getFormKey, item.getSubFormKey()));
            if (subDef == null) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "子表单不存在：" + item.getSubFormKey());
            }
            if (!FormConstants.FORM_TYPE_SINGLE.equals(subDef.getFormType())) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "子表单须为单表类型：" + item.getSubFormKey());
            }
            String foreignKey = item.getForeignKey().toUpperCase(Locale.ROOT);
            boolean mapped = formFieldMapper
                    .selectList(
                            new LambdaQueryWrapper<FormFieldEntity>().eq(FormFieldEntity::getFormId, subDef.getId()))
                    .stream()
                    .filter(field -> !Boolean.FALSE.equals(field.getPersisted()))
                    .map(FormFieldEntity::getColumnName)
                    .anyMatch(column -> foreignKey.equals(column.toUpperCase(Locale.ROOT)));
            if (!mapped) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "子表外键未在子表单映射：" + item.getForeignKey());
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
        def.setTreeConfig(command.getTreeConfig());
        def.setFlowCode(command.getFlowCode());
        def.setTitleTemplate(command.getTitleTemplate());
        def.setPublishOnSave(command.getPublishOnSave());
        def.setLogicDeleteColumn(command.getLogicDeleteColumn());
        def.setListConfig(command.getListConfig());
        def.setFormLayout(command.getFormLayout());
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
