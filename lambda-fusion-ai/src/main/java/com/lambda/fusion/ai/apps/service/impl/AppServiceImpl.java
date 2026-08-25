package com.lambda.fusion.ai.apps.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.fusion.ai.AiConstants.AppAudience;
import com.lambda.fusion.ai.AiConstants.AppType;
import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiConstants.RagMode;
import com.lambda.fusion.ai.AiConstants.SandboxBackend;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.mapper.AppConfigAuditMapper;
import com.lambda.fusion.ai.apps.mapper.AppMapper;
import com.lambda.fusion.ai.apps.model.AppPageQuery;
import com.lambda.fusion.ai.apps.model.AvailableApp;
import com.lambda.fusion.ai.apps.model.CreateApp;
import com.lambda.fusion.ai.apps.model.MediaGenerationCapability;
import com.lambda.fusion.ai.apps.model.UpdateApp;
import com.lambda.fusion.ai.apps.model.entity.AppConfigAuditEntity;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.apps.service.AppService;
import com.lambda.fusion.ai.apps.service.MediaGenerationBindingResolver;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import com.lambda.fusion.ai.llm.service.LlmModelService;
import com.lambda.fusion.ai.runtime.event.ConfigChangedEvent;
import com.lambda.fusion.ai.runtime.workspace.WorkspacePaths;
import com.lambda.fusion.core.utils.AuthUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.agentscope.core.util.JsonUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class AppServiceImpl implements AppService {

    private final AppMapper appMapper;
    private final AppConfigAuditMapper appConfigAuditMapper;
    private final LlmModelService llmModelService;
    private final MediaGenerationBindingResolver mediaBindingResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkspacePaths workspacePaths;
    private final AiProperties aiProperties;

    @Override
    public Page<AppEntity> page(AppPageQuery query) {
        return appMapper.selectPage(query.getPage(), query.getLambdaQueryWrapper());
    }

    @Override
    public AppEntity get(String id) {
        return requireExists(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AppEntity create(CreateApp dto) {
        validateChatModel(dto.getModelId());
        String imageModelId = validateMediaModel(dto.getImageModelId(), ModelType.IMAGE_GEN);
        String videoModelId = validateMediaModel(dto.getVideoModelId(), ModelType.VIDEO_GEN);
        String audioModelId = validateMediaModel(dto.getAudioModelId(), ModelType.AUDIO_GEN);
        String appType = validateAppType(dto.getAppType());
        String sandboxBackend = validateSandboxBackend(dto.getSandboxBackend());
        validateRagMode(dto.getRagMode());
        validateAudience(dto.getAudience());
        ensureNameUnique(dto.getName(), null);
        AppEntity entity = getAppEntity(dto, appType, sandboxBackend);
        entity.setImageModelId(imageModelId);
        entity.setVideoModelId(videoModelId);
        entity.setAudioModelId(audioModelId);
        appMapper.insert(entity);
        // create 的变更前快照即初始配置（insert 后 tenantId 已由租户插件回填到实体）。
        recordConfigAudit(entity, "CREATE", entity);
        eventPublisher.publishEvent(ConfigChangedEvent.app(entity.getId()));
        return entity;
    }

    private static AppEntity getAppEntity(CreateApp dto, String appType, String sandboxBackend) {
        AppEntity entity = new AppEntity();
        entity.setId(IdUtil.getSnowflakeNextIdStr());
        entity.setName(dto.getName());
        entity.setAvatar(dto.getAvatar());
        entity.setDescription(dto.getDescription());
        entity.setSystemPrompt(dto.getSystemPrompt());
        entity.setModelId(dto.getModelId());
        entity.setMaxIters(dto.getMaxIters());
        entity.setTemperature(dto.getTemperature());
        entity.setToolsAllow(dto.getToolsAllow());
        entity.setToolsDeny(dto.getToolsDeny());
        entity.setMcpServerIds(dto.getMcpServerIds());
        entity.setKnowledgeBaseIds(dto.getKnowledgeBaseIds());
        entity.setRagMode(dto.getRagMode());
        entity.setSubAgentIds(dto.getSubAgentIds());
        entity.setSkillsAllow(dto.getSkillsAllow());
        entity.setSkillsDeny(dto.getSkillsDeny());
        entity.setAppType(appType);
        entity.setSelfEvolve(dto.getSelfEvolve());
        entity.setSandboxBackend(sandboxBackend);
        entity.setAudience(StringUtils.defaultIfBlank(dto.getAudience(), "ALL"));
        entity.setEnabled(dto.getEnabled());
        entity.setCreatedBy(AuthUtils.getUser().getUsername());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(String id, UpdateApp dto) {
        AppEntity entity = requireExists(id);
        // 在任何字段变更前，先以查到的旧实体写变更前快照，保证回退可查。
        recordConfigAudit(entity, "UPDATE", entity);
        if (StringUtils.isNotBlank(dto.getModelId()) && !dto.getModelId().equals(entity.getModelId())) {
            validateChatModel(dto.getModelId());
            entity.setModelId(dto.getModelId());
        }
        if (dto.getImageModelId() != null) {
            // null 不更新；空串显式解除绑定
            entity.setImageModelId(validateMediaModel(dto.getImageModelId(), ModelType.IMAGE_GEN));
        }
        if (dto.getVideoModelId() != null) {
            entity.setVideoModelId(validateMediaModel(dto.getVideoModelId(), ModelType.VIDEO_GEN));
        }
        if (dto.getAudioModelId() != null) {
            entity.setAudioModelId(validateMediaModel(dto.getAudioModelId(), ModelType.AUDIO_GEN));
        }
        if (StringUtils.isNotBlank(dto.getName()) && !dto.getName().equals(entity.getName())) {
            ensureNameUnique(dto.getName(), id);
            entity.setName(dto.getName());
        }
        if (dto.getAvatar() != null) {
            entity.setAvatar(dto.getAvatar());
        }
        if (dto.getDescription() != null) {
            entity.setDescription(dto.getDescription());
        }
        if (dto.getSystemPrompt() != null) {
            entity.setSystemPrompt(dto.getSystemPrompt());
        }
        if (dto.getMaxIters() != null) {
            entity.setMaxIters(dto.getMaxIters());
        }
        if (dto.getTemperature() != null) {
            entity.setTemperature(dto.getTemperature());
        }
        if (dto.getToolsAllow() != null) {
            entity.setToolsAllow(dto.getToolsAllow());
        }
        if (dto.getToolsDeny() != null) {
            entity.setToolsDeny(dto.getToolsDeny());
        }
        if (dto.getMcpServerIds() != null) {
            entity.setMcpServerIds(dto.getMcpServerIds());
        }
        if (dto.getKnowledgeBaseIds() != null) {
            entity.setKnowledgeBaseIds(dto.getKnowledgeBaseIds());
        }
        if (dto.getRagMode() != null) {
            validateRagMode(dto.getRagMode());
            entity.setRagMode(dto.getRagMode());
        }
        if (dto.getSubAgentIds() != null) {
            entity.setSubAgentIds(dto.getSubAgentIds());
        }
        if (dto.getSkillsAllow() != null) {
            entity.setSkillsAllow(dto.getSkillsAllow());
        }
        if (dto.getSkillsDeny() != null) {
            entity.setSkillsDeny(dto.getSkillsDeny());
        }
        // appType 创建后不可变；selfEvolve / sandboxBackend / audience 可调
        if (dto.getSelfEvolve() != null) {
            entity.setSelfEvolve(dto.getSelfEvolve());
        }
        if (StringUtils.isNotBlank(dto.getSandboxBackend())) {
            entity.setSandboxBackend(validateSandboxBackend(dto.getSandboxBackend()));
        }
        if (dto.getAudience() != null) {
            validateAudience(dto.getAudience());
            entity.setAudience(dto.getAudience());
        }
        if (dto.getEnabled() != null) {
            entity.setEnabled(dto.getEnabled());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        appMapper.updateById(entity);
        eventPublisher.publishEvent(ConfigChangedEvent.app(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        AppEntity app = requireExists(id);
        // 删除前记录最终配置快照，供误删后人工恢复参考。
        recordConfigAudit(app, "DELETE", app);
        if (AppType.WORKSPACE.getCode().equalsIgnoreCase(app.getAppType())) {
            workspacePaths.deleteAppWorkspaces(id);
        }
        appMapper.deleteById(id);
        eventPublisher.publishEvent(ConfigChangedEvent.app(id));
    }

    @Override
    public AppEntity loadById(String id) {
        return requireExists(id);
    }

    @Override
    public List<AppEntity> listAvailable() {
        var user = AuthUtils.getUser();
        String userId = user.getUsername();
        Set<String> roles = user.getRoles();
        List<AppEntity> candidates = appMapper.selectList(new LambdaQueryWrapper<AppEntity>()
                .eq(AppEntity::getEnabled, Boolean.TRUE)
                .and(w -> w.isNull(AppEntity::getOwnerId).or().eq(AppEntity::getOwnerId, userId)));
        return candidates.stream()
                .filter(app -> isAppVisible(app, roles, userId))
                .map(this::fillSupportsVision)
                .toList();
    }

    @Override
    public List<AvailableApp> listAvailableView() {
        return listAvailable().stream().map(this::toAvailableView).toList();
    }

    // 回填绑定模型的视觉能力，供前端控制图片附件入口；模型被删等异常置 null，不阻断列表
    private AppEntity fillSupportsVision(AppEntity app) {
        try {
            app.setSupportsVision(Boolean.TRUE.equals(
                    llmModelService.loadById(app.getModelId()).getSupportsVision()));
        } catch (RuntimeException e) {
            log.warn("回填应用视觉能力失败: appId={}, modelId={}", app.getId(), app.getModelId());
            app.setSupportsVision(null);
        }
        return app;
    }

    @Override
    public AppEntity loadAvailable(String appId) {
        AppEntity app = requireExists(appId);
        if (!Boolean.TRUE.equals(app.getEnabled())) {
            throw new AiBusinessException(AiErrorCode.APP_DISABLED, appId);
        }
        var user = AuthUtils.getUser();
        if (!isAppVisible(app, user.getRoles(), user.getUsername())) {
            throw new AiBusinessException(AiErrorCode.APP_NOT_FOUND, appId);
        }
        return app;
    }

    @Override
    public AvailableApp loadAvailableView(String appId) {
        return toAvailableView(fillSupportsVision(loadAvailable(appId)));
    }

    /**
     * 构建聊天端安全视图并按当前模型/provider 状态解析真实可挂载的媒体能力。模型、provider
     * 后续被禁用或类型被修改时不再向前端宣称可用，与 ToolkitAssembler 的降级策略保持一致。
     */
    private AvailableApp toAvailableView(AppEntity app) {
        AvailableApp view = AppService.toAvailableView(app);
        List<MediaGenerationCapability> capabilities = new ArrayList<>(3);
        addMediaCapability(capabilities, app, ModelType.IMAGE_GEN);
        addMediaCapability(capabilities, app, ModelType.VIDEO_GEN);
        addMediaCapability(capabilities, app, ModelType.AUDIO_GEN);
        view.setMediaGenerationCapabilities(List.copyOf(capabilities));
        return view;
    }

    private void addMediaCapability(
            List<MediaGenerationCapability> capabilities, AppEntity app, ModelType expectedType) {
        try {
            mediaBindingResolver
                    .resolve(app, expectedType)
                    .map(MediaGenerationBindingResolver.Binding::capability)
                    .ifPresent(capabilities::add);
        } catch (RuntimeException e) {
            log.warn("回填应用媒体生成能力失败: appId={}, expectedType={}", app.getId(), expectedType, e);
        }
    }

    /**
     * 应用可见性：独立应用仅所有者可见；平台应用按 audience + 角色（ALL=所有登录用户）。
     * audience 经 {@link AppAudience} 显式三分支解析；非法/未知值不落入任何分支，显式判不可见。
     */
    private boolean isAppVisible(AppEntity app, Set<String> roles, String userId) {
        if (StringUtils.isNotBlank(app.getOwnerId())) {
            return app.getOwnerId().equals(userId);
        }
        AppAudience audience = AppAudience.of(StringUtils.defaultIfBlank(app.getAudience(), AppAudience.ALL.getCode()));
        if (audience == null) {
            // 入口已硬校验，此处仅防御历史脏数据：未知受众不提供访问。
            return false;
        }
        if (audience == AppAudience.ALL) {
            return true;
        }
        List<String> requiredRoles = audience == AppAudience.B
                ? aiProperties.getAudience().getBRoles()
                : aiProperties.getAudience().getCRoles();
        return roles != null && requiredRoles != null && requiredRoles.stream().anyMatch(roles::contains);
    }

    private AppEntity requireExists(String id) {
        AppEntity entity = appMapper.selectOne(new LambdaQueryWrapper<AppEntity>().eq(AppEntity::getId, id));
        if (entity == null) {
            throw new AiBusinessException(AiErrorCode.APP_NOT_FOUND, id);
        }
        return entity;
    }

    /**
     * 追加一条配置变更审计（append-only，非版本机制）。快照取变更前实体，租户取应用真实租户；
     * 审计失败随业务事务回滚，不产生半状态。仅支撑人工回退，不参与运行时读取。
     */
    private void recordConfigAudit(AppEntity app, String operation, AppEntity snapshotSource) {
        try {
            AppConfigAuditEntity audit = new AppConfigAuditEntity();
            audit.setTenantId(app.getTenantId());
            audit.setAppId(app.getId());
            audit.setOperation(operation);
            audit.setConfigJson(JsonUtils.getJsonCodec().toJson(snapshotSource));
            audit.setOperator(AuthUtils.getUser().getUsername());
            audit.setCreatedAt(LocalDateTime.now());
            appConfigAuditMapper.insert(audit);
        } catch (RuntimeException auditFailure) {
            // 审计是回退保障而非主流程：失败只告警，不阻断配置变更本身。
            log.warn("应用配置审计写入失败: appId={}, operation={}", app.getId(), operation, auditFailure);
        }
    }

    private void ensureNameUnique(String name, String excludeId) {
        boolean exists = appMapper.exists(new LambdaQueryWrapper<AppEntity>()
                .eq(AppEntity::getName, name)
                .ne(excludeId != null, AppEntity::getId, excludeId));
        if (exists) {
            throw new AiBusinessException(AiErrorCode.APP_NAME_EXISTS, name);
        }
    }

    // 对话主模型只能绑定 CHAT 型，防止误绑嵌入/生成模型导致运行时构建失败
    private void validateChatModel(String modelId) {
        LlmModelEntity model = llmModelService.loadById(modelId);
        if (model.getModelType() != ModelType.CHAT) {
            throw new AiBusinessException(AiErrorCode.INVALID_PARAMETER, "应用只能绑定对话模型: " + modelId);
        }
    }

    /** 校验媒体绑定可由当前 AgentScope 官方工具执行；空串用于显式解除绑定。 */
    private String validateMediaModel(String modelId, ModelType expectedType) {
        if (StringUtils.isBlank(modelId)) {
            return null;
        }
        mediaBindingResolver.requireExecutable(modelId, expectedType);
        return modelId;
    }

    private String validateAppType(String appType) {
        String resolved = StringUtils.defaultIfBlank(appType, AppType.CHAT.getCode());
        if (AppType.of(resolved) == null) {
            throw new AiBusinessException(AiErrorCode.INVALID_PARAMETER, "非法应用类型: " + appType);
        }
        return resolved;
    }

    private String validateSandboxBackend(String sandboxBackend) {
        String resolved = StringUtils.defaultIfBlank(sandboxBackend, SandboxBackend.HOST.getCode());
        if (SandboxBackend.of(resolved) == null) {
            throw new AiBusinessException(AiErrorCode.INVALID_PARAMETER, "非法沙箱后端: " + sandboxBackend);
        }
        return resolved;
    }

    // ragMode 仅允许 GENERIC/AGENTIC/BOTH；空值合法（按 GENERIC 处理）
    private void validateRagMode(String ragMode) {
        if (StringUtils.isNotBlank(ragMode) && RagMode.of(ragMode) == null) {
            throw new AiBusinessException(AiErrorCode.APP_RAG_MODE_INVALID, ragMode);
        }
    }

    // audience 仅允许 B/C/ALL；空值合法（按 ALL 处理）。非法值一律拒绝，不得静默落入某分支。
    private void validateAudience(String audience) {
        if (StringUtils.isNotBlank(audience) && AppAudience.of(audience) == null) {
            throw new AiBusinessException(AiErrorCode.APP_AUDIENCE_INVALID, audience);
        }
    }
}
