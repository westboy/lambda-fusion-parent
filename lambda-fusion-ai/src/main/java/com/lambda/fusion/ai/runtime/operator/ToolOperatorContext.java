package com.lambda.fusion.ai.runtime.operator;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.context.mock.SaRequestForMock;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.lambda.cloud.mybatis.tenant.TenantContextHolder;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.core.utils.AuthUtils;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.util.StringUtils;

/**
 * 工具操作人上下文（run-as 通道）：把对话请求边界捕获的活跃 Sa-Token 绑定到 Agent 工具执行线程，
 * 使 Sa-Token 会话、租户插件与数据权限按发起对话的操作人语义工作。机制与安全边界见
 * docs/design/ai-console-copilot.md §4.3、docs/design/ai-form-data-query.md §4.5。
 *
 * <p>工作方式：对话的每个驱动请求（发消息/确认/挂起输入，均为已鉴权登录态）在阶段边界 {@link
 * #captureFromCurrentRequest} 捕获活跃 token；工具方法体内 {@link #callAs} 以「带 token 的 mock 请求」
 * 绑定 sa-token 线程上下文（{@code SaManager.getSaTokenContext()} 懒初始化默认即
 * {@code SaTokenContextForThreadLocal}，与 Web 请求经 ContextFilter 的绑定走同一通道）。
 * 会话失效/登出时 {@code AuthUtils.getUser()} 抛 {@code NotLoginException} —— fail-closed。
 *
 * <p>租户判据（三模式，契约 §5.1）：目标租户缺失时回落到操作人自身租户；操作人无租户归属
 * （开发者/平台管理员）即平台模式，不绑租户（全局表直接可用、租户表为跨租户读）；租户归属
 * 操作者指定其它租户 → 拒绝（跨租户越权）。区分「合法无租户」与「意外丢租户」的权威依据是
 * 绑定会话中操作人自身的租户归属，不采信客户端输入。
 *
 * <p>安全边界：token 仅内存持有（runId 键控），不进 {@code RuntimeContext}、不随 {@code ChatRunSnapshot}
 * 序列化、不落库/日志/出参（契约 §16）；角色与组织不存快照，执行时刻从绑定会话读取，与人工操作员同源。
 *
 * @author zx
 */
public final class ToolOperatorContext {

    private static final Map<String, String> TOKEN_REGISTRY = new ConcurrentHashMap<>();

    private ToolOperatorContext() {}

    /**
     * 从当前请求捕获活跃 token（供对话运行时在阶段边界调用）。无 Web 上下文（单测/系统任务）
     * 或请求未携带 token 时静默跳过——捕获是机会性增强，缺失由 {@link #callAs} 在执行期 fail-closed 兜底。
     *
     * @param runId 对话运行标识
     */
    public static void captureFromCurrentRequest(String runId) {
        try {
            capture(runId, StpUtil.getTokenValue());
        } catch (RuntimeException ignored) {
            // 无 sa-token 上下文（非 HTTP 线程）：无 token 可捕获
        }
    }

    /**
     * Run 阶段边界捕获/刷新活跃 token（每阶段刷新保证新鲜度）。
     *
     * @param runId 对话运行标识
     * @param token 该请求刚通过鉴权的活跃 Sa-Token 值（原始 token，不含前缀）
     */
    public static void capture(String runId, String token) {
        if (StringUtils.hasText(runId) && StringUtils.hasText(token)) {
            TOKEN_REGISTRY.put(runId, token);
        }
    }

    /** Run 终结或同 runId 重新开始时释放，避免登记表随 Run 无界增长。 */
    public static void release(String runId) {
        if (runId != null) {
            TOKEN_REGISTRY.remove(runId);
        }
    }

    /**
     * 工具体内绑定：在 body 执行期间绑定操作人 Sa-Token 会话与租户上下文。
     *
     * <p>绑定原料：mock 请求头注入 token（头名与前缀读全局 sa-token 配置，与真实请求解析口径一致）；
     * 复用操作人自己的活跃会话，不新建会话。未捕获 token（Run 未捕获/已释放/匿名对话）→ fail-closed，
     * 抛 {@link AiErrorCode#TOOL_OPERATOR_CONTEXT_UNAVAILABLE}，绝不降级为匿名执行。
     *
     * @param runId 对话运行标识（取登记表中的 token）
     * @param targetTenantId 目标租户：通常来自对话上下文；代维场景由操作者显式传入；null/空表示未指定
     * @param body 绑定作用域内的执行体
     * @return body 返回值
     */
    public static <T> T callAs(String runId, String targetTenantId, Supplier<T> body) {
        String token = TOKEN_REGISTRY.get(runId);
        if (!StringUtils.hasText(token)) {
            throw new AiBusinessException(AiErrorCode.TOOL_OPERATOR_CONTEXT_UNAVAILABLE);
        }
        String tokenName = SaManager.getConfig().getTokenName();
        String prefix = SaManager.getConfig().getTokenPrefix();
        String headerValue = StringUtils.hasLength(prefix) ? prefix + " " + token : token;
        return SaTokenContextMockUtil.setMockContext(() -> {
            if (SaHolder.getRequest() instanceof SaRequestForMock mockRequest) {
                mockRequest.headerMap.put(tokenName, headerValue);
            } else {
                throw new AiBusinessException(AiErrorCode.TOOL_OPERATOR_CONTEXT_UNAVAILABLE);
            }
            // 会话失效在此抛 NotLoginException —— fail-closed
            String effectiveTenantId = resolveTenantId(AuthUtils.getUser(), targetTenantId);
            if (StringUtils.hasText(effectiveTenantId)) {
                TenantContextHolder.getInstance().setTenantId(effectiveTenantId);
            }
            try {
                return body.get();
            } finally {
                TenantContextHolder.getInstance().close();
            }
        });
    }

    /**
     * 租户三模式判据：显式目标 > 操作人自身归属 > 平台模式；租户归属操作者指定其它租户 → 拒绝。
     */
    private static String resolveTenantId(UserDetails operator, String targetTenantId) {
        String ownTenantId = operator == null ? null : operator.getTenantId();
        String target = StringUtils.hasText(targetTenantId) ? targetTenantId : ownTenantId;
        if (StringUtils.hasText(target) && StringUtils.hasText(ownTenantId) && !target.equals(ownTenantId)) {
            // 租户归属操作者试图以其它租户身份执行 —— 跨租户越权，拒绝
            throw new AiBusinessException(AiErrorCode.TOOL_OPERATOR_CONTEXT_UNAVAILABLE);
        }
        return target;
    }
}
