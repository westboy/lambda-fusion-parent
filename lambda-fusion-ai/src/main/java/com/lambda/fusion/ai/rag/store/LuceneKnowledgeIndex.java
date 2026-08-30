package com.lambda.fusion.ai.rag.store;

import com.lambda.cloud.lucene.manager.LuceneManager;
import com.lambda.cloud.lucene.manager.LuceneManagerFactory;
import com.lambda.cloud.lucene.model.LuceneSearchHit;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.rag.model.entity.KnowledgeBaseEntity;
import com.lambda.fusion.ai.rag.runtime.IngestChunk;
import com.lambda.fusion.ai.rag.runtime.RetrievedChunk;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;

/** 知识库 chunk 在 Lucene 中的字段契约与索引生命周期。 */
@RequiredArgsConstructor
public class LuceneKnowledgeIndex {

    private static final Pattern SAFE_PATH_SEGMENT = Pattern.compile("[A-Za-z0-9_-]+");

    private static final String FIELD_KB_ID = "kbId";
    private static final String FIELD_TENANT_ID = "tenantId";
    private static final String FIELD_DOC_ID = "docId";
    private static final String FIELD_CHUNK_ID = "chunkId";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_FILE_NAME = "fileName";
    private static final String FIELD_CHUNK_INDEX = "chunkIndex";
    private static final String FIELD_CHUNK_COUNT = "chunkCount";
    private static final String FIELD_SECTION_PATH = "sectionPath";

    private final LuceneManagerFactory managerFactory;

    /** 原子替换同一文档的全部 chunk，失败时不会留下部分新文档。 */
    public void replaceDocument(
            KnowledgeBaseEntity kb, String docId, String tenantId, String fileName, List<IngestChunk> chunks) {
        List<Document> documents = new ArrayList<>(chunks.size());
        for (IngestChunk chunk : chunks) {
            Document document = new Document();
            document.add(new StringField(FIELD_KB_ID, kb.getId(), Field.Store.YES));
            if (StringUtils.isNotBlank(tenantId)) {
                document.add(new StringField(FIELD_TENANT_ID, tenantId, Field.Store.YES));
            }
            document.add(new StringField(FIELD_DOC_ID, docId, Field.Store.YES));
            document.add(new StringField(FIELD_CHUNK_ID, chunk.chunkId(), Field.Store.YES));
            document.add(new TextField(FIELD_CONTENT, chunk.text(), Field.Store.YES));
            if (StringUtils.isNotBlank(fileName)) {
                document.add(new TextField(FIELD_FILE_NAME, fileName, Field.Store.YES));
            }
            document.add(new StoredField(FIELD_CHUNK_INDEX, chunk.chunkIndex()));
            document.add(new StoredField(FIELD_CHUNK_COUNT, chunks.size()));
            if (StringUtils.isNotBlank(chunk.sectionPath())) {
                document.add(new TextField(FIELD_SECTION_PATH, chunk.sectionPath(), Field.Store.YES));
            }
            documents.add(document);
        }
        try {
            manager(kb).replaceDocuments(new Term(FIELD_DOC_ID, docId), documents);
        } catch (IOException | RuntimeException e) {
            throw translateFailure(e);
        }
    }

    public List<RetrievedChunk> search(KnowledgeBaseEntity kb, String queryText, int limit) {
        try {
            LuceneManager manager = manager(kb);
            List<LuceneSearchHit> hits = manager.search(
                    manager.parseQuery(queryText, FIELD_CONTENT, FIELD_FILE_NAME, FIELD_SECTION_PATH), limit);
            List<RetrievedChunk> chunks = new ArrayList<>(hits.size());
            for (LuceneSearchHit hit : hits) {
                Document document = hit.document();
                chunks.add(new RetrievedChunk(
                        document.get(FIELD_CONTENT),
                        hit.score(),
                        kb.getId(),
                        document.get(FIELD_DOC_ID),
                        document.get(FIELD_FILE_NAME),
                        document.get(FIELD_CHUNK_ID),
                        storedInteger(document, FIELD_CHUNK_INDEX),
                        storedInteger(document, FIELD_CHUNK_COUNT),
                        document.get(FIELD_SECTION_PATH),
                        "BM25"));
            }
            return chunks;
        } catch (IOException | ParseException | RuntimeException e) {
            throw translateFailure(e);
        }
    }

    public void deleteDocument(KnowledgeBaseEntity kb, String docId) {
        try {
            manager(kb).deleteDocuments(new Term(FIELD_DOC_ID, docId));
        } catch (IOException | RuntimeException e) {
            throw translateFailure(e);
        }
    }

    public boolean exists(KnowledgeBaseEntity kb) {
        return managerFactory.exists(indexName(kb));
    }

    public void evict(KnowledgeBaseEntity kb) {
        managerFactory.close(indexName(kb));
    }

    public void deleteKnowledgeBase(KnowledgeBaseEntity kb) {
        managerFactory.delete(indexName(kb));
    }

    static AiBusinessException translateFailure(Throwable failure) {
        if (failure instanceof AiBusinessException aiBusinessException) {
            return aiBusinessException;
        }
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof IndexFormatTooOldException || current instanceof IndexFormatTooNewException) {
                return new AiBusinessException(AiErrorCode.KB_FULL_TEXT_INDEX_INCOMPATIBLE, failure);
            }
        }
        return new AiBusinessException(AiErrorCode.KB_RETRIEVAL_FAILED, failure);
    }

    private LuceneManager manager(KnowledgeBaseEntity kb) {
        return managerFactory.get(indexName(kb));
    }

    private static String indexName(KnowledgeBaseEntity kb) {
        return "rag/" + safeSegment(kb.getTenantId(), "system") + "/" + safeSegment(kb.getId(), null);
    }

    private static String safeSegment(String value, String defaultValue) {
        String resolved = StringUtils.defaultIfBlank(value, defaultValue);
        if (resolved == null || !SAFE_PATH_SEGMENT.matcher(resolved).matches()) {
            throw new AiBusinessException(AiErrorCode.CONFIGURATION_ERROR, "非法的 Lucene 索引标识: " + value);
        }
        return resolved;
    }

    private static Integer storedInteger(Document document, String field) {
        return document.getField(field) != null
                ? document.getField(field).numericValue().intValue()
                : null;
    }
}
