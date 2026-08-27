package com.lambda.fusion.ai.runtime.sandbox;

import com.lambda.fusion.ai.AiConstants.SandboxBackend;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 根据应用的 {@code sandboxBackend} 选择 {@link SandboxBackendProvider} 并创建沙箱文件系统配置。
 * 使用 HOST 时返回 {@link Optional#empty()}；应用显式选择沙箱后端后，扩展缺失或创建失败均拒绝构建，
 * 禁止把本应隔离的执行静默降级到宿主文件系统。
 *
 * @author Jin
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class SandboxSpecResolver {

    private final List<SandboxBackendProvider> providers;

    public Optional<SandboxFilesystemSpec> resolve(AppEntity app, Path hostWorkspace) {
        SandboxBackend backend =
                SandboxBackend.of(StringUtils.defaultIfBlank(app.getSandboxBackend(), SandboxBackend.HOST.getCode()));
        if (backend == null || backend == SandboxBackend.HOST) {
            return Optional.empty();
        }
        Optional<SandboxFilesystemSpec> spec;
        try {
            spec = providers.stream()
                    .filter(p -> p.backend() == backend)
                    .findFirst()
                    .map(p -> p.create(app, hostWorkspace));
        } catch (Exception e) {
            log.error("沙箱后端 {} 创建失败", backend, e);
            throw new AiBusinessException(
                    AiErrorCode.CONFIGURATION_ERROR, "沙箱后端 " + backend + " 创建失败: " + safeMessage(e));
        }
        if (spec.isEmpty()) {
            throw new AiBusinessException(AiErrorCode.CONFIGURATION_ERROR, "沙箱后端 " + backend + " 的扩展未安装");
        }
        return spec;
    }

    private static String safeMessage(Exception error) {
        return StringUtils.defaultIfBlank(error.getMessage(), error.getClass().getSimpleName());
    }

    /** 解析沙箱隔离粒度；配置为空或非法时使用 {@link IsolationScope#AGENT}。 */
    public static IsolationScope parseIsolationScope(AiProperties aiProperties) {
        try {
            return IsolationScope.valueOf(
                    StringUtils.defaultIfBlank(aiProperties.getSandbox().getIsolationScope(), "AGENT")
                            .toUpperCase());
        } catch (Exception e) {
            return IsolationScope.AGENT;
        }
    }
}
