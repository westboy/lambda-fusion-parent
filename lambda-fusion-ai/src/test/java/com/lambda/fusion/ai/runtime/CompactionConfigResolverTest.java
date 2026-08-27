package com.lambda.fusion.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class CompactionConfigResolverTest {

    @Test
    void shouldUseOfficialDynamicTokenThresholdAndPrimaryModelFallback() {
        Model primaryModel = new ContextAwareModel(262144);

        CompactionConfig config = AgentFactory.resolveCompactionConfig(primaryModel);

        assertThat(config.getTriggerMessages()).isZero();
        assertThat(config.getTriggerTokens()).isZero();
        assertThat(config.getModel()).isNull();
        assertThat(config.isFlushBeforeCompact()).isTrue();
        assertThat(config.isOffloadBeforeCompact()).isTrue();
    }

    @Test
    void shouldKeepOfficialMessageFallbackWhenContextWindowIsUnknown() {
        CompactionConfig config = AgentFactory.resolveCompactionConfig(new ContextAwareModel(0));

        assertThat(config.getTriggerMessages())
                .isEqualTo(CompactionConfig.builder().build().getTriggerMessages());
        assertThat(config.getTriggerTokens()).isZero();
    }

    private static final class ContextAwareModel implements Model {

        private final int contextWindowSize;

        private ContextAwareModel(int contextWindowSize) {
            this.contextWindowSize = contextWindowSize;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }

        @Override
        public String getModelName() {
            return "compaction-test-model";
        }

        @Override
        public int getContextWindowSize() {
            return contextWindowSize;
        }
    }
}
