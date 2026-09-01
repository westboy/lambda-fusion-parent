package com.lambda.fusion.ai.runtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.cloud.mybatis.tenant.TenantContextHolder;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.runtime.AgentFactory;
import com.lambda.fusion.ai.runtime.workspace.WorkspaceStorage;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.harness.agent.gateway.InMemorySubagentRegistry;
import io.agentscope.harness.agent.gateway.SubagentRecord;
import io.agentscope.harness.agent.gateway.SubagentRegistry;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

class FusionSubagentGatewayTest {

    private static final String APP_ID = "app-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String USER_ID = "user-1";
    private static final String OWNER_AGENT_ID = "owner-agent-1";

    @AfterEach
    void clearTenant() {
        TenantContextHolder.getInstance().close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void recoversOnAnotherNodeWithTheRecordedBusinessAndUserContext() throws Exception {
        ObjectProvider<AgentFactory> provider = mock(ObjectProvider.class);
        AgentFactory factory = mock(AgentFactory.class);
        WorkspaceStorage storage = mock(WorkspaceStorage.class);
        SubagentRegistry registry = new InMemorySubagentRegistry();
        HarnessAgent parent = mock(HarnessAgent.class);
        HarnessAgent child = mock(HarnessAgent.class);
        DefaultAgentManager manager = mock(DefaultAgentManager.class);
        DistributedStore distributedStore = mock(DistributedStore.class);
        SandboxExecutionGuard executionGuard = mock(SandboxExecutionGuard.class);
        SandboxLease lease = mock(SandboxLease.class);
        Msg request = mock(Msg.class);
        Msg reply = mock(Msg.class);
        AtomicReference<RuntimeContext> materializeContext = new AtomicReference<>();

        when(provider.getObject()).thenReturn(factory);
        when(storage.stableAgentId(APP_ID, TENANT_ID)).thenReturn(OWNER_AGENT_ID);
        when(storage.distributedStore()).thenReturn(Optional.of(distributedStore));
        when(distributedStore.sandboxExecutionGuard()).thenReturn(executionGuard);
        when(executionGuard.tryEnter(any(SandboxIsolationKey.class))).thenReturn(lease);
        when(factory.getOrBuildForChatRun(APP_ID, TENANT_ID)).thenReturn(parent);
        when(parent.getAgentId()).thenReturn(OWNER_AGENT_ID);
        when(parent.getSubagentAgentManager()).thenReturn(manager);
        when(manager.createAgentIfPresent(eq("worker"), any(RuntimeContext.class)))
                .thenAnswer(invocation -> {
                    assertThat(TenantContextHolder.getCurrentTenantId()).isEqualTo(TENANT_ID);
                    materializeContext.set(invocation.getArgument(1));
                    return Optional.of(child);
                });
        when(child.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(reply));
        FusionSubagentGateway nodeA = new FusionSubagentGateway(provider, storage, registry);
        FusionSubagentGateway nodeB = new FusionSubagentGateway(provider, storage, registry);

        nodeA.recordExposure(
                new SubagentExposedEvent("sub-1", "worker", "child-session", "Worker"),
                APP_ID,
                TENANT_ID,
                USER_ID,
                "parent-session");

        assertThat(nodeB.runSubagent("sub-1", APP_ID, TENANT_ID, USER_ID, List.of(request))
                        .block())
                .isSameAs(reply);
        SubagentRecord stored = registry.find("sub-1").orElseThrow();
        assertThat(stored.agentId()).startsWith("fusion:v1:");
        assertThat(stored.userId()).isEqualTo(USER_ID);
        assertThat(stored.parentSessionId()).isEqualTo("parent-session");
        assertThat(materializeContext.get().getUserId()).isEqualTo(USER_ID);
        assertThat(materializeContext.get().getSessionId()).isEqualTo("child-session");

        ArgumentCaptor<RuntimeContext> invocationContext = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(child).call(eq(List.of(request)), invocationContext.capture());
        assertThat(invocationContext.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(invocationContext.getValue().get(RuntimeProperty.KEY_APP_ID, String.class))
                .isEqualTo(APP_ID);
        verify(executionGuard).tryEnter(any(SandboxIsolationKey.class));
        verify(lease).close();
        assertThat(TenantContextHolder.getCurrentTenantId()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsAnotherUserBeforeMaterializingTheAgent() {
        ObjectProvider<AgentFactory> provider = mock(ObjectProvider.class);
        WorkspaceStorage storage = mock(WorkspaceStorage.class);
        when(storage.distributedStore()).thenReturn(Optional.empty());
        FusionSubagentGateway gateway = new FusionSubagentGateway(provider, storage, new InMemorySubagentRegistry());
        gateway.recordExposure(
                new SubagentExposedEvent("sub-1", "worker", "child-session", null),
                APP_ID,
                TENANT_ID,
                USER_ID,
                "parent-session");

        assertThatThrownBy(() -> gateway.runSubagent("sub-1", APP_ID, TENANT_ID, "user-2", List.of(mock(Msg.class)))
                        .block())
                .isInstanceOf(AiBusinessException.class)
                .satisfies(exception -> assertThat(((AiBusinessException) exception).getCode())
                        .isEqualTo(AiErrorCode.SUB_AGENT_SESSION_UNAVAILABLE.getCode()));
        verify(provider, never()).getObject();
    }

    @Test
    @SuppressWarnings("unchecked")
    void configuresTheParentInternalGatewayThroughPublicApis() {
        ObjectProvider<AgentFactory> provider = mock(ObjectProvider.class);
        WorkspaceStorage storage = mock(WorkspaceStorage.class);
        SubagentRegistry registry = new InMemorySubagentRegistry();
        HarnessAgent parent = mock(HarnessAgent.class);
        HarnessGateway ownerGateway = mock(HarnessGateway.class);
        when(parent.getSubagentAgentManager()).thenReturn(mock(DefaultAgentManager.class));
        when(parent.gateway()).thenReturn(ownerGateway);
        FusionSubagentGateway gateway = new FusionSubagentGateway(provider, storage, registry);

        gateway.configureAgent(parent);

        verify(ownerGateway).setSubagentRegistry(registry);
        verify(ownerGateway, never()).setSubagentMaterializer(any());
    }
}
