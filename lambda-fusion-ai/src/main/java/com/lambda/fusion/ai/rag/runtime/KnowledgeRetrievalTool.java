package com.lambda.fusion.ai.rag.runtime;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * Agentic 模式下的 {@code retrieve_knowledge} 工具，由模型在 ReAct 循环中自主决定检索时机和查询内容。
 * 工具在 Agent 构建期绑定应用的知识库范围，不参与全局工具扫描。检索结果保留文件、文档、章节和分块来源；
 * 空结果或检索异常转换为可读文本，避免中断 ReAct 循环。
 *
 * @author Jin
 */
@Slf4j
@RequiredArgsConstructor
public class KnowledgeRetrievalTool {

    public static final String TOOL_NAME = "retrieve_knowledge";

    static final String EMPTY_QUERY_MESSAGE = "Please provide a non-empty search query.";
    static final String NO_RESULT_MESSAGE = "No relevant documents found in the knowledge base.";

    private final KnowledgeRetriever retriever;
    private final List<String> kbIds;

    @Tool(
            name = TOOL_NAME,
            description = "Retrieve relevant documents from the knowledge base. Use this tool when "
                    + "you need specific information or the user asks about stored knowledge.")
    public String retrieveKnowledge(
            @ToolParam(name = "query", description = "The search query to find relevant documents") String query,
            @ToolParam(name = "limit", description = "Max documents to return (default: 5)", required = false)
                    Integer limit) {
        if (StringUtils.isBlank(query)) {
            return EMPTY_QUERY_MESSAGE;
        }
        List<RetrievedChunk> chunks;
        try {
            chunks = retriever.retrieve(kbIds, query, limit).block();
        } catch (Exception e) {
            // 将检索故障转为工具结果，避免中断 ReAct 循环。
            log.warn("知识库工具检索失败(kbIds={}): {}", kbIds, e.getMessage());
            return "Knowledge retrieval failed: " + e.getMessage();
        }
        if (chunks == null || chunks.isEmpty()) {
            return NO_RESULT_MESSAGE;
        }
        return formatChunks(chunks);
    }

    // 与自动注入中间件复用来源格式，使两种 RAG 模式呈现一致的文档边界。
    private static String formatChunks(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder("Retrieved ").append(chunks.size()).append(" relevant document(s):\n\n");
        int index = 1;
        for (RetrievedChunk chunk : chunks) {
            sb.append("Document ")
                    .append(index++)
                    .append(":\n")
                    .append(KnowledgeContextFormatter.format(chunk))
                    .append("\n\n");
        }
        return sb.toString();
    }
}
