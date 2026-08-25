package com.lambda.fusion.ai.runtime;

import com.lambda.fusion.ai.AiConstants.MediaGenerationOperation;
import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiConstants.ProviderType;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.apps.service.MediaGenerationBindingResolver;
import com.lambda.fusion.ai.apps.service.MediaGenerationBindingResolver.Binding;
import com.lambda.fusion.ai.chat.runtime.media.ToolResultMediaMaterializer;
import com.lambda.fusion.ai.mcp.model.entity.McpServerEntity;
import com.lambda.fusion.ai.mcp.service.McpServerService;
import com.lambda.fusion.ai.runtime.annotation.AiTools;
import com.lambda.fusion.ai.runtime.annotation.RequireConfirm;
import com.lambda.fusion.ai.runtime.media.MaterializingMediaTool;
import com.lambda.fusion.ai.runtime.tools.AudioGenerationTool;
import com.lambda.fusion.ai.runtime.tools.ImageGenerationTool;
import com.lambda.fusion.ai.runtime.tools.VideoGenerationTool;
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
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 工具集装配器：为每个应用构建 {@link Toolkit}，含全局 {@code @AiTool} 本地工具 Bean、
 * 应用绑定的图片/视频生成工具与应用绑定的 MCP 服务（每个独立装载，单个失败不影响其余）。
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

    /**
     * -- GETTER --
     *  共享的本地
     *  {@code @AiTool} Bean 列表（启动时扫描，只读）。
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
        // 扫描 @AiTool Bean 注册为共享工具，并收集需 HITL 确认的工具名供 askRules
        List<Object> tools = new ArrayList<>();
        Set<String> asks = new LinkedHashSet<>();
        for (String name : applicationContext.getBeanDefinitionNames()) {
            Class<?> type = applicationContext.getType(name);
            if (type == null || !type.isAnnotationPresent(AiTools.class)) {
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
        log.info("扫描到 {} 个本地 @AiTool Bean，其中 {} 个需 HITL 确认", tools.size(), asks.size());
    }

    // 收集需确认的工具名：@AiTool(requireConfirm) 作用于该类所有 @Tool 方法，方法级 @RequireConfirm 单独生效
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

    public Toolkit build(AppEntity app) {
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
        registerMediaGenerationTools(toolkit, app);
        registerMcpServers(toolkit, app.getMcpServerIds());
        return toolkit;
    }

    /** 安全视图与此处共用同一绑定解析器；单个失效绑定只降级该能力。 */
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

    /** 只负责解密凭据并为统一工具名创建官方能力薄适配器。 */
    private AgentTool buildMediaTool(Binding binding) {
        String apiKey = keyEncryptionService.decrypt(binding.provider().getApiKeyEncrypted());
        String modelName = binding.model().getModelName();
        MediaGenerationOperation operation = binding.model().getMediaOperation();
        Object adapter =
                switch (operation) {
                    case TEXT_TO_IMAGE ->
                        switch (binding.providerType()) {
                            case DASHSCOPE -> ImageGenerationTool.dashscope(apiKey, modelName);
                            case OPENAI ->
                                ImageGenerationTool.openai(
                                        apiKey, binding.provider().getBaseUrl(), modelName);
                            default -> throw unsupportedMediaBinding(binding);
                        };
                    case TEXT_TO_VIDEO, IMAGE_TO_VIDEO, FIRST_LAST_FRAME_TO_VIDEO -> {
                        if (binding.providerType() != ProviderType.DASHSCOPE) {
                            throw unsupportedMediaBinding(binding);
                        }
                        yield VideoGenerationTool.dashscope(apiKey, modelName);
                    }
                    case TEXT_TO_SPEECH -> {
                        if (binding.providerType() != ProviderType.DASHSCOPE) {
                            throw unsupportedMediaBinding(binding);
                        }
                        yield AudioGenerationTool.dashscope(apiKey, modelName);
                    }
                };
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

    private static IllegalArgumentException unsupportedMediaBinding(Binding binding) {
        return new IllegalArgumentException("没有匹配的 AgentScope 媒体工具: provider="
                + binding.providerType()
                + ", operation="
                + binding.model().getMediaOperation());
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
