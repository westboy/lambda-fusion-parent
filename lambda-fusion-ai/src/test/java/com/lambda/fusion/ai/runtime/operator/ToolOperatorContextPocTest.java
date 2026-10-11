package com.lambda.fusion.ai.runtime.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.SaTokenContextException;
import cn.dev33.satoken.stp.StpUtil;
import com.lambda.cloud.core.Constants;
import com.lambda.cloud.mybatis.tenant.TenantContextHolder;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.core.utils.AuthUtils;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * run-as PoC：验证「阶段边界捕获 token → 工具线程绑定 → 会话语义生效」的组装行为（命题 A + fail-closed/清理）。
 *
 * <p>无 Web 容器：登录阶段包在 mock 上下文内（sa-token 1.46 默认内存 {@code SaTokenDao}），
 * token 会话写入 {@code loginUser} 与 {@code CommonAuthenticationSuccessHandler} 同契约；
 * 断言在独立线程池线程上执行，模拟 Agent 异步工具线程。
 *
 * @author zx
 */
class ToolOperatorContextPocTest {

    private static final String RUN_ID = "poc-run";

    @BeforeAll
    static void setUpSaTokenConfig() {
        SaManager.getConfig().setTokenName("Authorization");
        SaManager.getConfig().setTokenPrefix("Bearer");
    }

    @AfterAll
    static void restoreSaTokenConfig() {
        SaManager.getConfig().setTokenName("satoken");
        SaManager.getConfig().setTokenPrefix("");
        ToolOperatorContext.release(RUN_ID);
    }

    /** 最小登录：mock 上下文内 login 并向 token 会话写 {@code loginUser}，返回活跃 token。 */
    private static String loginAs(String username, String orgId, String tenantId) {
        return SaTokenContextMockUtil.setMockContext(() -> {
            StpUtil.login(username);
            UserDetails user = new UserDetails();
            user.setUsername(username);
            user.setOrgId(orgId);
            user.setTenantId(tenantId);
            user.setRoles(Set.of("admin"));
            String token = StpUtil.getTokenValueByLoginId(username);
            StpUtil.stpLogic.getTokenSessionByToken(token).set(Constants.LOGIN_USER, user);
            return token;
        });
    }

