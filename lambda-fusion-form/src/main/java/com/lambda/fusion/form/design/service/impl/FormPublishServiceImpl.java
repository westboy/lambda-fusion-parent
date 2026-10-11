package com.lambda.fusion.form.design.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.cloud.core.shared.BaseVO;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.design.assembler.FormSnapshotAssembler;
import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.entity.FormDefVersionEntity;
import com.lambda.fusion.form.design.entity.FormFieldEntity;
import com.lambda.fusion.form.design.entity.FormRelationEntity;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.design.mapper.FormDefVersionMapper;
import com.lambda.fusion.form.design.mapper.FormFieldMapper;
import com.lambda.fusion.form.design.mapper.FormRelationMapper;
import com.lambda.fusion.form.design.model.FormVersionView;
import com.lambda.fusion.form.design.service.FormPublishService;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModel.FormField;
import com.lambda.fusion.form.runtime.support.FormModelCompiler;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.form.runtime.support.SnapshotJson;
import com.lambda.fusion.form.runtime.support.TargetTableContract;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * 表单发布服务实现
 *
 * <p>发布 = 去重与编译校验 → 乐观锁竞争 published_version 指针 → 写不可变快照，同事务提交；
 * 回滚 = 指针回指旧快照；停用 = 置 DISABLED。三者均在事务提交后清除本节点缓存
 * 运行入口逐次核对数据库状态与生效版本，缓存只复用不可变快照的编译结果。
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
    private final TargetTableContract targetTableContract;
    private final FormModelProvider modelProvider;
    private final SnapshotJson snapshotJson;
    private final FormSnapshotAssembler snapshotAssembler;
    private final ObjectMapper objectMapper;
    private final FormModelCompiler modelCompiler;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(String id) {
        AuthUtils.checkPermission("form:design:publish");
        FormDefEntity def = requireExists(id);
        List<FormFieldEntity> fields = fieldsOf(id);
        List<FormRelationEntity> relations = relationsOf(id);
        String snapshotJson = this.snapshotJson.write(snapshotAssembler.assemble(def, fields, relations, objectMapper));
        String md5 = DigestUtils.md5DigestAsHex(snapshotJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (def.getPublishedVersion() != null) {
            FormDefVersionEntity current = versionOf(id, def.getPublishedVersion());
            if (current != null && md5.equals(current.getContentMd5())) {
                throw new FormBusinessException(FormErrorCode.FORM_VERSION_UNCHANGED);
            }
        }
        long nextVersion = nextVersion(id);
        var compiledModel = modelCompiler.compile(snapshotJson, nextVersion);
        targetTableContract.validate(compiledModel);
        // 唯一字段必须有数据库唯一索引兜底（应用层查重挡不住并发窗口）
        targetTableContract.validateUniqueIndexes(compiledModel);
        // 外键/树父列是运行时高频过滤列：最左前缀索引缺失时拒绝发布（防读路径全表扫描）
        targetTableContract.validateRelationIndexes(compiledModel);
        // 关联字段：目标表单必须已发布且回显字段可用（禁自引用；未发布目标先发布它再回来）
        validateLookupFields(compiledModel);
        FormDefVersionEntity version = new FormDefVersionEntity();
        version.setFormId(id);
        version.setVersion(nextVersion);
        version.setSnapshotJson(snapshotJson);
        version.setContentMd5(md5);
        version.setPublishedBy(AuthUtils.getSafeUsername());
        version.setPublishedAt(LocalDateTime.now());
        def.setPublishedVersion(nextVersion);
        def.setStatus(FormConstants.STATUS_PUBLISHED);
        freshenAuditStamp(def);
        if (formDefMapper.updateById(def) == 0) {
            // @Version 冲突：草稿在读取后被并发修改，快照随事务回滚
            throw new FormBusinessException(FormErrorCode.FORM_DRAFT_CONFLICT);
        }
        // 先以草稿版本竞争生效指针，再写快照；并发失败者不会先触发版本唯一键异常。
        formDefVersionMapper.insert(version);
        modelProvider.evictAfterCommit(def.getId());
        log.info("表单发布成功：formKey={} version={}", def.getFormKey(), nextVersion);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disable(String id) {
        AuthUtils.checkPermission("form:design:publish");
        FormDefEntity def = requireExists(id);
        if (FormConstants.STATUS_DISABLED.equals(def.getStatus())) {
            return;
        }
        def.setStatus(FormConstants.STATUS_DISABLED);
        freshenAuditStamp(def);
        if (formDefMapper.updateById(def) == 0) {
            throw new FormBusinessException(FormErrorCode.FORM_DRAFT_CONFLICT);
        }
        modelProvider.evictAfterCommit(def.getId());
        log.info("表单已停用：formKey={}", def.getFormKey());
    }

    /** 关联字段发布校验：禁自引用；目标表单已发布（require 兜底未发布/停用/缺失）；回显字段为主表落库且画布可见 */
    private void validateLookupFields(FormModel model) {
        for (FormField field : model.fields()) {
            if (field.lookup() == null) {
                continue;
            }
            String target = field.lookup().targetFormKey();
            if (target.equals(model.formKey())) {
                throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "关联字段不能引用自身表单：" + field.name());
            }
            FormModel targetModel;
            try {
                targetModel = modelProvider.require(target);
            } catch (FormBusinessException error) {
                throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "关联字段的目标表单不可用：" + target);
            }
            FormField display = targetModel.fields().stream()
                    .filter(candidate -> candidate.name().equals(field.lookup().displayField()))
                    .findFirst()
                    .orElse(null);
            if (display == null
                    || !display.persisted()
                    || display.subFormKey() != null
                    || !display.form().show()) {
                throw new FormBusinessException(
                        FormErrorCode.FORM_TABLE_INVALID,
                        "关联回显字段在目标表单不可用（须为主表落库且画布可见字段）：" + field.lookup().displayField());
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollback(String id, Long version) {
        AuthUtils.checkPermission("form:design:publish");
        FormDefEntity def = requireExists(id);
        FormDefVersionEntity snapshot = versionOf(id, version);
        if (snapshot == null) {
            throw new FormBusinessException(FormErrorCode.FORM_VERSION_NOT_FOUND, version);
        }
        targetTableContract.validate(modelCompiler.compile(snapshot.getSnapshotJson(), snapshot.getVersion()));
        def.setPublishedVersion(version);
        def.setStatus(FormConstants.STATUS_PUBLISHED);
        freshenAuditStamp(def);
        if (formDefMapper.updateById(def) == 0) {
            throw new FormBusinessException(FormErrorCode.FORM_DRAFT_CONFLICT);
        }
        modelProvider.evictAfterCommit(def.getId());
        log.info("表单回滚成功：formKey={} version={}", def.getFormKey(), version);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enable(String id) {
        AuthUtils.checkPermission("form:design:publish");
        FormDefEntity def = requireExists(id);
        if (def.getPublishedVersion() == null) {
            throw new FormBusinessException(FormErrorCode.FORM_NOT_PUBLISHED);
        }
        FormDefVersionEntity snapshot = versionOf(id, def.getPublishedVersion());
        if (snapshot == null) {
            throw new FormBusinessException(FormErrorCode.FORM_VERSION_NOT_FOUND);
        }
        targetTableContract.validate(modelCompiler.compile(snapshot.getSnapshotJson(), snapshot.getVersion()));
        if (FormConstants.STATUS_PUBLISHED.equals(def.getStatus())) {
            return;
        }
        def.setStatus(FormConstants.STATUS_PUBLISHED);
        freshenAuditStamp(def);
        if (formDefMapper.updateById(def) == 0) {
            throw new FormBusinessException(FormErrorCode.FORM_DRAFT_CONFLICT);
        }
        modelProvider.evictAfterCommit(id);
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

    /** 审计字段交由 updateFill 填充：清空载入值，避免 strictUpdateFill 跳过导致更新时间停在旧值 */
    private static void freshenAuditStamp(FormDefEntity def) {
        def.setUpdatedAt(null);
        def.setUpdatedBy(null);
    }
}
