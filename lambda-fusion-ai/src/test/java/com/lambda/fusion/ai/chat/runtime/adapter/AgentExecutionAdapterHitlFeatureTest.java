package com.lambda.fusion.ai.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 使用真实 AgentScope Harness 固定 HITL 安全边界语义。 */
class AgentExecutionAdapterHitlFeatureTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    Path workspace;

    @Test
    void shouldPersistAskingStateBeforeSkippingMemoryTail() {
        ToolCallingModel mainModel = new ToolCallingModel();
        RecordingMemoryModel memoryModel = new RecordingMemoryModel();
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();

        try (HarnessAgent agent = buildAgent(mainModel, memoryModel, stateStore)) {
            AgentExecutionAdapter adapter = new AgentExecutionAdapter(agent, run(), session(), "tenant-feature", null);

            List<AgentEvent> events =
                    adapter.stream(userMessage(), null).collectList().block(TIMEOUT);

            assertThat(events).isNotNull();
            assertThat(events)
                    .extracting(AgentEvent::getType)
                    .contains(AgentEventType.REQUIRE_USER_CONFIRM, AgentEventType.AGENT_END);
            assertThat(events.getLast().getType()).isEqualTo(AgentEventType.AGENT_END);
            assertThat(events.getLast().getSource()).isNull();
            assertThat(memoryModel.invocations()).isZero();
            assertThat(stateStore.exists("user-feature", "session-feature")).isTrue();

            AgentState state = agent.getDelegate().getAgentState("user-feature", "session-feature");
            assertThat(lastAssistantToolUse(state).getState()).isEqualTo(ToolCallState.ASKING);
        }

        // 模拟应用进程重启：本地 Agent 实例消失，新的实例从同一 StateStore 懒加载 ASKING 状态。
        try (HarnessAgent restarted = buildAgent(new ToolCallingModel(), new RecordingMemoryModel(), stateStore)) {
            AgentExecutionAdapter restored =
                    new AgentExecutionAdapter(restarted, run(), session(), "tenant-feature", null);

            assertThat(restored.readAskingToolBlocks())
                    .extracting(ToolUseBlock::getId)
                    .containsExactly("call-feature");
        }
    }

    private HarnessAgent buildAgent(Model mainModel, Model memoryModel, InMemoryAgentStateStore stateStore) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new AskingTool());
        return HarnessAgent.builder()
                .agentId("hitl-boundary-agent")
                .name("hitl-boundary-agent")
                .sysPrompt("You are a test assistant.")
                .model(mainModel)
                .toolkit(toolkit)
                .workspace(workspace)
                .stateStore(stateStore)
                .memory(MemoryConfig.builder()
                        .model(memoryModel)
                        .flushTrigger(MemoryConfig.FlushTrigger.always())
                        .build())
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
                .build();
    }

    private static ToolUseBlock lastAssistantToolUse(AgentState state) {
        assertThat(state).isNotNull();
        for (int i = state.getContext().size() - 1; i >= 0; i--) {
            Msg message = state.getContext().get(i);
            if (message.getRole() == MsgRole.ASSISTANT) {
                return message.getContentBlocks(ToolUseBlock.class).getFirst();
            }
        }
        throw new AssertionError("No assistant tool call found");
    }

    private static ChatRunEntity run() {
        ChatRunEntity run = new ChatRunEntity();
        run.setId("run-feature");
        run.setSessionId("session-feature");
        return run;
    }

    private static ChatSessionEntity session() {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-feature");
        session.setAppId("app-feature");
        session.setUserId("user-feature");
        session.setTenantId("tenant-feature");
        return session;
    }

    private static Msg userMessage() {
        return Msg.builderForRole(MsgRole.USER).textContent("call demo tool").build();
    }

    private static final class ToolCallingModel implements Model {

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            ToolUseBlock toolUse = ToolUseBlock.builder()
                    .id("call-feature")
                    .name("demo_tool")
                    .input(Map.of("query", "demo"))
                    .build();
            return Flux.just(ChatResponse.builder()
                    .id("msg_" + UUID.randomUUID())
                    .content(List.<ContentBlock>of(toolUse))
                    .usage(new ChatUsage(1, 1, 2))
                    .finishReason("tool_calls")
                    .build());
        }

        @Override
        public String getModelName() {
            return "tool-calling-model";
        }
    }

    private static final class RecordingMemoryModel implements Model {

        private final AtomicInteger invocations = new AtomicInteger();

        int invocations() {
            return invocations.get();
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(() -> {
                invocations.incrementAndGet();
                return Flux.just(ChatResponse.builder()
                        .id("memory_" + UUID.randomUUID())
                        .content(List.<ContentBlock>of(
                                TextBlock.builder().text("NO_REPLY").build()))
                        .usage(new ChatUsage(1, 1, 2))
                        .finishReason("stop")
                        .build());
            });
        }

        @Override
        public String getModelName() {
            return "recording-memory-model";
        }
    }

    private static final class AskingTool extends ToolBase {

        AskingTool() {
            super("demo_tool", "asks for permission", schema(), false, true, false, null, false, false);
        }

        private static Map<String, Object> schema() {
            Map<String, Object> query = new HashMap<>();
            query.put("type", "string");
            Map<String, Object> properties = new HashMap<>();
            properties.put("query", query);
            Map<String, Object> schema = new HashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            return schema;
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.ask("test confirmation"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("executed"));
        }
    }
}
