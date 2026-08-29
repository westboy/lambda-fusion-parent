package com.lambda.fusion.ai.rag.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 验证 {@link KnowledgeRetrievalTool#retrieveKnowledge}：命中格式化（来源 + 章节 + Score + 内容）、
 * 空结果/空白 query/检索异常返回友好文案绝不外抛、limit 原样下传。
 *
 * @author Jin
 */
class KnowledgeRetrievalToolTest {

    private static final List<String> KB_IDS = List.of("kb1");

    @Test
    void hitFormatsDocuments() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(anyList(), anyString(), any()))
                .thenReturn(Mono.just(List.of(
                        new RetrievedChunk(
                                "Lambda Fusion 支持 RAG",
                                0.9,
                                "kb1",
                                "doc1",
                                "产品手册.pdf",
                                "doc1-0",
                                0,
                                2,
                                "第一章",
                                "COSINE"),
                        new RetrievedChunk(
                                "Lambda Fusion 基于 Spring Boot",
                                0.75,
                                "kb1",
                                "doc2",
                                "架构说明.md",
                                "doc2-0",
                                0,
                                1,
                                null,
                                "BM25"))));
        KnowledgeRetrievalTool tool = new KnowledgeRetrievalTool(retriever, KB_IDS);

        String result = tool.retrieveKnowledge("Lambda Fusion 是什么?", null);

        assertThat(result)
                .contains("Retrieved 2 relevant document(s):")
                .contains("Document 1:")
                .contains("Source: 产品手册.pdf")
                .contains("Section: 第一章")
                .contains("Chunk: 1/2")
                .contains("Score: 0.900 (COSINE)")
                .contains("Lambda Fusion 支持 RAG")
                .contains("Document 2:")
                .contains("Source: 架构说明.md")
                .contains("Score: 0.750 (BM25)")
                .contains("Lambda Fusion 基于 Spring Boot");
    }

    @Test
    void emptyResultReturnsFriendlyMessage() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(anyList(), anyString(), any())).thenReturn(Mono.just(List.of()));
        KnowledgeRetrievalTool tool = new KnowledgeRetrievalTool(retriever, KB_IDS);

        assertThat(tool.retrieveKnowledge("不存在的内容", null)).isEqualTo(KnowledgeRetrievalTool.NO_RESULT_MESSAGE);
    }

    @Test
    void retrievalErrorReturnsFriendlyMessage() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(anyList(), anyString(), any())).thenReturn(Mono.error(new RuntimeException("pg down")));
        KnowledgeRetrievalTool tool = new KnowledgeRetrievalTool(retriever, KB_IDS);

        // 检索故障返回友好文案，绝不向 ReAct 循环抛异常
        assertThatCode(() -> {
                    String result = tool.retrieveKnowledge("问题", null);
                    assertThat(result).startsWith("Knowledge retrieval failed");
                })
                .doesNotThrowAnyException();
    }

    @Test
    void limitPassedThrough() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(anyList(), anyString(), any())).thenReturn(Mono.just(List.of()));
        KnowledgeRetrievalTool tool = new KnowledgeRetrievalTool(retriever, KB_IDS);

        tool.retrieveKnowledge("q1", null);
        verify(retriever).retrieve(eq(KB_IDS), eq("q1"), isNull());

        tool.retrieveKnowledge("q2", 3);
        verify(retriever).retrieve(eq(KB_IDS), eq("q2"), eq(3));
    }

    @Test
    void blankQueryReturnsPromptWithoutCallingRetriever() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        KnowledgeRetrievalTool tool = new KnowledgeRetrievalTool(retriever, KB_IDS);

        assertThat(tool.retrieveKnowledge("   ", null)).isEqualTo(KnowledgeRetrievalTool.EMPTY_QUERY_MESSAGE);
        verifyNoInteractions(retriever);
    }
}
