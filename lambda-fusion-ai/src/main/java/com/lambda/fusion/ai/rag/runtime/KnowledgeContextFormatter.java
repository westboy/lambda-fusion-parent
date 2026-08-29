package com.lambda.fusion.ai.rag.runtime;

import java.util.Locale;
import java.util.StringJoiner;
import org.apache.commons.lang3.StringUtils;

/** 统一格式化检索片段及其来源，供自动注入中间件与 Agentic 检索工具复用。 */
final class KnowledgeContextFormatter {

    private KnowledgeContextFormatter() {}

    static String format(RetrievedChunk chunk) {
        StringJoiner metadata = new StringJoiner(" | ");
        metadata.add("Source: " + inline(StringUtils.defaultIfBlank(chunk.fileName(), chunk.docId())));
        metadata.add("Document ID: " + inline(chunk.docId()));
        if (StringUtils.isNotBlank(chunk.sectionPath())) {
            metadata.add("Section: " + inline(chunk.sectionPath()));
        }
        if (chunk.chunkIndex() != null) {
            String position = String.valueOf(chunk.chunkIndex() + 1);
            if (chunk.chunkCount() != null) {
                position += "/" + chunk.chunkCount();
            }
            metadata.add("Chunk: " + position);
        } else if (StringUtils.isNotBlank(chunk.chunkId())) {
            metadata.add("Chunk ID: " + inline(chunk.chunkId()));
        }
        metadata.add("Score: " + String.format(Locale.ROOT, "%.3f", chunk.score()) + " ("
                + StringUtils.defaultIfBlank(chunk.scoreType(), "UNKNOWN") + ")");
        return "[" + metadata + "]\n" + StringUtils.defaultString(chunk.content());
    }

    private static String inline(String value) {
        return StringUtils.defaultString(value)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('|', '/')
                .trim();
    }
}
