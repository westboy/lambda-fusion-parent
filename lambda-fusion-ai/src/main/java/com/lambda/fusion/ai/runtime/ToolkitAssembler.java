package com.lambda.fusion.ai.runtime;

import com.lambda.fusion.ai.AiConstants.AppType;
import com.lambda.fusion.ai.AiConstants.MediaGenerationOperation;
import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiConstants.RagMode;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.apps.service.MediaGenerationBindingResolver;
import com.lambda.fusion.ai.apps.service.MediaGenerationBindingResolver.Binding;
import com.lambda.fusion.ai.chat.attachment.ChatAttachmentContentTool;
import com.lambda.fusion.ai.chat.attachment.WorkspaceArtifactPublishTool;
import com.lambda.fusion.ai.chat.runtime.media.ToolResultMediaMaterializer;
import com.lambda.fusion.ai.mcp.model.entity.McpServerEntity;
import com.lambda.fusion.ai.mcp.service.McpServerService;
import com.lambda.fusion.ai.rag.runtime.KnowledgeRetrievalTool;
import com.lambda.fusion.ai.rag.runtime.KnowledgeRetriever;
import com.lambda.fusion.ai.rag.runtime.RagModeResolver;
import com.lambda.fusion.ai.runtime.annotation.AiTools;
import com.lambda.fusion.ai.runtime.annotation.RequireConfirm;
import com.lambda.fusion.ai.runtime.media.MaterializingMediaTool;
import com.lambda.fusion.ai.runtime.media.MediaToolAdapterRegistry;
import com.lambda.fusion.ai.runtime.tools.QwenImageGenerationTool;
import com.lambda.fusion.ai.security.KeyEncryptionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 工具集装配器：为每个应用构建 {@link Toolkit}，含全局 {@link AiTools} 本地工具、独立 Qwen
 * 生图工具、应用绑定的官方媒体生成工具与 MCP 服务。
 *
 * @author Jin
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class ToolkitAssembler {

    private final ApplicationContext applicationContext;
    private final McpServerService mcpServerService;
    private final MediaGenerationBindingResolver mediaBindingResolver;
    private final KeyEncryptionService keyEncryptionService;
    private final ToolResultMediaMaterializer mediaMaterializer;
    private final AiProperties aiProperties;
    private final ChatAttachmentContentTool chatAttachmentContentTool;
    private final WorkspaceArtifactPublishTool workspaceArtifactPublishTool;
    private final ObjectProvider<KnowledgeRetriever> knowledgeRetrieverProvider;
    private final ObjectProvider<QwenImageGenerationTool> qwenImageGenerationToolProvider;

    /**
     * -- GETTER --
     *  全局本地工具 Bean 列表（启动时扫描，只读）。
     *  供无 App 维度的场景（如定时任务）按
     *  白名单选择性注册。
     */
    @Getter
    private List<Object> localToolBeans = Collections.emptyList();

    /** 需要 HITL 确认的本地工具名（{@code @RequireConfirm} 声明，启动时扫描）。 */
    @Getter
    private Set<String> askToolNames = Collections.emptySet();

    @PostConstruct
    public void init() {
        // 只扫描通用 @AiTools Bean，并收集需 HITL 确认的工具名供 askRules。
        List<Object> tools = new ArrayList<>();
        Set<String> asks = new LinkedHashSet<>();
        for (String name : applicationContext.getBeanDefinitionNames()) {
            Class<?> type = applicationContext.getType(name);
            boolean annotatedTool = type != null && type.isAnnotationPresent(AiTools.class);
            if (!annotatedTool) {
                continue;
            }
            try {
                tools.add(applicationContext.getBean(name));
                collectAskToolNames(type, asks);
            } catch (LinkageError | RuntimeException e) {
                // 单个 Bean 反射失败（如方法签名引用缺失类）不阻断启动，跳过并告警
                log.warn("跳过无法反射的工具 Bean {}: {}", name, e.toString());
            }
        }
        this.localToolBeans = Collections.unmodifiableList(tools);
        this.askToolNames = Collections.unmodifiableSet(asks);
        log.info("装配 {} 个全局本地工具 Bean，其中 {} 个需 HITL 确认", tools.size(), asks.size());
    }

    // 收集需确认的工具名：@AiTools(requireConfirm) 作用于该类所有 @Tool 方法，方法级 @RequireConfirm 单独生效
    private void collectAskToolNames(Class<?> type, Set<String> asks) {
        boolean classLevel = type.getAnnotation(AiTools.class).requireConfirm();
        for (Method method : type.getMethods()) {
            Tool toolAnno = method.getAnnotation(Tool.class);
            if (toolAnno == null) {
                continue;
            }
            if (classLevel || method.isAnnotationPresent(RequireConfirm.class)) {
                asks.add(toolAnno.name().isEmpty() ? method.getName() : toolAnno.name());
            }
        }
    }

    /** 构建平台 Web Chat 使用的工具集，包含依赖 ChatRun 和平台会话附件的工具。 */
    public Toolkit buildForChatRun(AppEntity app) {
        return build(app, true);
    }

    /** 构建 Channel 使用的工具集，不暴露需要平台 ChatRun 上下文的附件和媒体工具。 */
    public Toolkit buildForChannel(AppEntity app) {
        return build(app, false);
    }

    private Toolkit build(AppEntity app, boolean includeChatRunTools) {
        Duration timeout = Duration.ofSeconds(aiProperties.getChat().getRun().getMaxRunDurationSeconds());
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
                .executionConfig(ExecutionConfig.builder()
                        .timeout(timeout)
                        .maxAttempts(1)
                        .build())
                .build());
        for (Object toolBean : localToolBeans) {
            toolkit.registerTool(toolBean);
        }
        if (includeChatRunTools) {
            // 这些工具依赖平台 ChatRun、会话附件和租户上下文，不得进入 Channel 或定时任务工具集。
            toolkit.registerTool(chatAttachmentContentTool);
            registerWorkspaceArtifactTool(toolkit, app);
            registerIndependentMediaTools(toolkit);
            registerMediaGenerationTools(toolkit, app);
        }
        registerKnowledgeTool(toolkit, app);
        registerMcpServers(toolkit, app.getMcpServerIds());
        applyToolPolicy(toolkit, app.getToolsAllow(), app.getToolsDeny());
        return toolkit;
    }

    /** 所有来源注册完成后统一应用应用级工具策略，避免本地、媒体和 MCP 工具各自解释白黑名单。 */
    private static void applyToolPolicy(Toolkit toolkit, List<String> allow, List<String> deny) {
        Set<String> allowed = normalizedToolNames(allow);
        Set<String> denied = normalizedToolNames(deny);
        boolean hasAllowList = !allowed.isEmpty();
        for (String toolName : new ArrayList<>(toolkit.getToolNames())) {
            if (denied.contains(toolName) || (hasAllowList && !allowed.contains(toolName))) {
                toolkit.removeTool(toolName);
            }
        }
    }

    private static Set<String> normalizedToolNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                normalized.add(name.trim());
            }
        }
        return normalized;
    }

    private void registerWorkspaceArtifactTool(Toolkit toolkit, AppEntity app) {
        if (AppType.of(app.getAppType()) == AppType.WORKSPACE) {
            toolkit.registerTool(workspaceArtifactPublishTool);
        }
    }

    /** Agentic/BOTH 模式的应用专属知识工具也必须在统一工具策略之前注册。 */
    private void registerKnowledgeTool(Toolkit toolkit, AppEntity app) {
        RagMode ragMode = RagModeResolver.resolve(app);
        if (ragMode != RagMode.AGENTIC && ragMode != RagMode.BOTH) {
            return;
        }
        KnowledgeRetriever retriever = knowledgeRetrieverProvider.getIfAvailable();
        if (retriever == null) {
            return;
        }
        toolkit.registerTool(new KnowledgeRetrievalTool(retriever, app.getKnowledgeBaseIds()));
        log.info("应用 {} 启用 Agentic 知识检索工具(kbs={})", app.getId(), app.getKnowledgeBaseIds());
    }

    /** 独立 Qwen 工具不参与应用模型绑定，也不进入无聊天上下文的定时任务 Toolkit。 */
    private void registerIndependentMediaTools(Toolkit toolkit) {
        QwenImageGenerationTool qwenTool = qwenImageGenerationToolProvider.getIfAvailable();
        if (qwenTool != null) {
            toolkit.registerTool(materializingTool(qwenTool, QwenImageGenerationTool.TOOL_NAME));
        }
    }

    /** 应用保存校验与此处共用同一绑定解析器；单个失效绑定只降级该工具。 */
    private void registerMediaGenerationTools(Toolkit toolkit, AppEntity app) {
        for (ModelType type : List.of(ModelType.IMAGE_GEN, ModelType.VIDEO_GEN, ModelType.AUDIO_GEN)) {
            try {
                mediaBindingResolver
                        .resolve(app, type)
                        .map(this::buildMediaTool)
                        .ifPresent(tool -> {
                            toolkit.registerTool(tool);
                            log.info("应用 {} 已挂载媒体生成工具: operation={}", app.getId(), tool.getName());
                        });
            } catch (RuntimeException e) {
                log.warn("应用 {} 媒体生成工具挂载失败，跳过: type={}", app.getId(), type, e);
            }
        }
    }

    /** 解密应用绑定提供方的凭据，并创建 AgentScope 官方媒体工具。 */
    private AgentTool buildMediaTool(Binding binding) {
        String apiKey = keyEncryptionService.decrypt(binding.provider().getApiKeyEncrypted());
        MediaGenerationOperation operation = binding.model().getMediaOperation();
        Object adapter = MediaToolAdapterRegistry.create(
                binding.providerType(),
                operation,
                apiKey,
                binding.provider().getBaseUrl(),
                binding.model().getModelName());
        return materializingTool(adapter, operation.getToolName());
    }

    /**
     * 让 AgentScope 从薄适配器的 {@code @Tool} 方法生成权威 Schema，再只装饰该方法的执行结果。
     * AgentScope 2.0.1 对普通对象尚不支持按方法注册，因此使用临时 Toolkit 完成官方反射转换。
     */
    private AgentTool materializingTool(Object adapter, String toolName) {
        Toolkit schemaToolkit = new Toolkit();
        schemaToolkit.registerTool(adapter);
        AgentTool delegate = schemaToolkit.getTool(toolName);
        if (delegate == null) {
            throw new IllegalArgumentException("AgentScope 未注册媒体工具: " + toolName);
        }
        return new MaterializingMediaTool(delegate, mediaMaterializer);
    }

    private void registerMcpServers(Toolkit toolkit, List<String> mcpServerIds) {
        if (mcpServerIds == null || mcpServerIds.isEmpty()) {
            return;
        }
        for (String mcpId : mcpServerIds) {
            try {
                McpServerEntity server = mcpServerService.loadById(mcpId);
                if (Boolean.FALSE.equals(server.getEnabled())) {
                    log.warn("MCP 服务 {} 已禁用，跳过装载", mcpId);
                    continue;
                }
                if (Boolean.FALSE.equals(server.getEnableTools())) {
                    log.warn("MCP 服务 {} 未启用工具，跳过装载", mcpId);
                    continue;
                }
                McpClientWrapper wrapper = mcpServerService.buildWrapper(server);
                toolkit.registration().mcpClient(wrapper).apply();
                log.info("已装载 MCP 服务 [{}] 的工具", server.getName());
            } catch (Exception e) {
                log.error("装载 MCP 服务 {} 失败: {}", mcpId, e.getMessage());
            }
        }
    }
}
