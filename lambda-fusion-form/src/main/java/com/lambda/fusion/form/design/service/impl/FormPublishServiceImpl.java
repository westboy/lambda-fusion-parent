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
        targetTableContract.validate(modelCompiler.compile(snapshotJson, nextVersion));
        FormDefVersionEntity version = new FormDefVersionEntity();
        version.setFormId(id);
        version.setVersion(nextVersion);
        version.setSnapshotJson(snapshotJson);
        version.setContentMd5(md5);
        version.setPublishedBy(AuthUtils.getSafeUsername());
        version.setPublishedAt(LocalDateTime.now());
        def.setPublishedVersion(nextVersion);
        def.setStatus(FormConstants.STATUS_PUBLISHED);
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
        if (formDefMapper.updateById(def) == 0) {
            throw new FormBusinessException(FormErrorCode.FORM_DRAFT_CONFLICT);
        }
        modelProvider.evictAfterCommit(def.getId());
        log.info("表单已停用：formKey={}", def.getFormKey());
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
}
