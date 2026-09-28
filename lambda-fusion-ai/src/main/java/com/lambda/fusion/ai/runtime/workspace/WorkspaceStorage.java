package com.lambda.fusion.ai.runtime.workspace;

import cn.hutool.crypto.digest.MD5;
import com.lambda.fusion.ai.AiConstants.StateStoreType;
import com.lambda.fusion.ai.AiConstants.WorkspaceStorageType;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.runtime.sandbox.SandboxSpecResolver;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Workspace 存储唯一解析入口。存储类型由部署配置统一决定，不随应用变化：
 * LOCAL 保留原本地目录；MYSQL/POSTGRES 用 AgentScope 分布式存储。切换配置只换另一套
 * Workspace，不复制、迁移或删除原数据。
 *
 * @author Jin
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceStorage {

    private final AiProperties aiProperties;
    private final WorkspacePaths workspacePaths;
    private final WorkspaceScaffolder workspaceScaffolder;
    private final List<WorkspaceDistributedStoreProvider> providers;

    private WorkspaceStorageType type;
    private DistributedStore distributedStore;
    private BaseStore baseStore;

    @PostConstruct
    public void initialize() {
        String configuredType = aiProperties.getWorkspace().getStorage().getType();
        type = WorkspaceStorageType.of(
                StringUtils.defaultIfBlank(configuredType, WorkspaceStorageType.LOCAL.getCode()));
        if (type == null) {
            throw new IllegalStateException("未知的 Workspace 存储类型: " + configuredType);
        }
        if (type == WorkspaceStorageType.LOCAL) {
            log.info("AgentScope Workspace 使用本地存储；该模式仅适用于单节点部署");
            return;
        }

        validateDistributedStateStore();
        WorkspaceDistributedStoreProvider provider = providers.stream()
                .filter(candidate -> candidate.type() == type)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Workspace 存储类型 " + type + " 的 AgentScope 扩展未安装"));
        try {
            distributedStore = Objects.requireNonNull(provider.create(), "distributedStore");
            baseStore = Objects.requireNonNull(distributedStore.baseStore(), "baseStore");
            log.info("AgentScope Workspace 使用 {} 分布式存储", type);
        } catch (Exception e) {
            throw new IllegalStateException("Workspace 分布式存储 " + type + " 初始化失败", e);
        }
    }

    public WorkspaceStorageType type() {
        return type;
    }

    public boolean isDistributed() {
        return type != WorkspaceStorageType.LOCAL;
    }

    /**
     * 当前远程 Workspace 使用的统一分布式存储；LOCAL 模式返回空。
     */
    public Optional<DistributedStore> distributedStore() {
        return Optional.ofNullable(distributedStore);
    }

    /**
     * 初始化当前配置对应的 Workspace 模板目录；已存在文件不会被覆盖。
     */
    public Path initializeWorkspace(String tenantId, AppEntity app) {
        Path workspace = workspacePaths.resolveAppWorkspace(tenantId, app.getId(), type);
        try {
            workspaceScaffolder.scaffold(workspace, app);
            return workspace;
        } catch (IOException e) {
            throw new AiBusinessException(AiErrorCode.CONFIGURATION_ERROR, e);
        }
    }

    /**
     * 配置 AgentScope 文件系统。沙箱应用仍使用沙箱文件系统；分布式存储同时为其提供快照与执行锁。
     */
    public void configureFilesystem(
            HarnessAgent.Builder builder, Optional<SandboxFilesystemSpec> sandboxSpec, Path workspace, String agentId) {
        if (isDistributed()) {
            builder.distributedStore(distributedStore);
        }
        if (sandboxSpec.isPresent()) {
            builder.filesystem(sandboxSpec.get());
        } else if (isDistributed()) {
            builder.abstractFilesystem(distributedFilesystem(workspace, agentId));
        } else {
            builder.filesystem(
                    new LocalFilesystemSpec().isolationScope(SandboxSpecResolver.parseIsolationScope(aiProperties)));
        }
    }

    /**
     * 为管理与审计链路打开 Agent 使用的远程文件系统；无用户上下文时记忆使用默认用户命名空间。
     */
    public AbstractFilesystem openDistributedFilesystem(String tenantId, AppEntity app, Path workspace) {
        if (!isDistributed()) {
            throw new IllegalStateException("LOCAL Workspace 不应打开远程文件系统");
        }
        return distributedFilesystem(workspace, stableAgentId(app.getId(), tenantId));
    }

    /**
     * Agent ID 是状态存储与远程 Workspace 命名空间的共同稳定标识。
     */
    public String stableAgentId(String appId, String tenantId) {
        return MD5.create().digestHex("app:" + appId + ":t:" + tenantId);
    }

    /**
     * 使用应用级短锁串行化跨节点的 Workspace 管理写入。
     */
    public <T> T withWriteLock(String tenantId, AppEntity app, Callable<T> operation) throws IOException {
        if (!isDistributed()) {
            return call(operation);
        }
        SandboxIsolationKey key = SandboxIsolationKey.resolve(
                        io.agentscope.harness.agent.IsolationScope.AGENT, null, stableAgentId(app.getId(), tenantId))
                .orElseThrow(() -> new IOException("无法解析 Workspace 分布式锁标识"));
        try (SandboxLease ignored = distributedStore.sandboxExecutionGuard().tryEnter(key)) {
            return call(operation);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待 Workspace 分布式锁时被中断", e);
        }
    }

    private AbstractFilesystem distributedFilesystem(Path workspace, String agentId) {
        return WorkspaceRemoteFilesystemFactory.create(baseStore, workspace, agentId);
    }

    private <T> T call(Callable<T> operation) throws IOException {
        try {
            return operation.call();
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Workspace 存储操作失败", e);
        }
    }

    /** 分布式 Workspace 要求 Agent 状态存储同样跨节点共享，否则 HITL 跨节点确认与渠道记忆会静默失败。 */
    private void validateDistributedStateStore() {
        String configured = aiProperties.getStateStore().getType();
        StateStoreType stateStoreType = StateStoreType.of(configured);
        if (stateStoreType != null && stateStoreType.isDistributed()) {
            return;
        }
        throw new IllegalStateException(
                "远程 Workspace 要求分布式 Agent 状态存储（MYSQL/POSTGRES/REDIS/OSS/COS），当前 state-store.type="
                        + StringUtils.defaultIfBlank(configured, StateStoreType.MEMORY.getCode()));
    }
}
