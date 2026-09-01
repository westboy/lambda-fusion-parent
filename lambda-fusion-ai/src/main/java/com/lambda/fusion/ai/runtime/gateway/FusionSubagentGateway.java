package com.lambda.fusion.ai.runtime.gateway;

import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.runtime.AgentFactory;
import com.lambda.fusion.ai.runtime.event.ConfigChangedEvent;
import com.lambda.fusion.ai.runtime.workspace.WorkspaceStorage;
import com.lambda.fusion.core.utils.TenantUtils;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.harness.agent.gateway.SubagentMaterializer;
import io.agentscope.harness.agent.gateway.SubagentRecord;
import io.agentscope.harness.agent.gateway.SubagentRegistry;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Lambda Fusion 子 Agent 发布、鉴权与跨节点恢复入口。仅使用 AgentScope 已公开的 {@link SubagentRegistry}、
 * {@link SubagentMaterializer} 和 {@link HarnessGateway} 接口，不修改 AgentScope 源码。暴露事件到达后把应用、
 * 租户、用户和父会话补入共享记录；直接对话必须经本类校验并恢复调用上下文。
 */
@Slf4j
public final class FusionSubagentGateway implements SubagentMaterializer {

    private static final String ID_PREFIX = "fusion:v1:";
    private static final String ID_SEPARATOR = ".";
    private final ObjectProvider<AgentFactory> agentFactoryProvider;
    private final WorkspaceStorage workspaceStorage;
    private final SubagentRegistry registry;
    private final ConcurrentHashMap<String, ResolvedSubagent> resolvedAgents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Semaphore> localTurnLocks = new ConcurrentHashMap<>();

