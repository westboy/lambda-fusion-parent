package com.lambda.fusion.ai.runtime;

import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.mcp.model.entity.McpServerEntity;
import com.lambda.fusion.ai.mcp.service.McpServerService;
import com.lambda.fusion.ai.runtime.annotaion.AiTools;
import com.lambda.fusion.ai.runtime.annotaion.RequireConfirm;
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
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 工具集装配器：为每个应用构建 {@link Toolkit}，含全局 {@code @AiTool} 本地工具 Bean
 * 与应用绑定的 MCP 服务（每个独立装载，单个失败不影响其余）。
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
        registerMcpServers(toolkit, app.getMcpServerIds());
        return toolkit;
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
