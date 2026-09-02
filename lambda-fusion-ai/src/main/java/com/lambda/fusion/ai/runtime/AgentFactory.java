package com.lambda.fusion.ai.runtime;

import com.lambda.fusion.ai.AiConstants.AppType;
import com.lambda.fusion.ai.AiConstants.RagMode;
import com.lambda.fusion.ai.AiConstants.StateStoreType;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.apps.service.AppService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.rag.runtime.KnowledgeRetriever;
import com.lambda.fusion.ai.rag.runtime.RagMiddleware;
import com.lambda.fusion.ai.rag.runtime.RagModeResolver;
import com.lambda.fusion.ai.runtime.event.ConfigChangedEvent;
import com.lambda.fusion.ai.runtime.sandbox.SandboxSpecResolver;
import com.lambda.fusion.ai.runtime.state.StateStoreProvider;
import com.lambda.fusion.ai.runtime.workspace.WorkspaceStorage;
import com.lambda.fusion.ai.skill.runtime.SkillRepositoryResolver;
import com.lambda.fusion.ai.subagent.service.SubAgentService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 按 {@code (appId, tenantId, runtimeProfile)} 构建并缓存智能应用的 {@link HarnessAgent}。
 *
 * <p>CHAT 应用只使用数据库配置，不向模型开放工作区能力；附件工具仍复用会话隔离的 AgentScope
 * 文件系统，配置非 HOST 后端时进入真实沙箱，否则回退受限本地目录。WORKSPACE 应用拥有独立工作区，
 * 并根据 {@code selfEvolve} 控制文件与记忆的写能力。
 *
 * @author Jin
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class AgentFactory {

    private static final String CHANNEL_AGENT_ID_SUFFIX = "-channel";

    private final AppService appService;
    private final ModelResolver modelResolver;
    private final ToolkitAssembler toolkitAssembler;
    private final AiProperties aiProperties;
    private final WorkspaceStorage workspaceStorage;
    private final SandboxSpecResolver sandboxSpecResolver;
    private final List<StateStoreProvider> stateStoreProviders;
    private final SkillRepositoryResolver skillRepositoryResolver;
    private final ObjectProvider<HarnessGateway> gatewayProvider;
    private final ObjectProvider<KnowledgeRetriever> retrieverProvider;
    private final SubAgentService subAgentService;

    private final Map<String, HarnessAgent> cache = new ConcurrentHashMap<>();

    /**
     * 状态存储缓存：同 {@code (appId, tenantId)} 共享同一实例。Agent 因配置变更重建后仍复用原存储，
     * 进程内（MEMORY）会话状态不随重建丢失；存储类型变更需重启生效。ChatRun 与 Channel Agent 使用不同
     * agentId，但复用同一存储实例；Agent 缓存失效时刻意不清空本缓存（保留会话记忆）。
     */
    private final Map<String, AgentStateStore> stateStores = new ConcurrentHashMap<>();

    /** 获取平台 ChatRun 使用的 Agent；该实例可以包含依赖运行和附件上下文的工具。 */
    public HarnessAgent getOrBuildForChatRun(String appId, String tenantId) {
        return getOrBuild(appId, tenantId, RuntimeProfile.CHAT_RUN);
    }

    /** 获取 Channel 使用的 Agent；该实例不会暴露依赖平台 ChatRun 上下文的工具。 */
    public HarnessAgent getOrBuildForChannel(String appId, String tenantId) {
        return getOrBuild(appId, tenantId, RuntimeProfile.CHANNEL);
    }

    private HarnessAgent getOrBuild(String appId, String tenantId, RuntimeProfile profile) {
        String cacheKey = agentCacheKey(appId, tenantId, profile);
        return cache.computeIfAbsent(cacheKey, ignored -> build(appId, tenantId, profile));
    }

    /** 获取或创建同 {@code (appId, tenantId)} 共享的状态存储。 */
    private AgentStateStore sharedStateStore(String appId, String tenantId) {
        return stateStores.computeIfAbsent(
                stateStoreKey(appId, tenantId), key -> resolveStateStore(aiProperties, stateStoreProviders));
    }

    public String buildStableAgentId(String appId, String tenantId) {
        return workspaceStorage.stableAgentId(appId, tenantId);
    }

    /**
     * 清理 CHAT 会话独占的 AgentScope 工作区。HOST 删除 SESSION 文件命名空间；沙箱后端清除官方恢复状态，
     * 使下一次同会话调用不会恢复已删除会话的沙箱快照。WORKSPACE 应用的目录属于应用，不随对话删除。
     *
     * <p>该方法用于数据库事务提交后的资源回收，清理失败只告警，不能反向影响已经提交的会话删除。
     */
    public void clearChatSessionWorkspace(String appId, String tenantId, String userId, String sessionId) {
        if (StringUtils.isAnyBlank(appId, sessionId)) {
            log.warn("跳过 AgentScope 会话工作区清理：缺少应用或会话标识");
            return;
        }
        try {
            AppEntity app = appService.loadById(appId);
            AppType appType = AppType.of(StringUtils.defaultIfBlank(app.getAppType(), AppType.CHAT.getCode()));
            if (appType == AppType.WORKSPACE) {
                log.debug("保留 WORKSPACE 应用的应用级工作区: app={}, session={}", appId, sessionId);
                return;
            }
            String resolvedTenantId = StringUtils.defaultIfBlank(tenantId, "default");
            RuntimeContext runtimeContext =
                    RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
            if (sandboxSpecResolver.resolve(app, null).isPresent()) {
                String stableAgentId = buildStableAgentId(appId, resolvedTenantId);
                SandboxIsolationKey key = SandboxIsolationKey.resolve(
                                IsolationScope.SESSION, runtimeContext, stableAgentId)
                        .orElseThrow(() -> new IllegalStateException("无法解析会话沙箱隔离标识"));
                new SessionSandboxStateStore(sharedStateStore(appId, resolvedTenantId), stableAgentId).delete(key);
                log.info("已清除 AgentScope 会话沙箱恢复状态: app={}, session={}", appId, sessionId);
                return;
            }

            var filesystem = new LocalFilesystemSpec()
                    .mode(LocalFsMode.SANDBOXED)
                    .toFilesystem(resolveChatWorkspace(), IsolationScope.SESSION.toNamespaceFactory());
            WriteResult result = filesystem.delete(runtimeContext, ".");
            if (!result.isSuccess()) {
                log.warn("清理 AgentScope 会话工作区失败: app={}, session={}, reason={}", appId, sessionId, result.error());
                return;
            }
            log.info("已清理 AgentScope 会话工作区: app={}, session={}", appId, sessionId);
        } catch (Exception e) {
            log.warn("清理 AgentScope 会话资源失败: app={}, session={}, reason={}", appId, sessionId, safeMessage(e), e);
        }
    }

    public void invalidateApp(String appId) {
        String prefix = appId + "|";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * 删除会话的 AgentState：删除共享状态存储中该 {@code (userId, sessionId)} 槽位的全部持久化状态
     * （含 {@code agent_state} 与沙箱恢复键），避免已删除会话在持久化层残留数据。
     *
     * <p>仅清理持久化存储：AgentScope 未提供逐出进程内 {@code stateCache} 的公开 API，内存残留随
     * Agent 缓存失效重建或进程重启自然释放。该方法用于数据库事务提交后的资源回收，与 app 类型无关
     * （会话记忆不属于 Workspace）；清理失败只告警，不能反向影响已经提交的会话删除。
     */
    public void clearChatSessionState(String appId, String tenantId, String userId, String sessionId) {
        if (StringUtils.isAnyBlank(appId, sessionId)) {
            log.warn("跳过 AgentScope 会话状态清理：缺少应用或会话标识");
            return;
        }
        String resolvedTenantId = StringUtils.defaultIfBlank(tenantId, "default");
        try {
            sharedStateStore(appId, resolvedTenantId).delete(userId, sessionId);
            log.info("已删除 AgentScope 会话持久化状态: app={}, session={}", appId, sessionId);
        } catch (Exception e) {
            log.warn("删除 AgentScope 会话持久化状态失败: app={}, session={}, reason={}", appId, sessionId, safeMessage(e), e);
        }
    }

    public void invalidateAll() {
        cache.clear();
    }

    @EventListener
    public void onConfigChanged(ConfigChangedEvent event) {
        String source = event.remote() ? "远端" : "本地";
        if (event.appId() == null) {
            log.info("AI 配置变更({})，失效全部 Agent 缓存", source);
            invalidateAll();
        } else {
            log.info("应用 {} 配置变更({})，失效对应 Agent 缓存", event.appId(), source);
            invalidateApp(event.appId());
        }
    }

    private HarnessAgent build(String appId, String tenantId, RuntimeProfile profile) {
        AppEntity app = appService.loadById(appId);
        if (Boolean.FALSE.equals(app.getEnabled())) {
            throw new AiBusinessException(AiErrorCode.APP_DISABLED, appId);
        }
        Model model = modelResolver.apply(app.getModelId());
        Toolkit toolkit = profile == RuntimeProfile.CHAT_RUN
                ? toolkitAssembler.buildForChatRun(app)
                : toolkitAssembler.buildForChannel(app);
        int maxIters = Objects.requireNonNullElse(
                app.getMaxIters(), aiProperties.getRuntime().getDefaultMaxIters());
        if (maxIters < 1) {
            throw new AiBusinessException(AiErrorCode.INVALID_PARAMETER, "ReAct 最大迭代数必须大于 0");
        }
        AppType appType = AppType.of(Objects.toString(app.getAppType(), AppType.CHAT.getCode()));
        List<MiddlewareBase> middlewares = resolveMiddlewares(app);
        HarnessAgent agent;
        String stableAgentId = agentId(app.getId(), tenantId, profile);
        if (appType == AppType.WORKSPACE) {
            agent = buildWorkspace(
                    stableAgentId, app, tenantId, model, toolkit, maxIters, middlewares, resolveSubAgents(app));
        } else {
            agent = buildChat(stableAgentId, app, tenantId, model, toolkit, maxIters, middlewares);
        }
        registerWithGateway(stableAgentId, agent);
        return agent;
    }

    /** 将已绑定且启用的子代理转换为构建期声明；配置变更后通过缓存失效触发重建。 */
    private List<SubagentDeclaration> resolveSubAgents(AppEntity app) {
        if (app.getSubAgentIds() == null || app.getSubAgentIds().isEmpty()) {
            return List.of();
        }
        return subAgentService.listEnabledByIds(app.getSubAgentIds()).stream()
                .map(SubAgentDeclarationMapper::toDeclaration)
                .toList();
    }

    /**
     * 组装运行期中间件。配置 GENERIC/BOTH RAG 且检索器可用时挂载 {@link RagMiddleware}。
     * 绑定变更经 {@code ConfigChangedEvent} 重建 agent。
     */
    private List<MiddlewareBase> resolveMiddlewares(AppEntity app) {
        List<MiddlewareBase> middlewares = new ArrayList<>();
        RagMode ragMode = RagModeResolver.resolve(app);
        if (ragMode != RagMode.GENERIC && ragMode != RagMode.BOTH) {
            return List.copyOf(middlewares);
        }
        KnowledgeRetriever retriever = retrieverProvider.getIfAvailable();
        if (retriever == null) {
            return List.copyOf(middlewares);
        }
        middlewares.add(new RagMiddleware(
                retriever, app.getKnowledgeBaseIds(), aiProperties.getRag().getMaxInjectChars()));
        return List.copyOf(middlewares);
    }

    private void registerWithGateway(String stableAgentId, HarnessAgent agent) {
        HarnessGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            return;
        }
        gateway.registerAgent(stableAgentId, agent);
    }

    /**
     * 构建 HITL 权限上下文。默认使用 BYPASS，仅为 {@code @RequireConfirm} 工具追加 ASK 规则；
     * ASK 的优先级高于 BYPASS，因此不会误拦截其他工具。没有确认规则时不创建上下文。
     */
    private PermissionContextState buildPermissionContext() {
        Set<String> askToolNames = toolkitAssembler.getAskToolNames();
        if (askToolNames.isEmpty()) {
            return null;
        }
        PermissionContextState.Builder builder =
                PermissionContextState.builder().mode(PermissionMode.BYPASS);
        for (String toolName : askToolNames) {
            builder.addAskRule(toolName, new PermissionRule(toolName, null, PermissionBehavior.ASK, "userSettings"));
        }
        return builder.build();
    }

    /**
     * 按配置解析 Agent 状态存储：MEMORY/FILE 内置，MYSQL/POSTGRES/REDIS/OSS/COS 分发到匹配的
     * {@link StateStoreProvider}。显式配置非 MEMORY/FILE 时，扩展缺失或创建失败必须抛出
     * {@link AiErrorCode#CONFIGURATION_ERROR}，禁止静默回退 MEMORY。
     */
    static AgentStateStore resolveStateStore(AiProperties props) {
        return resolveStateStore(props, List.of());
    }

    static AgentStateStore resolveStateStore(AiProperties props, List<StateStoreProvider> providers) {
        AiProperties.StateStore cfg = props.getStateStore();
        String configuredType = cfg.getType();
        StateStoreType type = StateStoreType.of(configuredType);
        boolean explicit = StringUtils.isNotBlank(configuredType);

        if (type == StateStoreType.FILE) {
            return new JsonFileAgentStateStore(resolveStateRoot(props));
        }
        if (type == null || type == StateStoreType.MEMORY) {
            if (explicit && type == null) {
                throw new AiBusinessException(AiErrorCode.CONFIGURATION_ERROR, "未知的 Agent 状态存储类型: " + configuredType);
            }
            return new InMemoryAgentStateStore();
        }

        try {
            return providers.stream()
                    .filter(p -> p.type() == type)
                    .findFirst()
                    .map(StateStoreProvider::create)
                    .orElseThrow(() -> new AiBusinessException(
                            AiErrorCode.CONFIGURATION_ERROR, "Agent 状态存储类型 " + type + " 的扩展未安装"));
        } catch (AiBusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Agent 状态存储 {} 创建失败", type, e);
            throw new AiBusinessException(
                    AiErrorCode.CONFIGURATION_ERROR, "Agent 状态存储 " + type + " 创建失败: " + safeMessage(e));
        }
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /** FILE 状态目录依次取显式配置、{@code workspace.root/state} 和用户目录下的默认路径。 */
    private static Path resolveStateRoot(AiProperties props) {
        String configured = props.getStateStore().getRoot();
        if (StringUtils.isNotBlank(configured)) {
            return Path.of(configured);
        }
        String wsRoot = props.getWorkspace().getRoot();
        if (StringUtils.isNotBlank(wsRoot)) {
            return Path.of(wsRoot, "state");
        }
        return Paths.get(System.getProperty("user.home"), ".agentscope", "fusion", "state");
    }

    /**
     * 根据应用的技能白名单和黑名单创建过滤器。白名单优先；未配置白名单时才应用黑名单，
     * 两者均为空则允许全部技能。过滤器同时作用于技能市场和工作区技能源。
     */
    static SkillFilter resolveSkillFilter(AppEntity app) {
        List<String> allow = app.getSkillsAllow();
        if (allow != null && !allow.isEmpty()) {
            return SkillFilter.only(allow.toArray(new String[0]));
        }
        List<String> deny = app.getSkillsDeny();
        if (deny != null && !deny.isEmpty()) {
            return SkillFilter.except(deny.toArray(new String[0]));
        }
        return SkillFilter.all();
    }

    private HarnessAgent buildChat(
            String stableAgentId,
            AppEntity app,
            String tenantId,
            Model model,
            Toolkit toolkit,
            int maxIters,
            List<MiddlewareBase> middlewares) {
        log.info("构建 CHAT Agent: app={}, tenant={}", app.getId(), tenantId);
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .agentId(stableAgentId)
                .name(app.getName())
                .sysPrompt(StringUtils.defaultString(app.getSystemPrompt()))
                .model(model)
                .maxIters(maxIters)
                .toolkit(toolkit)
                .permissionContext(buildPermissionContext())
                .stateStore(sharedStateStore(app.getId(), tenantId))
                .disableFilesystemTools()
                .disableShellTool()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableCompaction()
                .disableToolResultEviction()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableSubagents()
                .disableToolsConfig()
                .disableSessionPersistence()
                .disableMemoryTools()
                .disableMemoryHooks();
        configureChatAttachmentFilesystem(builder, app);
        builder.middlewares(middlewares);
        return builder.build();
    }

    /** CHAT 仅把文件系统作为附件工具的受控暂存区，隔离粒度固定为会话，不暴露 Shell/写文件工具。 */
    private void configureChatAttachmentFilesystem(HarnessAgent.Builder builder, AppEntity app) {
        builder.workspace(resolveChatWorkspace());
        Optional<SandboxFilesystemSpec> sandboxSpec = sandboxSpecResolver.resolve(app, null);
        if (sandboxSpec.isPresent()) {
            builder.filesystem(
                    sandboxSpec.get().isolationScope(IsolationScope.SESSION).workspaceProjectionEnabled(false));
            return;
        }
        builder.filesystem(
                new LocalFilesystemSpec().mode(LocalFsMode.SANDBOXED).isolationScope(IsolationScope.SESSION));
    }

    /** 与 AgentScope {@code HarnessAgent} 默认工作区采用相同的公开配置优先级。 */
    private static Path resolveChatWorkspace() {
        String property = System.getProperty("agentscope.workspace");
        if (StringUtils.isNotBlank(property)) {
            return Paths.get(property.strip());
        }
        String environment = System.getenv("AGENTSCOPE_WORKSPACE");
        if (StringUtils.isNotBlank(environment)) {
            return Paths.get(environment.strip());
        }
        return Paths.get(System.getProperty("user.dir")).resolve(WorkspaceConstants.DEFAULT_WORKSPACE_ROOT);
    }

    private HarnessAgent buildWorkspace(
            String stableAgentId,
            AppEntity app,
            String tenantId,
            Model model,
            Toolkit toolkit,
            int maxIters,
            List<MiddlewareBase> middlewares,
            List<SubagentDeclaration> subAgents) {
        Path hostWorkspace = workspaceStorage.initializeWorkspace(tenantId, app);
        boolean selfEvolve = Boolean.TRUE.equals(app.getSelfEvolve());
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .agentId(stableAgentId)
                .name(app.getName())
                .sysPrompt(StringUtils.defaultString(app.getSystemPrompt()))
                .model(model)
                .maxIters(maxIters)
                .toolkit(toolkit)
                .permissionContext(buildPermissionContext())
                .stateStore(sharedStateStore(app.getId(), tenantId))
                .workspace(hostWorkspace)
                .memory(resolveMemoryConfig(aiProperties))
                .compaction(resolveCompactionConfig(model))
                .skillFilter(resolveSkillFilter(app));
        AgentSkillRepository skillRepo = skillRepositoryResolver.resolve();
        if (skillRepo != null) {
            builder.skillRepository(skillRepo);
        }
        Optional<SandboxFilesystemSpec> sandboxSpec = sandboxSpecResolver.resolve(app, hostWorkspace);
        workspaceStorage.configureFilesystem(builder, sandboxSpec, hostWorkspace, stableAgentId);
        if (!selfEvolve) {
            builder.disableFilesystemTools().disableMemoryTools().disableMemoryHooks();
        }
        builder.middlewares(middlewares);
        if (!subAgents.isEmpty()) {
            // 数据库声明与 workspace/subagents/*.md 的文件声明合并；同名文件声明优先。
            builder.subagents(subAgents);
            // declaration.model 保存 Fusion 模型 ID，由 ModelResolver 桥接到 ai_llm_model；主模型不受影响。
            builder.modelResolver(modelResolver);
        }
        // 自演化应用保留文件工具和记忆钩子，执行期间的文件变更由 WorkspaceAuditRecorder 审计。
        log.info(
                "构建 WORKSPACE Agent: app={}, tenant={}, selfEvolve={}, storage={}, sandbox={}",
                app.getId(),
                tenantId,
                selfEvolve,
                workspaceStorage.type(),
                sandboxSpec.isPresent() ? "yes" : "HOST");
        return builder.build();
    }

    /** 将系统级记忆刷新策略映射为 AgentScope 官方配置。 */
    static MemoryConfig resolveMemoryConfig(AiProperties properties) {
        AiProperties.Memory memory = properties.getMemory();
        AiProperties.Memory.Flush flush = memory.getFlush();
        MemoryConfig.FlushTrigger trigger =
                switch (flush.getMode()) {
                    case ALWAYS -> MemoryConfig.FlushTrigger.always();
                    case THROTTLED -> MemoryConfig.FlushTrigger.throttled(flush.getMinGap());
                    case NEVER -> MemoryConfig.FlushTrigger.never();
                };
        return MemoryConfig.builder().flushTrigger(trigger).build();
    }

    /** 使用 AgentScope 官方动态 Token 阈值；已知上下文窗口时关闭固定消息数阈值，未知时保留官方兜底。 */
    static CompactionConfig resolveCompactionConfig(Model model) {
        CompactionConfig.Builder builder = CompactionConfig.builder().triggerTokens(0);
        if (model.getContextWindowSize() > 0) {
            builder.triggerMessages(0);
        }
        return builder.build();
    }

    private String agentId(String appId, String tenantId, RuntimeProfile profile) {
        String stableAgentId = buildStableAgentId(appId, tenantId);
        return profile == RuntimeProfile.CHANNEL ? stableAgentId + CHANNEL_AGENT_ID_SUFFIX : stableAgentId;
    }

    /** Agent 缓存按运行场景隔离，同时保留应用前缀以支持统一失效。 */
    private String agentCacheKey(String appId, String tenantId, RuntimeProfile profile) {
        return stateStoreKey(appId, tenantId) + "|" + profile.name();
    }

    /** 不同运行场景仍共享同一应用/租户状态存储实例，具体状态由不同 agentId 隔离。 */
    private String stateStoreKey(String appId, String tenantId) {
        return appId + "|" + StringUtils.defaultString(tenantId);
    }

    private enum RuntimeProfile {
        CHAT_RUN,
        CHANNEL
    }
}
