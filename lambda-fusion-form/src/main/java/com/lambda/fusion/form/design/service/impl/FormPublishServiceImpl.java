package com.lambda.fusion.form.design.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.cloud.core.shared.BaseVO;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.entity.FormDefVersionEntity;
import com.lambda.fusion.form.design.entity.FormFieldEntity;
import com.lambda.fusion.form.design.entity.FormRelationEntity;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.design.mapper.FormDefVersionMapper;
import com.lambda.fusion.form.design.mapper.FormFieldMapper;
import com.lambda.fusion.form.design.mapper.FormRelationMapper;
import com.lambda.fusion.form.design.model.FormVersionView;
import com.lambda.fusion.form.design.model.TableColumnView;
import com.lambda.fusion.form.design.service.FormPublishService;
import com.lambda.fusion.form.design.support.TableMetadataReader;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.support.FieldConfigs;
import com.lambda.fusion.form.runtime.support.FormDefSnapshot;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.form.runtime.support.SnapshotJson;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * 表单发布服务实现
 *
 * <p>发布 = 发布校验 → 写不可变快照（MD5 去重）→ 拨 published_version 指针；
 * 回滚 = 指针回指旧快照；停用 = 置 DISABLED。三者均在事务提交后清除本节点缓存
 * （跨节点失效由 P2 的广播补齐，当前以 TTL 降级兜底）。
 *
 * @author Zx
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class FormPublishServiceImpl implements FormPublishService {

    private final FormDefMapper formDefMapper;
    private final FormFieldMapper formFieldMapper;
    private final FormRelationMapper formRelationMapper;
    private final FormDefVersionMapper formDefVersionMapper;
    private final TableMetadataReader tableMetadataReader;
    private final FormModelProvider modelProvider;
    private final SnapshotJson snapshotJson;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(String id) {
        FormDefEntity def = requireExists(id);
        List<FormFieldEntity> fields = fieldsOf(id);
        List<FormRelationEntity> relations = relationsOf(id);
        validateTargetTable(def, fields);
        String snapshotJson = this.snapshotJson.write(toSnapshot(def, fields, relations));
        String md5 = DigestUtils.md5DigestAsHex(snapshotJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (def.getPublishedVersion() != null) {
            FormDefVersionEntity current = versionOf(id, def.getPublishedVersion());
            if (current != null && md5.equals(current.getContentMd5())) {
                throw new FormBusinessException(FormErrorCode.FORM_VERSION_UNCHANGED);
            }
        }
        long nextVersion = nextVersion(id);
        FormDefVersionEntity version = new FormDefVersionEntity();
        version.setFormId(id);
        version.setVersion(nextVersion);
        version.setSnapshotJson(snapshotJson);
        version.setContentMd5(md5);
        version.setPublishedBy(AuthUtils.getSafeUsername());
        version.setPublishedAt(LocalDateTime.now());
        formDefVersionMapper.insert(version);

        def.setPublishedVersion(nextVersion);
        def.setStatus(FormConstants.STATUS_PUBLISHED);
        if (formDefMapper.updateById(def) == 0) {
            // @Version 冲突：草稿在读取后被并发修改，快照随事务回滚
            throw new FormBusinessException(FormErrorCode.FORM_DRAFT_CONFLICT);
        }
        modelProvider.evict(def.getFormKey());
        log.info("表单发布成功：formKey={} version={}", def.getFormKey(), nextVersion);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disable(String id) {
        FormDefEntity def = requireExists(id);
        if (FormConstants.STATUS_DISABLED.equals(def.getStatus())) {
            return;
        }
        def.setStatus(FormConstants.STATUS_DISABLED);
        if (formDefMapper.updateById(def) == 0) {
            throw new FormBusinessException(FormErrorCode.FORM_DRAFT_CONFLICT);
        }
        modelProvider.evict(def.getFormKey());
        log.info("表单已停用：formKey={}", def.getFormKey());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollback(String id, Long version) {
        FormDefEntity def = requireExists(id);
        FormDefVersionEntity snapshot = versionOf(id, version);
        if (snapshot == null) {
            throw new FormBusinessException(FormErrorCode.FORM_VERSION_NOT_FOUND, version);
        }
        def.setPublishedVersion(version);
        def.setStatus(FormConstants.STATUS_PUBLISHED);
        if (formDefMapper.updateById(def) == 0) {
            throw new FormBusinessException(FormErrorCode.FORM_DRAFT_CONFLICT);
        }
        modelProvider.evict(def.getFormKey());
        log.info("表单回滚成功：formKey={} version={}", def.getFormKey(), version);
    }

    @Override
    public List<FormVersionView> versions(String id) {
        FormDefEntity def = requireExists(id);
        List<FormVersionView> views = BaseVO.fromEntityList(
                FormVersionView.class,
                formDefVersionMapper.selectList(new LambdaQueryWrapper<FormDefVersionEntity>()
                        .eq(FormDefVersionEntity::getFormId, id)
                        .orderByDesc(FormDefVersionEntity::getVersion)));
        views.forEach(view -> view.setCurrent(view.getVersion().equals(def.getPublishedVersion())));
        return views;
    }

    /**
     * 发布校验（设计文档 §5.5）：表存在、ID/租户/审计列齐备、字段列存在且类型兼容
     */
    private void validateTargetTable(FormDefEntity def, List<FormFieldEntity> fields) {
        String tableName = def.getTableName();
        if (tableMetadataReader.isExcluded(tableName) || !tableMetadataReader.tableExists(tableName)) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "表不存在或不可映射：" + tableName);
        }
        Map<String, TableColumnView> columns = tableMetadataReader.listColumns(tableName).stream()
                .collect(Collectors.toMap(TableColumnView::getColumnName, Function.identity()));
        List<String> missing = new ArrayList<>();
        if (!columns.containsKey(FormConstants.COLUMN_ID)) {
            missing.add(FormConstants.COLUMN_ID);
        }
        if (!columns.containsKey(FormConstants.COLUMN_TENANT_ID)) {
            missing.add(FormConstants.COLUMN_TENANT_ID);
        }
        FormConstants.AUDIT_COLUMNS.stream()
                .filter(column -> !columns.containsKey(column))
                .forEach(missing::add);
        for (FormFieldEntity field : fields) {
            TableColumnView column = columns.get(field.getColumnName().toUpperCase(Locale.ROOT));
            if (column == null) {
                missing.add(field.getColumnName());
                continue;
            }
            if (!compatible(field.getFieldType(), column.getSuggestedFieldType())) {
                throw new FormBusinessException(
                        FormErrorCode.FORM_TABLE_INVALID,
                        "字段类型不兼容：" + field.getColumnName() + " 列类型 " + column.getDbType());
            }
        }
        if (!missing.isEmpty()) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "缺少列：" + String.join(",", missing));
        }
    }

    /**
     * 字段类型与列类型兼容判断（INT↔DECIMAL 放宽，其余按建议类型比对）
     */
    private boolean compatible(String fieldType, String suggestedType) {
        if (fieldType == null) {
            return false;
        }
        Set<String> numeric = Set.of("INT", "DECIMAL");
        if (numeric.contains(fieldType) && numeric.contains(suggestedType)) {
            return true;
        }
        if ("TEXT".equals(fieldType) && "STRING".equals(suggestedType)) {
            return true;
        }
        if ("DICT".equals(fieldType) || "FILE".equals(fieldType)) {
            return "STRING".equals(suggestedType) || "TEXT".equals(suggestedType) || "INT".equals(suggestedType);
        }
        return fieldType.equals(suggestedType);
    }

    private FormDefSnapshot toSnapshot(
            FormDefEntity def, List<FormFieldEntity> fields, List<FormRelationEntity> relations) {
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey(def.getFormKey());
        snapshot.setFormName(def.getFormName());
        snapshot.setFormType(def.getFormType());
        snapshot.setTableName(def.getTableName());
        snapshot.setTreeConfig(def.getTreeConfig());
        snapshot.setFlowCode(def.getFlowCode());
        snapshot.setTitleTemplate(def.getTitleTemplate());
        snapshot.setInterceptor(def.getInterceptor());
        snapshot.setPublishOnSave(def.getPublishOnSave());
        snapshot.setLogicDeleteColumn(def.getLogicDeleteColumn());
        snapshot.setListConfig(FieldConfigs.toListConfig(objectMapper, def.getListConfig()));
        snapshot.setFields(fields.stream()
                .map(field -> {
                    FormDefSnapshot.Field item = new FormDefSnapshot.Field();
                    item.setFieldName(field.getFieldName());
                    item.setColumnName(field.getColumnName());
                    item.setFieldType(field.getFieldType());
                    item.setNullable(!Boolean.FALSE.equals(field.getNullable()));
                    item.setDefaultValue(field.getDefaultValue());
                    item.setDictType(field.getDictType());
                    item.setPersisted(!Boolean.FALSE.equals(field.getPersisted()));
                    item.setQuery(FieldConfigs.toQueryConf(objectMapper, field.getQueryConfig()));
                    item.setList(FieldConfigs.toListConf(objectMapper, field.getListConfig()));
                    item.setForm(FieldConfigs.toFormConf(objectMapper, field.getFormConfig()));
                    item.setSort(field.getSort());
                    return item;
                })
                .toList());
        snapshot.setRelations(relations.stream()
                .map(relation -> {
                    FormDefSnapshot.Relation item = new FormDefSnapshot.Relation();
                    item.setSubFormKey(relation.getSubFormKey());
                    item.setForeignKey(relation.getForeignKey());
                    item.setCascadeStrategy(relation.getCascadeStrategy());
                    return item;
                })
                .toList());
        return snapshot;
    }

    private long nextVersion(String formId) {
        FormDefVersionEntity latest = formDefVersionMapper.selectOne(new LambdaQueryWrapper<FormDefVersionEntity>()
                .eq(FormDefVersionEntity::getFormId, formId)
                .orderByDesc(FormDefVersionEntity::getVersion)
                .last("LIMIT 1"));
        return latest == null ? 1L : latest.getVersion() + 1;
    }

    private FormDefVersionEntity versionOf(String formId, Long version) {
        if (version == null) {
            return null;
        }
        return formDefVersionMapper.selectOne(new LambdaQueryWrapper<FormDefVersionEntity>()
                .eq(FormDefVersionEntity::getFormId, formId)
                .eq(FormDefVersionEntity::getVersion, version));
    }

    private List<FormFieldEntity> fieldsOf(String formId) {
        return formFieldMapper.selectList(new LambdaQueryWrapper<FormFieldEntity>()
                .eq(FormFieldEntity::getFormId, formId)
                .orderByAsc(FormFieldEntity::getSort));
    }

    private List<FormRelationEntity> relationsOf(String formId) {
        return formRelationMapper.selectList(
                new LambdaQueryWrapper<FormRelationEntity>().eq(FormRelationEntity::getFormId, formId));
    }

    private FormDefEntity requireExists(String id) {
        FormDefEntity def = formDefMapper.selectById(id);
        if (def == null) {
            throw new FormBusinessException(FormErrorCode.FORM_NOT_FOUND, id);
        }
        return def;
    }
}
