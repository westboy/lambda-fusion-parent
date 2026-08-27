package com.lambda.fusion.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.AppType;
import com.lambda.fusion.ai.AiConstants.StateStoreType;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.apps.service.AppService;
import com.lambda.fusion.ai.runtime.sandbox.SandboxSpecResolver;
import com.lambda.fusion.ai.runtime.state.StateStoreProvider;
import com.lambda.fusion.ai.runtime.workspace.WorkspaceStorage;
import com.lambda.fusion.ai.skill.runtime.SkillRepositoryResolver;
import com.lambda.fusion.ai.subagent.service.SubAgentService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

class AgentFactorySessionCleanupTest {

    @Test
    void shouldDeleteHostSessionThroughAgentScopeFilesystem(@TempDir Path workspaceRoot) {
        AppService appService = mock(AppService.class);
        WorkspaceStorage workspaceStorage = mock(WorkspaceStorage.class);
        SandboxSpecResolver sandboxSpecResolver = mock(SandboxSpecResolver.class);
        AppEntity app = chatApp();
        app.setEnabled(false);
        when(appService.loadById("app-1")).thenReturn(app);
        when(sandboxSpecResolver.resolve(same(app), isNull())).thenReturn(Optional.empty());

        ModelResolver modelResolver = mock(ModelResolver.class);
        ToolkitAssembler toolkitAssembler = mock(ToolkitAssembler.class);
        AgentFactory factory = factory(
                appService,
                workspaceStorage,
                sandboxSpecResolver,
                new AiProperties(),
                List.of(),
                modelResolver,
                toolkitAssembler);
        RuntimeContext runtimeContext =
                RuntimeContext.builder().userId("user-1").sessionId("session-1").build();
        var filesystem = new LocalFilesystemSpec()
                .mode(LocalFsMode.SANDBOXED)
                .toFilesystem(workspaceRoot, IsolationScope.SESSION.toNamespaceFactory());
        assertThat(filesystem
                        .uploadFiles(
                                runtimeContext,
                                List.of(Map.entry("outputs/report.txt", "report".getBytes(StandardCharsets.UTF_8))))
                        .getFirst()
                        .isSuccess())
                .isTrue();
        Path sessionRoot = workspaceRoot.resolve("session-1");
        assertThat(Files.exists(sessionRoot.resolve("outputs/report.txt"))).isTrue();

        String previousWorkspace = System.getProperty("agentscope.workspace");
        System.setProperty("agentscope.workspace", workspaceRoot.toString());
        try {
            factory.clearChatSessionWorkspace("app-1", "tenant-1", "user-1", "session-1");
        } finally {
            restoreWorkspaceProperty(previousWorkspace);
        }

        assertThat(Files.exists(sessionRoot)).isFalse();
        verifyNoInteractions(modelResolver, toolkitAssembler, workspaceStorage);
    }

    @Test
    void shouldClearSandboxResumeStateWithoutBuildingAgent() {
        AppService appService = mock(AppService.class);
        WorkspaceStorage workspaceStorage = mock(WorkspaceStorage.class);
        SandboxSpecResolver sandboxSpecResolver = mock(SandboxSpecResolver.class);
        AppEntity app = chatApp();
        when(appService.loadById("app-1")).thenReturn(app);
        when(workspaceStorage.stableAgentId("app-1", "tenant-1")).thenReturn("agent-1");
        when(sandboxSpecResolver.resolve(same(app), isNull()))
                .thenReturn(Optional.of(mock(SandboxFilesystemSpec.class)));

        AiProperties properties = new AiProperties();
        properties.getStateStore().setType(StateStoreType.REDIS.getCode());
        AgentStateStore stateStore = mock(AgentStateStore.class);
        StateStoreProvider stateStoreProvider = mock(StateStoreProvider.class);
        when(stateStoreProvider.type()).thenReturn(StateStoreType.REDIS);
        when(stateStoreProvider.create()).thenReturn(stateStore);
        ModelResolver modelResolver = mock(ModelResolver.class);
        ToolkitAssembler toolkitAssembler = mock(ToolkitAssembler.class);
        AgentFactory factory = factory(
                appService,
                workspaceStorage,
                sandboxSpecResolver,
                properties,
                List.of(stateStoreProvider),
                modelResolver,
                toolkitAssembler);

        factory.clearChatSessionWorkspace("app-1", "tenant-1", "user-1", "session-1");

        verify(stateStore).save(isNull(), eq("sandbox/session/session-1"), eq("_sandbox_state"), any(State.class));
        verifyNoInteractions(modelResolver, toolkitAssembler);
    }

    @Test
    void shouldKeepWorkspaceApplicationFiles() {
        AppService appService = mock(AppService.class);
        WorkspaceStorage workspaceStorage = mock(WorkspaceStorage.class);
        SandboxSpecResolver sandboxSpecResolver = mock(SandboxSpecResolver.class);
        AppEntity app = chatApp();
        app.setAppType(AppType.WORKSPACE.getCode());
        when(appService.loadById("app-1")).thenReturn(app);
        AgentFactory factory =
                factory(appService, workspaceStorage, sandboxSpecResolver, new AiProperties(), List.of());

        factory.clearChatSessionWorkspace("app-1", "tenant-1", "user-1", "session-1");

        verifyNoInteractions(workspaceStorage, sandboxSpecResolver);
    }

    private static AppEntity chatApp() {
        AppEntity app = new AppEntity();
        app.setId("app-1");
        app.setAppType(AppType.CHAT.getCode());
        return app;
    }

    private static void restoreWorkspaceProperty(String previousWorkspace) {
        if (previousWorkspace == null) {
            System.clearProperty("agentscope.workspace");
            return;
        }
        System.setProperty("agentscope.workspace", previousWorkspace);
    }

    private static AgentFactory factory(
            AppService appService,
            WorkspaceStorage workspaceStorage,
            SandboxSpecResolver sandboxSpecResolver,
            AiProperties properties,
            List<StateStoreProvider> stateStoreProviders) {
        return factory(
                appService,
                workspaceStorage,
                sandboxSpecResolver,
                properties,
                stateStoreProviders,
                mock(ModelResolver.class),
                mock(ToolkitAssembler.class));
    }

    @SuppressWarnings("unchecked")
    private static AgentFactory factory(
            AppService appService,
            WorkspaceStorage workspaceStorage,
            SandboxSpecResolver sandboxSpecResolver,
            AiProperties properties,
            List<StateStoreProvider> stateStoreProviders,
            ModelResolver modelResolver,
            ToolkitAssembler toolkitAssembler) {
        return new AgentFactory(
                appService,
                modelResolver,
                toolkitAssembler,
                properties,
                workspaceStorage,
                sandboxSpecResolver,
                stateStoreProviders,
                mock(SkillRepositoryResolver.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(SubAgentService.class));
    }
}
