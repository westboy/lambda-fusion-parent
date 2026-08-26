package com.lambda.fusion.ai.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.runtime.gateway.FusionSubagentGateway;
import com.lambda.fusion.ai.runtime.gateway.RuntimeProperty;
import com.lambda.fusion.ai.runtime.media.ExplicitToolCall;
import com.lambda.fusion.ai.runtime.media.ExplicitToolCallHook;
import com.lambda.fusion.ai.runtime.media.ExplicitToolCallMiddleware;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Agent 执行适配器测试。
 *
 * @author Jin
 */
class AgentExecutionAdapterTest {

    @TempDir
    Path workspace;

    @Test
    void shouldUseConversationSessionInDirectMode() {
        HarnessAgent agent = mock(HarnessAgent.class);
        ReActAgent delegate = mock(ReActAgent.class);
        when(agent.getDelegate()).thenReturn(delegate);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());
        when(delegate.getAgentState("user-1", "session-1")).thenReturn(stateWithAskingBlock());
        AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1", null);
        Msg message = userMessage();

        adapter.stream(message, null).blockLast();
        adapter.stream(message, null).blockLast();

        ArgumentCaptor<RuntimeContext> contextCaptor = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent, times(2)).streamEvents(any(Msg.class), contextCaptor.capture());
        List<RuntimeContext> contexts = contextCaptor.getAllValues();
        assertThat(contexts).allSatisfy(context -> {
            assertThat(context.getSessionId()).isEqualTo("session-1");
            assertThat(context.getUserId()).isEqualTo("user-1");
            assertThat(context.<String>get(RuntimeProperty.KEY_TENANT_ID)).isEqualTo("tenant-1");
            assertThat(context.<String>get(RuntimeProperty.KEY_APP_ID)).isEqualTo("app-1");
            assertThat(context.<String>get(RuntimeProperty.KEY_LF_SESSION_ID)).isEqualTo("session-1");
        });
        // 每次订阅都新建 RuntimeContext，不复用。
        assertThat(contexts.get(0)).isNotSameAs(contexts.get(1));
        assertThat(adapter.readAskingToolBlocks())
                .extracting(ToolUseBlock::getId)
                .containsExactly("call-1");

        adapter.interrupt();

        verify(delegate).interrupt("user-1", "session-1");
    }

    @Test
    void shouldRecordSubagentExposureWithBusinessSessionAsParent() {
        HarnessAgent agent = mock(HarnessAgent.class);
        FusionSubagentGateway subagentGateway = mock(FusionSubagentGateway.class);
        SubagentExposedEvent event = new SubagentExposedEvent("sub-1", "worker", "child-session", "Worker");
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.<AgentEvent>just(event));
        AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1", subagentGateway);

        adapter.stream(userMessage(), null).blockLast();

        // 父会话归属使用业务 ChatSession.id，而非 Gateway 派生的 gw-* 状态槽。
        verify(subagentGateway).recordExposure(event, "app-1", "tenant-1", "user-1", "session-1");
    }

    @Test
    void shouldLeaveWorkspaceConcurrencyToAgentScope() {
        HarnessAgent agent = mock(HarnessAgent.class);
        DistributedStore distributedStore = mock(DistributedStore.class);
        when(agent.getDistributedStore()).thenReturn(distributedStore);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());
        AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1", null);

        adapter.stream(userMessage(), null).blockLast();

        verify(distributedStore, never()).sandboxExecutionGuard();
    }

    @Test
    void shouldExecuteExplicitMediaRequestThroughAgentScopeToolkitWithoutCallingChatModel() {
        NeverCalledModel model = new NeverCalledModel();
        JsonFileAgentStateStore stateStore = new JsonFileAgentStateStore(workspace.resolve("state"));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ExplicitMediaTool());
        ToolUseBlock toolCall = ToolUseBlock.builder()
                .id("media-call-1")
                .name("test_text_to_image")
                .input(java.util.Map.of("prompt", "一只猫"))
                .content("{\"prompt\":\"一只猫\"}")
                .build();

        try (HarnessAgent agent = buildExplicitMediaAgent(model, toolkit, stateStore, null)) {
            AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1", null);

            List<AgentEvent> events =
                    adapter.stream(userMessage(), toolCall).collectList().block();

            assertThat(events)
                    .extracting(AgentEvent::getType)
                    .containsExactly(
                            AgentEventType.AGENT_START,
                            AgentEventType.TOOL_CALL_START,
                            AgentEventType.TOOL_CALL_DELTA,
                            AgentEventType.TOOL_CALL_END,
                            AgentEventType.TOOL_RESULT_START,
                            AgentEventType.TOOL_RESULT_TEXT_DELTA,
                            AgentEventType.TOOL_RESULT_END,
                            AgentEventType.AGENT_RESULT,
                            AgentEventType.AGENT_END);
            assertThat(model.invocations()).isZero();
            AgentResultEvent resultEvent = events.stream()
                    .filter(AgentResultEvent.class::isInstance)
                    .map(AgentResultEvent.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(resultEvent.getResult().getRole()).isEqualTo(MsgRole.TOOL);
            assertThat(resultEvent.getResult().getGenerateReason()).isEqualTo(GenerateReason.ACTING_STOP_REQUESTED);
            AgentState state = stateStore
                    .get("user-1", "session-1", "agent_state", AgentState.class)
                    .orElseThrow();
            assertThat(state.getContext())
                    .extracting(Msg::getRole)
                    .containsExactly(MsgRole.USER, MsgRole.ASSISTANT, MsgRole.TOOL);
            ToolUseBlock savedCall = state.getContext()
                    .get(1)
                    .getContentBlocks(ToolUseBlock.class)
                    .getFirst();
            assertThat(savedCall.getState()).isEqualTo(ToolCallState.ALLOWED);
            ToolResultBlock savedResult = state.getContext()
                    .get(2)
                    .getContentBlocks(ToolResultBlock.class)
                    .getFirst();
            assertThat(savedResult.getState()).isEqualTo(ToolResultState.SUCCESS);
            assertThat(savedResult.getOutput())
                    .singleElement()
                    .isInstanceOfSatisfying(
                            TextBlock.class, text -> assertThat(text.getText()).isEqualTo("generated:一只猫"));
        }
    }

    @Test
    void shouldResumeExplicitMediaRequestAfterPermissionConfirmationWithoutCallingChatModel() {
        NeverCalledModel model = new NeverCalledModel();
        JsonFileAgentStateStore stateStore = new JsonFileAgentStateStore(workspace.resolve("confirm-state"));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ExplicitMediaTool());
        String toolName = "test_text_to_image";
        PermissionContextState permissionContext = PermissionContextState.builder()
                .mode(PermissionMode.BYPASS)
                .addAskRule(toolName, new PermissionRule(toolName, null, PermissionBehavior.ASK, "test"))
                .build();
        ToolUseBlock toolCall = ToolUseBlock.builder()
                .id("media-confirm-1")
                .name(toolName)
                .input(Map.of("prompt", "一只猫"))
                .content("{\"prompt\":\"一只猫\"}")
                .build();

        try (HarnessAgent agent = buildExplicitMediaAgent(model, toolkit, stateStore, permissionContext)) {
            AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1", null);

            List<AgentEvent> askingEvents =
                    adapter.stream(userMessage(), toolCall).collectList().block();

            assertThat(askingEvents)
                    .extracting(AgentEvent::getType)
                    .contains(AgentEventType.REQUIRE_USER_CONFIRM, AgentEventType.AGENT_END);
            ToolUseBlock asking = adapter.readAskingToolBlocks().getFirst();
            assertThat(asking.getState()).isEqualTo(ToolCallState.ASKING);
            assertThat(ExplicitToolCall.isExplicit(asking)).isTrue();

            Msg confirmation = Msg.builderForRole(MsgRole.USER)
                    .textContent("[confirm]")
                    .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of(new ConfirmResult(true, asking))))
                    .build();
            List<AgentEvent> resumedEvents =
                    adapter.stream(confirmation, null).collectList().block();

            assertThat(model.invocations()).isZero();
            AgentResultEvent resultEvent = resumedEvents.stream()
                    .filter(AgentResultEvent.class::isInstance)
                    .map(AgentResultEvent.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(resultEvent.getResult().getRole()).isEqualTo(MsgRole.TOOL);
            assertThat(resultEvent.getResult().getGenerateReason()).isEqualTo(GenerateReason.ACTING_STOP_REQUESTED);
            AgentState persisted = stateStore
                    .get("user-1", "session-1", "agent_state", AgentState.class)
                    .orElseThrow();
            assertThat(persisted.getContext())
                    .extracting(Msg::getRole)
                    .containsExactly(MsgRole.USER, MsgRole.ASSISTANT, MsgRole.TOOL);
        }
    }

    @Test
    void shouldStopExplicitMediaRequestWhenPermissionIsDeniedWithoutCallingChatModel() {
        NeverCalledModel model = new NeverCalledModel();
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ExplicitMediaTool());
        String toolName = "test_text_to_image";
        PermissionContextState permissionContext = PermissionContextState.builder()
                .mode(PermissionMode.BYPASS)
                .addAskRule(toolName, new PermissionRule(toolName, null, PermissionBehavior.ASK, "test"))
                .build();
        ToolUseBlock toolCall = ToolUseBlock.builder()
                .id("media-denied-1")
                .name(toolName)
                .input(Map.of("prompt", "一只猫"))
                .build();

        try (HarnessAgent agent = buildExplicitMediaAgent(model, toolkit, stateStore, permissionContext)) {
            AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1", null);
            adapter.stream(userMessage(), toolCall).blockLast();
            ToolUseBlock asking = adapter.readAskingToolBlocks().getFirst();
            Msg rejection = Msg.builderForRole(MsgRole.USER)
                    .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of(new ConfirmResult(false, asking))))
                    .build();

            List<AgentEvent> events =
                    adapter.stream(rejection, null).collectList().block();

            assertThat(model.invocations()).isZero();
            AgentResultEvent resultEvent = events.stream()
                    .filter(AgentResultEvent.class::isInstance)
                    .map(AgentResultEvent.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(resultEvent.getResult().getGenerateReason()).isEqualTo(GenerateReason.ALL_TOOLS_DENIED);
            AgentState state = agent.getDelegate().getAgentState("user-1", "session-1");
            ToolResultBlock denied = state.getContext().stream()
                    .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                    .findFirst()
                    .orElseThrow();
            assertThat(denied.getState()).isEqualTo(ToolResultState.DENIED);
        }
    }

    @Test
    void shouldEndAtRootAgentEndWithoutSubscribingHitlMemoryTail() {
        HarnessAgent agent = mock(HarnessAgent.class);
        AtomicBoolean memoryTailSubscribed = new AtomicBoolean();
        AgentEvent requireConfirm = new RequireUserConfirmEvent("reply-1", List.of(askingBlock("call-1")));
        AgentEvent rootEnd = new AgentEndEvent("reply-1");
        Flux<AgentEvent> source = Flux.concat(Flux.just(requireConfirm, rootEnd), Flux.defer(() -> {
            memoryTailSubscribed.set(true);
            return Flux.empty();
        }));
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(source);
        AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1", null);

        List<AgentEvent> events =
                adapter.stream(userMessage(), null).collectList().block();

        assertThat(events)
                .extracting(AgentEvent::getType)
                .containsExactly(AgentEventType.REQUIRE_USER_CONFIRM, AgentEventType.AGENT_END);
        assertThat(memoryTailSubscribed).isFalse();
    }

    @Test
    void shouldKeepMemoryTailForNormalFinalAnswer() {
        HarnessAgent agent = mock(HarnessAgent.class);
        AtomicBoolean memoryTailSubscribed = new AtomicBoolean();
        Flux<AgentEvent> source = Flux.concat(Flux.just(new AgentEndEvent("reply-1")), Flux.defer(() -> {
            memoryTailSubscribed.set(true);
            return Flux.empty();
        }));
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(source);
        AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1", null);

        List<AgentEvent> events =
                adapter.stream(userMessage(), null).collectList().block();

        assertThat(events).extracting(AgentEvent::getType).containsExactly(AgentEventType.AGENT_END);
        assertThat(memoryTailSubscribed).isTrue();
    }

    @Test
    void shouldEndAtRootAgentEndWhenToolSuspendedResultArrives() {
        HarnessAgent agent = mock(HarnessAgent.class);
        AtomicBoolean memoryTailSubscribed = new AtomicBoolean();
        // AgentScope 2.0.1 真实序列：外部工具挂起以根 AGENT_RESULT(TOOL_SUSPENDED) 出现，不发 REQUIRE_EXTERNAL_EXECUTION。
        ToolUseBlock toolUse = ToolUseBlock.builder()
                .id("call-1")
                .name("ask_single_choice")
                .state(ToolCallState.SUBMITTED)
                .build();
        Msg suspended = Msg.builderForRole(MsgRole.ASSISTANT)
                .content(List.of(toolUse, ToolResultBlock.suspended(toolUse)))
                .generateReason(GenerateReason.TOOL_SUSPENDED)
                .build();
        AgentEvent result = new AgentResultEvent(suspended);
        AgentEvent rootEnd = new AgentEndEvent("reply-1");
        Flux<AgentEvent> source = Flux.concat(Flux.just(result, rootEnd), Flux.defer(() -> {
            memoryTailSubscribed.set(true);
            return Flux.empty();
        }));
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(source);
        AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1", null);

        List<AgentEvent> events =
                adapter.stream(userMessage(), null).collectList().block();

        assertThat(events)
                .extracting(AgentEvent::getType)
                .containsExactly(AgentEventType.AGENT_RESULT, AgentEventType.AGENT_END);
        assertThat(memoryTailSubscribed).isFalse();
    }

    @Test
    void shouldKeepStreamForNormalAgentResult() {
        HarnessAgent agent = mock(HarnessAgent.class);
        AtomicBoolean memoryTailSubscribed = new AtomicBoolean();
        Msg normal = Msg.builderForRole(MsgRole.ASSISTANT)
                .textContent("done")
                .generateReason(GenerateReason.MODEL_STOP)
                .build();
        Flux<AgentEvent> source =
                Flux.concat(Flux.just(new AgentResultEvent(normal), new AgentEndEvent("reply-1")), Flux.defer(() -> {
                    memoryTailSubscribed.set(true);
                    return Flux.empty();
                }));
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(source);
        AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1", null);

        List<AgentEvent> events =
                adapter.stream(userMessage(), null).collectList().block();

        assertThat(events)
                .extracting(AgentEvent::getType)
                .containsExactly(AgentEventType.AGENT_RESULT, AgentEventType.AGENT_END);
        assertThat(memoryTailSubscribed).isTrue();
    }

    @Test
    void shouldIgnoreSubagentConfirmBoundary() {
        HarnessAgent agent = mock(HarnessAgent.class);
        AtomicBoolean tailSubscribed = new AtomicBoolean();
        AgentEvent childConfirm = new RequireUserConfirmEvent("child-reply", List.of(askingBlock("child-call")))
                .withSource("root/worker");
        AgentEvent childEnd = new AgentEndEvent("child-reply").withSource("root/worker");
        Flux<AgentEvent> source =
                Flux.concat(Flux.just(childConfirm, childEnd, new AgentEndEvent("root-reply")), Flux.defer(() -> {
                    tailSubscribed.set(true);
                    return Flux.empty();
                }));
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(source);
        AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1", null);

        List<AgentEvent> events =
                adapter.stream(userMessage(), null).collectList().block();

        assertThat(events).hasSize(3);
        assertThat(tailSubscribed).isTrue();
    }

    @Test
    void shouldDenyPendingToolCallsAndSaveState() {
        HarnessAgent agent = mock(HarnessAgent.class);
        ReActAgent delegate = mock(ReActAgent.class);
        when(agent.getDelegate()).thenReturn(delegate);
        when(agent.getName()).thenReturn("demo-agent");
        AgentState state = stateWithAskingBlock();
        when(delegate.getAgentState("user-1", "session-1")).thenReturn(state);
        AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1", null);

        adapter.denyPendingToolCalls();

        List<Msg> context = state.getContext();
        assertThat(context).hasSize(2);
        Msg deniedMsg = context.get(1);
        assertThat(deniedMsg.getRole()).isEqualTo(MsgRole.TOOL);
        ToolResultBlock denied =
                deniedMsg.getContentBlocks(ToolResultBlock.class).getFirst();
        assertThat(denied.getId()).isEqualTo("call-1");
        assertThat(denied.getName()).isEqualTo("demo_tool");
        assertThat(denied.getState()).isEqualTo(ToolResultState.DENIED);
        verify(delegate).saveAgentState("user-1", "session-1");
    }

    @Test
    void shouldSkipDenyWhenNoPendingToolCalls() {
        HarnessAgent agent = mock(HarnessAgent.class);
        ReActAgent delegate = mock(ReActAgent.class);
        when(agent.getDelegate()).thenReturn(delegate);
        ToolUseBlock resolved = ToolUseBlock.builder()
                .id("call-1")
                .name("demo_tool")
                .state(ToolCallState.ALLOWED)
                .build();
        Msg assistant = Msg.builderForRole(MsgRole.ASSISTANT)
                .content(new ArrayList<>(List.of(resolved)))
                .build();
        Msg result = Msg.builderForRole(MsgRole.TOOL)
                .content(ToolResultBlock.text("done").withIdAndName("call-1", "demo_tool"))
                .build();
        AgentState state =
                AgentState.builder().context(List.of(assistant, result)).build();
        when(delegate.getAgentState("user-1", "session-1")).thenReturn(state);
        AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1", null);

        adapter.denyPendingToolCalls();

        assertThat(state.getContext()).hasSize(2);
        verify(delegate, never()).saveAgentState("user-1", "session-1");
    }

    private static ChatRunEntity run() {
        ChatRunEntity run = new ChatRunEntity();
        run.setId("run-1");
        run.setSessionId("session-1");
        return run;
    }

    private static ChatSessionEntity session() {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setAppId("app-1");
        session.setUserId("user-1");
        session.setTenantId("tenant-1");
        return session;
    }

    private static Msg userMessage() {
        return Msg.builderForRole(MsgRole.USER).textContent("hello").build();
    }

    private static AgentState stateWithAskingBlock() {
        ToolUseBlock block = askingBlock("call-1");
        Msg assistant = Msg.builderForRole(MsgRole.ASSISTANT)
                .content(new ArrayList<>(List.of(block)))
                .build();
        return AgentState.builder().context(List.of(assistant)).build();
    }

    private static ToolUseBlock askingBlock(String toolCallId) {
        return ToolUseBlock.builder()
                .id(toolCallId)
                .name("demo_tool")
                .state(ToolCallState.ASKING)
                .build();
    }

    @SuppressWarnings({"deprecation", "removal"})
    private HarnessAgent buildExplicitMediaAgent(
            Model model, Toolkit toolkit, AgentStateStore stateStore, PermissionContextState permissionContext) {
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .agentId("explicit-media-agent")
                .name("demo-agent")
                .sysPrompt("You are a test assistant.")
                .model(model)
                .toolkit(toolkit)
                .middleware(new ExplicitToolCallMiddleware())
                .hook(new ExplicitToolCallHook())
                .workspace(workspace)
                .stateStore(stateStore)
                .disableFilesystemTools()
                .disableShellTool()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableCompaction()
                .disableToolResultEviction()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableToolsConfig()
                .disableMemoryTools()
                .disableMemoryHooks();
        if (permissionContext != null) {
            builder.permissionContext(permissionContext);
        }
        return builder.build();
    }

    static final class ExplicitMediaTool {

        @Tool(name = "test_text_to_image", description = "test")
        public Mono<ToolResultBlock> generate(@ToolParam(name = "prompt", description = "prompt") String prompt) {
            return Mono.just(ToolResultBlock.text("generated:" + prompt));
        }
    }

    static final class NeverCalledModel implements Model {

        private final AtomicInteger invocations = new AtomicInteger();

        int invocations() {
            return invocations.get();
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            invocations.incrementAndGet();
            return Flux.error(new AssertionError("Explicit media execution must not invoke the chat model"));
        }

        @Override
        public String getModelName() {
            return "never-called-model";
        }
    }
}
