package com.lambda.fusion.ai.runtime.tools;

import com.lambda.fusion.ai.runtime.annotaion.AiTools;
import com.lambda.fusion.ai.runtime.gateway.RuntimeProperty;
import com.lambda.fusion.authority.api.RemoteUser;
import com.lambda.fusion.authority.api.RemoteUserService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

/**
 * 当前对话用户信息查询工具：返回发起本次对话的用户身份。
 *
 * <p>身份标识（用户名/租户/会话/应用）来自对话建立时已确定的业务会话（{@code ChatSession}/{@code ChatRun}），
 * 由 {@code AgentExecutionAdapter} 在每次调用时写入 {@link RuntimeContext}；AgentScope 在反射调用工具时按参数类型
 * 自动注入 {@link RuntimeContext}（未标注 {@code @ToolParam}，不出现在模型可见的工具 schema 中，模型无法伪造）。
 *
 * <p>用户的昵称、组织、角色、账户状态等 {@code UserDetails} 级详情，经 authority 暴露的 {@link RemoteUserService}
 * （Dubbo）按用户名回查得到。authority 不在 classpath、远程不可用或用户不存在时，优雅降级为仅返回上下文中的基础身份，
 * 不影响对话流程。本工具不依赖登录安全上下文，可在 Agent 异步执行线程中安全使用。
 *
 * @author zx
 */
@Slf4j
@AiTools
public class CurrentUserQueryTool {

    /** 工具名。 */
    public static final String TOOL_NAME = "query_current_user";

    private final ObjectProvider<RemoteUserService> remoteUserService;

    public CurrentUserQueryTool(ObjectProvider<RemoteUserService> remoteUserService) {
        this.remoteUserService = remoteUserService;
    }

    /**
     * 查询当前对话用户信息。
     *
     * @param context 本次调用的运行时上下文（框架按类型自动注入，对模型不可见）
     * @return 当前对话用户信息的可读描述
     */
    @Tool(
            name = TOOL_NAME,
            description = "Get information about the user who started this conversation. "
                    + "Use this tool when the user asks who they are, their username, nickname, organization, "
                    + "roles/permissions, tenant, or which conversation/application context they are currently in.")
    public String queryCurrentUser(RuntimeContext context) {
        if (context == null) {
            return "无法确定当前用户：缺少对话上下文。";
        }
        String userId = context.getUserId();
        String tenantId = RuntimeProperty.tenantId(context);
        String sessionId = RuntimeProperty._sessionId(context);
        String appId = RuntimeProperty.appId(context);

        RemoteUser detail = loadRemoteUser(userId);
        if (detail == null) {
            return String.format(
                    "当前对话用户信息：用户名=%s，租户=%s，会话ID=%s，应用ID=%s。",
                    orUnknown(userId), orUnknown(tenantId), orUnknown(sessionId), orUnknown(appId));
        }
        return formatDetailed(detail, sessionId, appId, tenantId);
    }

    /** 经 authority 远程服务按用户名回查用户详情；任何失败均降级返回 null。 */
    private RemoteUser loadRemoteUser(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        RemoteUserService service = remoteUserService.getIfAvailable();
        if (service == null) {
            return null;
        }
        try {
            return service.getByUsername(userId);
        } catch (RuntimeException e) {
            log.warn("回查当前用户详情失败，降级为基础身份: userId={}, reason={}", userId, e.getMessage());
            return null;
        }
    }

    private static String formatDetailed(RemoteUser detail, String sessionId, String appId, String tenantId) {
        StringBuilder sb = new StringBuilder("当前对话用户信息：");
        sb.append("用户名=").append(orUnknown(detail.getUsername()));
        if (StringUtils.hasText(detail.getNickname())) {
            sb.append("，昵称=").append(detail.getNickname());
        }
        sb.append("，租户=").append(orUnknown(firstText(detail.getTenantId(), tenantId)));
        if (StringUtils.hasText(detail.getOrgName()) || StringUtils.hasText(detail.getOrgFullName())) {
            sb.append("，组织=").append(orUnknown(firstText(detail.getOrgFullName(), detail.getOrgName())));
        }
        List<String> roles = detail.getRoles();
        if (roles != null && !roles.isEmpty()) {
            sb.append("，角色=").append(String.join("、", roles));
        }
        sb.append("，账户状态=").append(accountState(detail));
        if (StringUtils.hasText(detail.getMobile())) {
            sb.append("，手机=").append(detail.getMobile());
        }
        if (StringUtils.hasText(detail.getEmail())) {
            sb.append("，邮箱=").append(detail.getEmail());
        }
        sb.append("，会话ID=").append(orUnknown(sessionId));
        sb.append("，应用ID=").append(orUnknown(appId));
        sb.append("。");
        return sb.toString();
    }

    private static String accountState(RemoteUser detail) {
        if (!detail.isEnabled()) {
            return "已禁用";
        }
        if (detail.isLocked()) {
            return "已锁定";
        }
        return "正常";
    }

    private static String firstText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    private static String orUnknown(String value) {
        return StringUtils.hasText(value) ? value : "未知";
    }
}
