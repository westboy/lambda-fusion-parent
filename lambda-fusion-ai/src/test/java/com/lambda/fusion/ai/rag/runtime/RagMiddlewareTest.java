package com.lambda.fusion.ai.rag.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 验证 {@link RagMiddleware#onAgent}：命中时尾部追加带 {@link Msg#METADATA_SYNTHETIC}
 * 的合成 USER 消息；无 USER 消息/空 query/未命中/检索失败一律原样放行；
 * 注入总长受 maxInjectChars 截断。
 *
 * @author Jin
 */
class RagMiddlewareTest {

    private static final List<String> KB_IDS = List.of("kb1");

    // 记录 next 收到的 AgentInput 并返回空事件流
    private static Function<AgentInput, Flux<AgentEvent>> recordingNext(AtomicReference<AgentInput> seen) {
        return input -> {
            seen.set(input);
            return Flux.empty();
        };
    }

    private static AgentInput inputOf(Msg... msgs) {
        return new AgentInput(List.of(msgs));
    }

    private static Msg userMsg(String text) {
        return Msg.builder().name("user").role(MsgRole.USER).textContent(text).build();
    }

    @Test
    void hitAppendsSyntheticKnowledgeMessage() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(anyList(), anyString()))
                .thenReturn(Mono.just(List.of(chunk("Lambda Fusion 支持 RAG 检索注入", 0.9, "产品手册.pdf"))));
        RagMiddleware middleware = new RagMiddleware(retriever, KB_IDS, 4000);
        AgentInput input = inputOf(userMsg("Lambda Fusion 是什么?"));
        AtomicReference<AgentInput> seen = new AtomicReference<>();

        middleware.onAgent(null, null, input, recordingNext(seen)).blockLast();

        List<Msg> msgs = seen.get().msgs();
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(0)).isSameAs(input.msgs().get(0)); // 原消息保持不变
        Msg injected = msgs.get(1);
        assertThat(injected.getRole()).isEqualTo(MsgRole.USER);
        assertThat(injected.getMetadata()).containsEntry(Msg.METADATA_SYNTHETIC, true);
        assertThat(injected.getTextContent())
                .contains("<retrieved_knowledge>")
                .contains("</retrieved_knowledge>")
                .contains("Source: 产品手册.pdf")
                .contains("Lambda Fusion 支持 RAG 检索注入");
    }

    @Test
    void noUserMessagePassesThrough() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        RagMiddleware middleware = new RagMiddleware(retriever, KB_IDS, 4000);
        AgentInput input = inputOf(Msg.builder()
                .name("assistant")
                .role(MsgRole.ASSISTANT)
                .textContent("你好")
                .build());
        AtomicReference<AgentInput> seen = new AtomicReference<>();

        middleware.onAgent(null, null, input, recordingNext(seen)).blockLast();

        assertThat(seen.get()).isSameAs(input);
        verifyNoInteractions(retriever);
    }

    @Test
    void blankQueryPassesThrough() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        RagMiddleware middleware = new RagMiddleware(retriever, KB_IDS, 4000);
        AgentInput input = inputOf(userMsg("   "));
        AtomicReference<AgentInput> seen = new AtomicReference<>();

        middleware.onAgent(null, null, input, recordingNext(seen)).blockLast();

        assertThat(seen.get()).isSameAs(input);
        verifyNoInteractions(retriever);
    }

    @Test
    void retrievalErrorPassesThrough() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(anyList(), anyString())).thenReturn(Mono.error(new RuntimeException("pg down")));
        RagMiddleware middleware = new RagMiddleware(retriever, KB_IDS, 4000);
        AgentInput input = inputOf(userMsg("问题"));
        AtomicReference<AgentInput> seen = new AtomicReference<>();

        // 检索失败降级放行，不传播异常
        assertThatCode(() -> middleware
                        .onAgent(null, null, input, recordingNext(seen))
                        .blockLast())
                .doesNotThrowAnyException();
        assertThat(seen.get()).isSameAs(input);
    }

    @Test
    void emptyResultPassesThrough() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(anyList(), anyString())).thenReturn(Mono.just(List.of()));
        RagMiddleware middleware = new RagMiddleware(retriever, KB_IDS, 4000);
        AgentInput input = inputOf(userMsg("问题"));
        AtomicReference<AgentInput> seen = new AtomicReference<>();

        middleware.onAgent(null, null, input, recordingNext(seen)).blockLast();

        assertThat(seen.get()).isSameAs(input);
    }

    @Test
    void injectContentTruncatedByMaxInjectChars() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        String shortChunk = "AAAAAAAAAA";
        String longChunk = "B".repeat(1000);
        when(retriever.retrieve(anyList(), anyString()))
                .thenReturn(Mono.just(List.of(chunk(shortChunk, 0.9, "短文档.pdf"), chunk(longChunk, 0.8, "长文档.pdf"))));
        RagMiddleware middleware = new RagMiddleware(retriever, KB_IDS, 400);
        AgentInput input = inputOf(userMsg("问题"));
        AtomicReference<AgentInput> seen = new AtomicReference<>();

        middleware.onAgent(null, null, input, recordingNext(seen)).blockLast();

        String text = seen.get().msgs().get(1).getTextContent();
        assertThat(text).contains(shortChunk).doesNotContain(longChunk);
        assertThat(text).contains("Source: 短文档.pdf").contains("[Content truncated]");
        // 截断后总长不超过 maxInjectChars + 收尾标签长度
        assertThat(text.length()).isLessThanOrEqualTo(400 + "</retrieved_knowledge>".length());
    }

    private static RetrievedChunk chunk(String content, double score, String fileName) {
        return new RetrievedChunk(content, score, "kb1", "doc1", fileName, "doc1-0", 0, 1, "第一章", "COSINE");
    }
}
