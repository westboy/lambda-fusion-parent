package com.lambda.fusion.ai.runtime;

import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiConstants.ProviderType;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.apps.service.MediaGenerationCapabilityFactory;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import com.lambda.fusion.ai.llm.model.entity.LlmProviderEntity;
import com.lambda.fusion.ai.llm.service.LlmModelService;
import com.lambda.fusion.ai.llm.service.LlmProviderService;
import com.lambda.fusion.ai.mcp.model.entity.McpServerEntity;
import com.lambda.fusion.ai.mcp.service.McpServerService;
import com.lambda.fusion.ai.runtime.annotation.AiTools;
import com.lambda.fusion.ai.runtime.annotation.RequireConfirm;
import com.lambda.fusion.ai.runtime.tools.ImageGenerationTool;
import com.lambda.fusion.ai.runtime.tools.VideoGenerationTool;
import com.lambda.fusion.ai.security.KeyEncryptionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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
    private final LlmModelService llmModelService;
    private final LlmProviderService llmProviderService;
    private final KeyEncryptionService keyEncryptionService;

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
        Toolkit toolkit = new Toolkit();
        for (Object toolBean : localToolBeans) {
            toolkit.registerTool(toolBean);
        }
        registerImageGenerationTool(toolkit, app);
        registerVideoGenerationTool(toolkit, app);
        registerMcpServers(toolkit, app.getMcpServerIds());
        return toolkit;
    }

    /**
     * 按应用绑定的 {@code IMAGE_GEN} 模型挂载文生图工具。绑定合法性已在配置入口强校验；
     * 此处对后续配置变化（模型禁用、provider 关闭生成开关等）降级跳过并告警，不阻断对话构建。
     */
    private void registerImageGenerationTool(Toolkit toolkit, AppEntity app) {
        if (StringUtils.isBlank(app.getImageModelId())) {
            return;
        }
        try {
            LlmModelEntity model = llmModelService.loadById(app.getImageModelId());
            if (model.getModelType() != ModelType.IMAGE_GEN || Boolean.FALSE.equals(model.getEnabled())) {
                log.warn("应用 {} 绑定的生图模型 {} 已禁用或类型不符，跳过挂载", app.getId(), app.getImageModelId());
                return;
            }
            LlmProviderEntity provider = llmProviderService.loadById(model.getProviderId());
            if (Boolean.FALSE.equals(provider.getEnabled()) || !Boolean.TRUE.equals(provider.getEnableMediaGen())) {
                log.warn("应用 {} 生图模型所属 provider 未启用媒体生成，跳过挂载: {}", app.getId(), provider.getId());
                return;
            }
            ImageGenerationTool tool = buildImageTool(provider, model.getModelName());
            if (tool == null) {
                log.warn("应用 {} 生图模型所属 provider 类型 {} 暂不支持生图，跳过挂载", app.getId(), provider.getProviderType());
                return;
            }
            toolkit.registerTool(tool);
            log.info("应用 {} 已挂载文生图工具: model={}", app.getId(), model.getModelName());
        } catch (RuntimeException e) {
            log.warn("应用 {} 生图工具挂载失败，跳过", app.getId(), e);
        }
    }

    /** 按提供方类型解密 Key 并构建生图工具；不支持的类型返回 null。 */
    private ImageGenerationTool buildImageTool(LlmProviderEntity provider, String modelName) {
        ProviderType type = ProviderType.of(provider.getProviderType());
        if (!MediaGenerationCapabilityFactory.supports(ModelType.IMAGE_GEN, type, modelName)) {
            return null;
        }
        String apiKey = keyEncryptionService.decrypt(provider.getApiKeyEncrypted());
        if (type == ProviderType.DASHSCOPE) {
            return ImageGenerationTool.dashscope(apiKey, modelName);
        }
        if (type == ProviderType.OPENAI) {
            return ImageGenerationTool.openai(apiKey, provider.getBaseUrl(), modelName);
        }
        return null;
    }

    /**
     * 按应用绑定的 {@code VIDEO_GEN} 模型挂载文生视频工具。AgentScope 2.0.1 仅提供 DashScope
     * 视频生成实现；其他 provider 类型与失效配置均安全跳过。
     */
    private void registerVideoGenerationTool(Toolkit toolkit, AppEntity app) {
        if (StringUtils.isBlank(app.getVideoModelId())) {
            return;
        }
        try {
            LlmModelEntity model = llmModelService.loadById(app.getVideoModelId());
            if (model.getModelType() != ModelType.VIDEO_GEN || Boolean.FALSE.equals(model.getEnabled())) {
                log.warn("应用 {} 绑定的视频模型 {} 已禁用或类型不符，跳过挂载", app.getId(), app.getVideoModelId());
                return;
            }
            LlmProviderEntity provider = llmProviderService.loadById(model.getProviderId());
            if (Boolean.FALSE.equals(provider.getEnabled()) || !Boolean.TRUE.equals(provider.getEnableMediaGen())) {
                log.warn("应用 {} 视频模型所属 provider 未启用媒体生成，跳过挂载: {}", app.getId(), provider.getId());
                return;
            }
            ProviderType providerType = ProviderType.of(provider.getProviderType());
            if (!MediaGenerationCapabilityFactory.supports(ModelType.VIDEO_GEN, providerType)) {
                log.warn("应用 {} 视频模型所属 provider 类型 {} 暂不支持视频生成，跳过挂载", app.getId(), provider.getProviderType());
                return;
            }
            String apiKey = keyEncryptionService.decrypt(provider.getApiKeyEncrypted());
            toolkit.registerTool(VideoGenerationTool.dashscope(apiKey, model.getModelName()));
            log.info("应用 {} 已挂载文生视频工具: model={}", app.getId(), model.getModelName());
        } catch (RuntimeException e) {
            log.warn("应用 {} 视频生成工具挂载失败，跳过", app.getId(), e);
        }
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