    /** 命题 A：跨线程绑定后 AuthUtils.getUser() 返回与登录态逐字段一致的身份，租户上下文同步生效。 */
    @Test
    void capturedTokenBindsIdentityAndTenantOnAgentThread() throws Exception {
        String token = loginAs("tester", "org-1", "tenant-1");
        ToolOperatorContext.capture(RUN_ID, token);
        AtomicReference<String> tenantInView = new AtomicReference<>();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            UserDetails bound = pool.submit(() -> ToolOperatorContext.callAs(RUN_ID, "tenant-1", () -> {
                        tenantInView.set(TenantContextHolder.getCurrentTenantId());
                        return AuthUtils.getUser();
                    }))
                    .get(5, TimeUnit.SECONDS);
            assertThat(bound).isNotNull();
            assertThat(bound.getUsername()).isEqualTo("tester");
            assertThat(bound.getOrgId()).isEqualTo("org-1");
            assertThat(bound.getTenantId()).isEqualTo("tenant-1");
            assertThat(bound.getRoles()).containsExactly("admin");
            assertThat(tenantInView).hasValue("tenant-1");
        } finally {
            pool.shutdownNow();
            ToolOperatorContext.release(RUN_ID);
        }
    }

    /** fail-closed：Run 未捕获 token → 拒绝执行，不触达任何会话查询。 */
    @Test
    void failsClosedWithoutCapturedToken() {
        assertThatThrownBy(() -> ToolOperatorContext.callAs("run-unknown", "tenant-1", AuthUtils::getUser))
                .isInstanceOf(AiBusinessException.class);
    }

    /** fail-closed：登出（会话失效）后绑定 → 会话解析抛 NotLoginException，绝不降级为匿名。 */
    @Test
    void failsClosedAfterLogout() throws Exception {
        String token = loginAs("leaver", "org-1", "tenant-1");
        ToolOperatorContext.capture(RUN_ID, token);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            StpUtil.logout("leaver");
            assertThatThrownBy(
                            () -> pool.submit(() -> ToolOperatorContext.callAs(RUN_ID, "tenant-1", AuthUtils::getUser))
                                    .get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(NotLoginException.class);
        } finally {
            pool.shutdownNow();
            ToolOperatorContext.release(RUN_ID);
        }
    }

    /** 清理语义：同一池化线程复用，上一任务绑定/租户不残留；无上下文时 Sa-Token 直接抛 SaTokenContextException。 */
    @Test
    void scopeCleanupLeavesNoResidueOnPooledThread() throws Exception {
        String token = loginAs("tester", "org-1", "tenant-1");
        ToolOperatorContext.capture(RUN_ID, token);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> ToolOperatorContext.callAs(RUN_ID, "tenant-1", AuthUtils::getUser))
                    .get(5, TimeUnit.SECONDS);
            assertThatThrownBy(
                            () -> pool.submit((Callable<?>) AuthUtils::getUser).get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(SaTokenContextException.class);
            assertThat(pool.submit(TenantContextHolder::getCurrentTenantId).get(5, TimeUnit.SECONDS))
                    .isNull();
        } finally {
            pool.shutdownNow();
            ToolOperatorContext.release(RUN_ID);
        }
    }

    /** 平台模式：操作人无租户归属且未指定目标租户 → 不绑租户，会话身份可用（跨租户元数据读合法）。 */
    @Test
    void platformOperatorWithoutTenantRunsPlatformMode() throws Exception {
        String token = loginAs("platform-admin", null, null);
        ToolOperatorContext.capture(RUN_ID, token);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<String> tenantInView = new AtomicReference<>();
            pool.submit(() -> ToolOperatorContext.callAs(RUN_ID, null, () -> {
                        tenantInView.set(TenantContextHolder.getCurrentTenantId());
                        return AuthUtils.getUser();
                    }))
                    .get(5, TimeUnit.SECONDS);
            assertThat(tenantInView).hasValue(null);
        } finally {
            pool.shutdownNow();
            ToolOperatorContext.release(RUN_ID);
        }
    }

    /** 租户用户未指定目标 → 自动回落到操作人自身租户（防 fail-open 的兜底绑定）。 */
    @Test
    void tenantUserWithoutTargetAutoBindsOwnTenant() throws Exception {
        String token = loginAs("tenant-user", "org-1", "tenant-9");
        ToolOperatorContext.capture(RUN_ID, token);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<String> tenantInView = new AtomicReference<>();
            pool.submit(() -> ToolOperatorContext.callAs(RUN_ID, null, () -> {
                        tenantInView.set(TenantContextHolder.getCurrentTenantId());
                        return AuthUtils.getUser();
                    }))
                    .get(5, TimeUnit.SECONDS);
            assertThat(tenantInView).hasValue("tenant-9");
        } finally {
            pool.shutdownNow();
            ToolOperatorContext.release(RUN_ID);
        }
    }

    /** 跨租户越权：租户归属操作者显式指定其它租户 → 拒绝（判据权威 = 会话操作人自身归属）。 */
    @Test
    void tenantUserEscalationToOtherTenantIsRejected() throws Exception {
        String token = loginAs("tenant-user", "org-1", "tenant-9");
        ToolOperatorContext.capture(RUN_ID, token);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            assertThatThrownBy(
                            () -> pool.submit(() -> ToolOperatorContext.callAs(RUN_ID, "tenant-1", AuthUtils::getUser))
                                    .get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AiBusinessException.class);
        } finally {
            pool.shutdownNow();
            ToolOperatorContext.release(RUN_ID);
        }
    }

    /** 代维模式：平台操作者显式指定目标租户 → 绑定目标租户执行。 */
    @Test
    void platformOperatorDelegationBindsTargetTenant() throws Exception {
        String token = loginAs("platform-admin", null, null);
        ToolOperatorContext.capture(RUN_ID, token);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<String> tenantInView = new AtomicReference<>();
            pool.submit(() -> ToolOperatorContext.callAs(RUN_ID, "tenant-1", () -> {
                        tenantInView.set(TenantContextHolder.getCurrentTenantId());
                        return AuthUtils.getUser();
                    }))
                    .get(5, TimeUnit.SECONDS);
            assertThat(tenantInView).hasValue("tenant-1");
        } finally {
            pool.shutdownNow();
            ToolOperatorContext.release(RUN_ID);
        }
    }
}