    public FusionSubagentGateway(
            ObjectProvider<AgentFactory> agentFactoryProvider,
            WorkspaceStorage workspaceStorage,
            SubagentRegistry registry) {
        this.agentFactoryProvider = Objects.requireNonNull(agentFactoryProvider, "agentFactoryProvider");
        this.workspaceStorage = Objects.requireNonNull(workspaceStorage, "workspaceStorage");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** 让父 Agent 的内部暴露网关与系统共享同一注册表。 */
    public void configureAgent(HarnessAgent agent) {
        Objects.requireNonNull(agent, "agent");
        if (agent.getSubagentAgentManager() == null) {
            return;
        }
        HarnessGateway ownerGateway = agent.gateway();
        ownerGateway.setSubagentRegistry(registry);
    }

    /**
     * 使用当前业务对话身份补全 AgentScope 暴露记录：{@code agentId} 被编码为应用、租户与真实子 Agent 名称的组合，
     * 解决不同应用声明同名子 Agent 时恢复到错误父 Agent 的问题。
     */
    public void recordExposure(
            SubagentExposedEvent event, String appId, String tenantId, String userId, String parentSessionId) {
        Objects.requireNonNull(event, "event");
        if (StringUtils.isAnyBlank(
                event.getSubagentId(), event.getAgentId(), event.getSessionId(), appId, tenantId, userId)) {
            log.warn("忽略缺少应用、租户、用户或会话身份的子 Agent 暴露事件");
            return;
        }
        SubagentRecord previous = registry.find(event.getSubagentId()).orElse(null);
        Instant createdAt = previous != null && previous.createdAt() != null ? previous.createdAt() : Instant.now();
        Instant expiresAt = previous != null ? previous.expiresAt() : null;
        SubagentRecord completed = new SubagentRecord(
                event.getSubagentId(),
                encodeIdentity(appId, tenantId, event.getAgentId()),
                event.getSessionId(),
                userId,
                parentSessionId,
                createdAt,
                expiresAt);
        registry.register(completed);
        resolvedAgents.remove(event.getSubagentId());
    }

    @Override
    public Optional<Agent> materialize(String agentId, RuntimeContext parentRc) {
        Optional<SubagentIdentity> identity = decodeIdentity(agentId);
        if (identity.isEmpty()) {
            return Optional.empty();
        }
        SubagentIdentity target = identity.get();
        return TenantUtils.withTenant(target.tenantId(), () -> {
            HarnessAgent parent =
                    agentFactoryProvider.getObject().getOrBuildForChatRun(target.appId(), target.tenantId());
            String expectedOwner = workspaceStorage.stableAgentId(target.appId(), target.tenantId());
            if (!Objects.equals(expectedOwner, parent.getAgentId())) {
                log.warn("拒绝从错误的父 Agent 恢复子 Agent: app={}, tenant={}", target.appId(), target.tenantId());
                return Optional.empty();
            }
            DefaultAgentManager manager = parent.getSubagentAgentManager();
            return manager != null ? manager.createAgentIfPresent(target.childAgentId(), parentRc) : Optional.empty();
        });
    }

    /** 经过应用、租户和用户校验后执行一次非流式子 Agent 对话。 */
    public Mono<Msg> runSubagent(String subagentId, String appId, String tenantId, String userId, List<Msg> messages) {
        List<Msg> request = List.copyOf(Objects.requireNonNull(messages, "messages"));
        return withSerializedTurn(subagentId, () -> {
            ResolvedSubagent resolved = resolveAuthorized(subagentId, appId, tenantId, userId);
            return invoke(resolved.agent(), request, resolved.runtimeContext());
        });
    }

    /** 经过应用、租户和用户校验后执行一次流式子 Agent 对话。 */
    public Flux<AgentEvent> runSubagentStream(
            String subagentId, String appId, String tenantId, String userId, List<Msg> messages) {
        List<Msg> request = List.copyOf(Objects.requireNonNull(messages, "messages"));
        return withSerializedStream(subagentId, () -> {
            ResolvedSubagent resolved = resolveAuthorized(subagentId, appId, tenantId, userId);
            return stream(resolved.agent(), request, resolved.runtimeContext());
        });
    }

    /** 配置变更时同步清除受影响的本节点子 Agent 缓存。 */
    @EventListener
    public void onConfigChanged(ConfigChangedEvent event) {
        if (event.appId() == null) {
            invalidateAll();
        } else {
            invalidateApp(event.appId());
        }
    }

    private void invalidateApp(String appId) {
        if (StringUtils.isBlank(appId)) {
            return;
        }
        resolvedAgents.entrySet().removeIf(entry -> decodeIdentity(
                        entry.getValue().record().agentId())
                .map(identity -> appId.equals(identity.appId()))
                .orElse(false));
    }

    private void invalidateAll() {
        resolvedAgents.clear();
    }

    private ResolvedSubagent resolveAuthorized(String subagentId, String appId, String tenantId, String userId) {
        if (StringUtils.isAnyBlank(subagentId, appId, tenantId, userId)) {
            throw new AiBusinessException(AiErrorCode.SUB_AGENT_SESSION_UNAVAILABLE);
        }
        SubagentRecord record = registry.find(subagentId)
                .orElseThrow(() -> new AiBusinessException(AiErrorCode.SUB_AGENT_SESSION_UNAVAILABLE));
        SubagentIdentity identity = decodeIdentity(record.agentId())
                .filter(target -> appId.equals(target.appId()) && tenantId.equals(target.tenantId()))
                .orElseThrow(() -> new AiBusinessException(AiErrorCode.SUB_AGENT_SESSION_UNAVAILABLE));
        if (!userId.equals(record.userId())) {
            throw new AiBusinessException(AiErrorCode.SUB_AGENT_SESSION_UNAVAILABLE);
        }

        ResolvedSubagent cached = resolvedAgents.get(subagentId);
        if (cached != null && sameRecipe(cached.record(), record)) {
            return cached;
        }

        RuntimeContext runtimeContext = RuntimeContext.builder()
                .sessionId(record.sessionId())
                .userId(record.userId())
                .put(RuntimeProperty.KEY_APP_ID, identity.appId())
                .put(RuntimeProperty.KEY_TENANT_ID, identity.tenantId())
                .put("parentSessionId", record.parentSessionId())
                .build();
        Agent agent = materialize(record.agentId(), runtimeContext)
                .orElseThrow(() -> new AiBusinessException(AiErrorCode.SUB_AGENT_RECOVERY_FAILED, subagentId));
        ResolvedSubagent resolved = new ResolvedSubagent(record, agent, runtimeContext);
        resolvedAgents.put(subagentId, resolved);
        return resolved;
    }

    private static boolean sameRecipe(SubagentRecord left, SubagentRecord right) {
        return Objects.equals(left.agentId(), right.agentId())
                && Objects.equals(left.sessionId(), right.sessionId())
                && Objects.equals(left.userId(), right.userId())
                && Objects.equals(left.parentSessionId(), right.parentSessionId());
    }

    private Mono<Msg> invoke(Agent agent, List<Msg> messages, RuntimeContext context) {
        if (agent instanceof HarnessAgent harnessAgent) {
            return harnessAgent.call(messages, context);
        }
        if (agent instanceof ReActAgent reactAgent) {
            return reactAgent.call(messages, context);
        }
        return agent.call(messages);
    }

    private Flux<AgentEvent> stream(Agent agent, List<Msg> messages, RuntimeContext context) {
        if (agent instanceof HarnessAgent harnessAgent) {
            return harnessAgent.streamEvents(messages, context);
        }
        if (agent instanceof ReActAgent reactAgent) {
            return reactAgent.streamEvents(messages, context);
        }
        return Flux.error(new AiBusinessException(
                AiErrorCode.OPERATION_NOT_SUPPORTED,
                "Agent type " + agent.getClass().getName() + " does not support streaming"));
    }

    private Mono<Msg> withSerializedTurn(String subagentId, Supplier<Mono<Msg>> source) {
        return Mono.using(() -> acquireTurnLease(subagentId), ignored -> Mono.defer(source::get), SandboxLease::close)
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<AgentEvent> withSerializedStream(String subagentId, Supplier<Flux<AgentEvent>> source) {
        return Flux.using(() -> acquireTurnLease(subagentId), ignored -> Flux.defer(source::get), SandboxLease::close)
                .subscribeOn(Schedulers.boundedElastic());
    }

    private SandboxLease acquireTurnLease(String subagentId) throws InterruptedException {
        if (StringUtils.isBlank(subagentId)) {
            throw new AiBusinessException(AiErrorCode.SUB_AGENT_SESSION_UNAVAILABLE);
        }
        RuntimeContext lockContext = RuntimeContext.builder()
                .sessionId("fusion-subagent:" + subagentId)
                .build();
        SandboxIsolationKey lockKey = SandboxIsolationKey.resolve(
                        IsolationScope.SESSION, lockContext, "fusion-subagent")
                .orElseThrow(() -> new AiBusinessException(AiErrorCode.CONFIGURATION_ERROR, "无法生成子 Agent 执行锁"));
        try {
            Optional<DistributedStore> distributedStore = workspaceStorage.distributedStore();
            if (distributedStore.isPresent()) {
                SandboxExecutionGuard guard = distributedStore.get().sandboxExecutionGuard();
                return guard.tryEnter(lockKey);
            }
            Semaphore localLock = localTurnLocks.computeIfAbsent(subagentId, ignored -> new Semaphore(1, true));
            localLock.acquire();
            AtomicBoolean released = new AtomicBoolean();
            return () -> {
                if (released.compareAndSet(false, true)) {
                    localLock.release();
                }
            };
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    private static String encodeIdentity(String appId, String tenantId, String childAgentId) {
        return ID_PREFIX
                + encodePart(appId)
                + ID_SEPARATOR
                + encodePart(tenantId)
                + ID_SEPARATOR
                + encodePart(childAgentId);
    }

    private static Optional<SubagentIdentity> decodeIdentity(String value) {
        if (value == null || !value.startsWith(ID_PREFIX)) {
            return Optional.empty();
        }
        String[] parts = value.substring(ID_PREFIX.length()).split("\\.", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            SubagentIdentity identity =
                    new SubagentIdentity(decodePart(parts[0]), decodePart(parts[1]), decodePart(parts[2]));
            return StringUtils.isNoneBlank(identity.appId(), identity.tenantId(), identity.childAgentId())
                    ? Optional.of(identity)
                    : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static String encodePart(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodePart(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private record SubagentIdentity(String appId, String tenantId, String childAgentId) {}

    private record ResolvedSubagent(SubagentRecord record, Agent agent, RuntimeContext runtimeContext) {}
}
