package com.lambda.fusion.ai.runtime;

import com.lambda.fusion.ai.AiConstants.AppType;
import com.lambda.fusion.ai.AiConstants.RagMode;
import com.lambda.fusion.ai.AiConstants.StateStoreType;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.apps.service.AppService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.rag.runtime.KnowledgeRetrievalTool;
import com.lambda.fusion.ai.rag.runtime.KnowledgeRetriever;
import com.lambda.fusion.ai.rag.runtime.RagMiddleware;
import com.lambda.fusion.ai.runtime.event.ConfigChangedEvent;
import com.lambda.fusion.ai.runtime.media.ExplicitToolCallHook;
import com.lambda.fusion.ai.runtime.sandbox.SandboxSpecResolver;
import com.lambda.fusion.ai.runtime.state.StateStoreProvider;
import com.lambda.fusion.ai.runtime.workspace.WorkspaceStorage;
import com.lambda.fusion.ai.skill.runtime.SkillRepositoryResolver;
import com.lambda.fusion.ai.subagent.service.SubAgentService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
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
 * 按 {@code (appId, tenantId)} 构建并缓存智能应用的 {@link HarnessAgent}。
 *
 * <p>CHAT 应用只使用数据库配置，不启用工作区能力；WORKSPACE 应用拥有独立工作区，并根据
 * {@code selfEvolve} 控制文件与记忆的写能力。配置了非 HOST 沙箱后端时优先使用沙箱文件系统，
 * 后端不可用时返回空配置并回退宿主环境。
 *
 * @author Jin
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class AgentFactory {

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
     * 进程内（MEMORY）会话状态不随重建丢失；存储类型变更需重启生效。缓存键与 {@link #cache} 一致，
     * Agent 缓存失效时刻意不清空本缓存（保留会话记忆）。
     */
    private final Map<String, AgentStateStore> stateStores = new ConcurrentHashMap<>();

    /** 按应用和租户获取 Agent；首次构建由 {@link ConcurrentHashMap#computeIfAbsent} 保证线程安全。 */
    public HarnessAgent getOrBuild(String appId, String tenantId) {
        String cacheKey = cacheKey(appId, tenantId);
        return cache.computeIfAbsent(cacheKey, k -> build(appId, tenantId));
    }

    /** 获取或创建同 {@code (appId, tenantId)} 共享的状态存储。 */
    private AgentStateStore sharedStateStore(String appId, String tenantId) {
        return stateStores.computeIfAbsent(
                cacheKey(appId, tenantId), key -> resolveStateStore(aiProperties, stateStoreProviders));
    }

    public String buildStableAgentId(String appId, String tenantId) {
        return workspaceStorage.stableAgentId(appId, tenantId);
    }

    public void invalidateApp(String appId) {
        String prefix = appId + "|";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
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

    private HarnessAgent build(String appId, String tenantId) {
        AppEntity app = appService.loadById(appId);
        if (Boolean.FALSE.equals(app.getEnabled())) {
            throw new AiBusinessException(AiErrorCode.APP_DISABLED, appId);
        }
        Model model = modelResolver.apply(app.getModelId());
        Toolkit toolkit = toolkitAssembler.build(app);
        registerKnowledgeTool(toolkit, app);
        int maxIters = Objects.requireNonNullElse(
                app.getMaxIters(), aiProperties.getRuntime().getDefaultMaxIters());
        AppType appType = AppType.of(Objects.toString(app.getAppType(), AppType.CHAT.getCode()));
        List<MiddlewareBase> middlewares = resolveMiddlewares(app);
        HarnessAgent agent;
        String stableAgentId = buildStableAgentId(app.getId(), tenantId);
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
     * 按 app 的 {@code knowledgeBaseIds} 绑定 RAG 中间件：GENERIC/BOTH 且启用检索时挂载
     * {@link RagMiddleware}（对话实时检索注入）；绑定变更经 {@code ConfigChangedEvent} 重建 agent。
     */
    private List<MiddlewareBase> resolveMiddlewares(AppEntity app) {
        List<MiddlewareBase> middlewares = new ArrayList<>();
        RagMode ragMode = resolveRagMode(app);
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

    /**
     * 为 AGENTIC/BOTH 模式注册应用专属的知识检索工具。工具在构建期绑定知识库列表，
     * 不参与 {@link ToolkitAssembler} 的全局扫描，避免跨应用扩大检索范围。
     */
    private void registerKnowledgeTool(Toolkit toolkit, AppEntity app) {
        RagMode ragMode = resolveRagMode(app);
        if (ragMode != RagMode.AGENTIC && ragMode != RagMode.BOTH) {
            return;
        }
        KnowledgeRetriever retriever = retrieverProvider.getIfAvailable();
        if (retriever == null) {
            return;
        }
        toolkit.registerTool(new KnowledgeRetrievalTool(retriever, app.getKnowledgeBaseIds()));
        log.info("应用 {} 启用 Agentic 知识检索工具(kbs={})", app.getId(), app.getKnowledgeBaseIds());
    }

    /**
     * 解析应用的知识检索模式。未绑定知识库时禁用检索；模式为空或无法识别时按 GENERIC
     * 兼容历史数据，新增和更新入口仍负责拒绝非法值。
     */
    static RagMode resolveRagMode(AppEntity app) {
        if (app.getKnowledgeBaseIds() == null || app.getKnowledgeBaseIds().isEmpty()) {
            return null;
        }
        RagMode ragMode = RagMode.of(app.getRagMode());
        return ragMode != null ? ragMode : RagMode.GENERIC;
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

    @SuppressWarnings({"deprecation", "removal"})
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
                .hook(new ExplicitToolCallHook())
                .permissionContext(buildPermissionContext())
                .stateStore(sharedStateStore(app.getId(), tenantId))
                .disableFilesystemTools()
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
        if (!middlewares.isEmpty()) {
            builder.middlewares(middlewares);
        }
        return builder.build();
    }

    @SuppressWarnings({"deprecation", "removal"})
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
                .hook(new ExplicitToolCallHook())
                .permissionContext(buildPermissionContext())
                .stateStore(sharedStateStore(app.getId(), tenantId))
                .workspace(hostWorkspace)
                .memory(resolveMemoryConfig(aiProperties, model))
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
        if (!middlewares.isEmpty()) {
            builder.middlewares(middlewares);
        }
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

    /** 将系统级记忆刷新与模型安全边界映射为 AgentScope 官方配置。 */
    static MemoryConfig resolveMemoryConfig(AiProperties properties, Model model) {
        AiProperties.Memory memory = properties.getMemory();
        AiProperties.Memory.Flush flush = memory.getFlush();
        MemoryConfig.FlushTrigger trigger =
                switch (flush.getMode()) {
                    case ALWAYS -> MemoryConfig.FlushTrigger.always();
                    case THROTTLED -> MemoryConfig.FlushTrigger.throttled(flush.getMinGap());
                    case NEVER -> MemoryConfig.FlushTrigger.never();
                };
        return MemoryConfig.builder()
                .model(new MemoryModelGuard(model, memory.getMaxOutputTokens(), memory.getModelTimeout()))
                .flushTrigger(trigger)
                .build();
    }

    /** 缓存键格式须与 {@link #invalidateApp(String)} 的应用前缀匹配规则保持一致。 */
    private String cacheKey(String appId, String tenantId) {
        return appId + "|" + StringUtils.defaultString(tenantId);
    }
}
