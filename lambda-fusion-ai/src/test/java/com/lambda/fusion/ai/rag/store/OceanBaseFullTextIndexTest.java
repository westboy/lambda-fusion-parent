package com.lambda.fusion.ai.rag.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import org.junit.jupiter.api.Test;

class OceanBaseFullTextIndexTest {

    @Test
    void rejectsBlankJdbcUrl() {
        assertThatThrownBy(() -> new OceanBaseFullTextIndex(" ", "u", "p", "ai_kb_full_text_chunk"))
                .isInstanceOf(AiBusinessException.class)
                .extracting("code")
                .isEqualTo(AiErrorCode.CONFIGURATION_ERROR.getCode());
    }

    @Test
    void rejectsInvalidTableName() {
        assertThatThrownBy(() -> new OceanBaseFullTextIndex("jdbc:oceanbase://localhost:2881/test", "u", "p", "a-b"))
                .isInstanceOf(AiBusinessException.class)
                .extracting("code")
                .isEqualTo(AiErrorCode.CONFIGURATION_ERROR.getCode());
        assertThatThrownBy(() ->
                        new OceanBaseFullTextIndex("jdbc:oceanbase://localhost:2881/test", "u", "p", "drop table"))
                .isInstanceOf(AiBusinessException.class)
                .extracting("code")
                .isEqualTo(AiErrorCode.CONFIGURATION_ERROR.getCode());
    }

    @Test
    void acceptsValidConfiguration() {
        OceanBaseFullTextIndex index =
                new OceanBaseFullTextIndex("jdbc:oceanbase://localhost:2881/test", "u", "p", "ai_kb_full_text_chunk");

        assertThat(index).isNotNull();
    }

    @Test
    void translatesFailuresIntoRetrievalBusinessError() {
        IllegalStateException failure = new IllegalStateException("connection lost");

        AiBusinessException translated = OceanBaseFullTextIndex.translateFailure(failure);

        assertThat(translated.getCode()).isEqualTo(AiErrorCode.KB_RETRIEVAL_FAILED.getCode());
        assertThat(translated.getCause()).isSameAs(failure);
    }
}
