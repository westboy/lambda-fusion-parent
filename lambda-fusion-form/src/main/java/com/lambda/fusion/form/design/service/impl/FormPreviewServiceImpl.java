package com.lambda.fusion.form.design.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.design.assembler.FormSnapshotAssembler;
import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.entity.FormDefVersionEntity;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.design.mapper.FormDefVersionMapper;
import com.lambda.fusion.form.design.model.FormDefSaveCommand;
import com.lambda.fusion.form.design.model.FormDefView;
import com.lambda.fusion.form.design.model.FormPreviewSource;
import com.lambda.fusion.form.design.service.FormDesignService;
import com.lambda.fusion.form.design.service.FormPreviewService;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.assembler.FormSchemaAssembler;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import com.lambda.fusion.form.runtime.model.FormSchemaView;
import com.lambda.fusion.form.runtime.service.DynamicDataService;
import com.lambda.fusion.form.runtime.support.FormAction;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModelCompiler;
import com.lambda.fusion.form.runtime.support.SnapshotJson;
import com.lambda.fusion.form.runtime.support.TargetTableContract;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class FormPreviewServiceImpl implements FormPreviewService {
    private final FormDesignService designService;
    private final FormDefVersionMapper versionMapper;
    private final FormSnapshotAssembler snapshotAssembler;
    private final FormModelCompiler compiler;
    private final FormSchemaAssembler schemaAssembler;
    private final SnapshotJson json;
    private final ObjectMapper objectMapper;
    private final FormDefMapper definitions;
    private final DynamicDataService dataService;
    private final TargetTableContract tableContract;

    @Override
    @Transactional(readOnly = true)
    public FormSchemaView preview(FormPreviewSource source) {
        ResolvedPreview resolved = resolve(source, false);
        FormSchemaView schema = schemaAssembler.assemble(resolved.model());
        schema.setDraftVersion(resolved.draftVersion());
        for (FormAction action : FormAction.values()) {
            schema.getPermissions()
                    .put(
                            action.getCode(),
                            resolved.enabled()
                                    && AuthUtils.hasPermission(
                                            action.permission(resolved.model().formKey())));
        }
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public FormSchemaView previewDraft(String id) {
        return preview(new FormPreviewSource(id, null, null, null));
    }

    @Override
    @Transactional(readOnly = true)
    public FormSchemaView previewVersion(String id, long version) {
        return preview(new FormPreviewSource(id, version, null, null));
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<Map<String, Object>> page(FormPreviewSource source, FormDataPageQuery query) {
        return dataService.page(dataModel(source, FormAction.PAGE), query == null ? new FormDataPageQuery() : query);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> tree(FormPreviewSource source, String parentValue) {
        return dataService.tree(dataModel(source, FormAction.PAGE), parentValue);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> detail(FormPreviewSource source, String id) {
        return dataService.getById(dataModel(source, FormAction.PAGE), id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String create(FormPreviewSource source, Map<String, Object> data) {
        return dataService.create(dataModel(source, FormAction.ADD), data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(FormPreviewSource source, String id, Map<String, Object> data) {
        dataService.update(dataModel(source, FormAction.EDIT), id, data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(FormPreviewSource source, String id) {
        dataService.delete(dataModel(source, FormAction.DELETE), id);
    }

    private FormModel dataModel(FormPreviewSource source, FormAction action) {
        ResolvedPreview resolved = resolve(source, true);
        if (!resolved.enabled()) throw new FormBusinessException(FormErrorCode.FORM_DISABLED);
        FormModel model = resolved.model();
        // 请求体决定模型，权限必须在服务端解析出真实 formKey 后校验，沿用统一开发者豁免。
        AuthUtils.checkPermission(action.permission(model.formKey()));
        // 读取同样检查物理映射和允许清单，不能把任意客户端设计直接拼进查询。
        tableContract.validate(model);
        return model;
    }

    private ResolvedPreview resolve(FormPreviewSource source, boolean dataAccess) {
        if (source == null
                || source.design() != null && (source.version() != null || source.draftVersion() != null)
                || source.version() != null && source.draftVersion() != null) {
            throw invalidSource();
        }
        boolean identified = StringUtils.hasText(source.formId());
        if (source.design() != null) {
            // 当前编辑内容可改变业务列映射，只允许具有相应设计权限的用户提交。
            AuthUtils.checkPermission(identified ? "form:design:edit" : "form:design:add");
        }
        FormDefView owner = identified ? designService.detail(source.formId()) : null;
        FormModel model;
        Long revision = null;
        if (source.design() != null) {
            FormDefSaveCommand design = source.design();
            if (owner != null) {
                checkRevision(design.getVersion(), owner.getVersion());
            } else if (definitions.selectCount(
                            new LambdaQueryWrapper<FormDefEntity>().eq(FormDefEntity::getFormKey, design.getFormKey()))
                    > 0) {
                throw new FormBusinessException(FormErrorCode.FORM_KEY_DUPLICATED);
            }
            model = compiler.compile(json.write(snapshotAssembler.fromCommand(design, objectMapper)), 0);
        } else {
            if (owner == null) throw invalidSource();
            if (source.version() != null) {
                FormDefVersionEntity snapshot = versionMapper.selectOne(new LambdaQueryWrapper<FormDefVersionEntity>()
                        .eq(FormDefVersionEntity::getFormId, source.formId())
                        .eq(FormDefVersionEntity::getVersion, source.version()));
                if (snapshot == null) throw new FormBusinessException(FormErrorCode.FORM_VERSION_NOT_FOUND);
                model = compiler.compile(snapshot.getSnapshotJson(), snapshot.getVersion());
            } else {
                if (dataAccess) checkRevision(source.draftVersion(), owner.getVersion());
                revision = owner.getVersion();
                model = compiler.compile(json.write(snapshotAssembler.fromView(owner, objectMapper)), 0);
            }
        }
        if (owner != null
                && (!Objects.equals(owner.getFormKey(), model.formKey())
                        || !Objects.equals(owner.getTableName(), model.tableName())
                        || !Objects.equals(owner.getFormType(), model.formType()))) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "预览不能改变已有表单的标识、目标表或类型");
        }
        return new ResolvedPreview(
                model, revision, owner == null || !FormConstants.STATUS_DISABLED.equals(owner.getStatus()));
    }

    private void checkRevision(Long submitted, Long current) {
        if (submitted == null || !submitted.equals(current)) {
            throw new FormBusinessException(FormErrorCode.FORM_DRAFT_CONFLICT);
        }
    }

    private FormBusinessException invalidSource() {
        return new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "必须提供唯一的预览来源：草稿、历史版本或当前设计");
    }

    private record ResolvedPreview(FormModel model, Long draftVersion, boolean enabled) {}
}
