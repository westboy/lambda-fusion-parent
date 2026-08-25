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
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * Agent 执行适配器测试。
 *
 * @author Jin
 */
class AgentExecutionAdapterTest {

    @Test
    void shouldUseConversationSessionInDirectMode() {
        HarnessAgent agent = mock(HarnessAgent.class);
        ReActAgent delegate = mock(ReActAgent.class);
        when(agent.getDelegate()).thenReturn(delegate);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());
        when(delegate.getAgentState("user-1", "session-1")).thenReturn(stateWithAskingBlock());
        AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1");
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
        AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1");

        adapter.stream(userMessage(), null).blockLast();

        verify(distributedStore, never()).sandboxExecutionGuard();
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
        AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1");

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
        AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1");

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
        AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1");

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
        AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1");

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
        AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1");

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
        AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1");

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
        AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-1");

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
}
