package com.lambda.fusion.ai.rag.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.lambda.cloud.lucene.exception.LuceneOperationException;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.junit.jupiter.api.Test;

class LuceneKnowledgeIndexTest {

    @Test
    void translatesOldIndexFormatIntoActionableBusinessError() {
        LuceneOperationException failure =
                new LuceneOperationException("创建 Lucene 索引失败", new IndexFormatTooOldException("segments_1", 8, 9, 10));

        AiBusinessException translated = LuceneKnowledgeIndex.translateFailure(failure);

        assertThat(translated.getCode()).isEqualTo(AiErrorCode.KB_FULL_TEXT_INDEX_INCOMPATIBLE.getCode());
        assertThat(translated.getCause()).isSameAs(failure);
    }

    @Test
    void translatesNewIndexFormatIntoSameRebuildGuidance() {
        LuceneOperationException failure =
                new LuceneOperationException("创建 Lucene 索引失败", new IndexFormatTooNewException("segments_1", 11, 9, 10));

        AiBusinessException translated = LuceneKnowledgeIndex.translateFailure(failure);

        assertThat(translated.getCode()).isEqualTo(AiErrorCode.KB_FULL_TEXT_INDEX_INCOMPATIBLE.getCode());
    }

    @Test
    void preservesGenericRetrievalFailureForUnrelatedErrors() {
        IllegalStateException failure = new IllegalStateException("index unavailable");

        AiBusinessException translated = LuceneKnowledgeIndex.translateFailure(failure);

        assertThat(translated.getCode()).isEqualTo(AiErrorCode.KB_RETRIEVAL_FAILED.getCode());
        assertThat(translated.getCause()).isSameAs(failure);
    }
}
