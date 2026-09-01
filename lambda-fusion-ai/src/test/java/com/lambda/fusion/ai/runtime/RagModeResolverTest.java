package com.lambda.fusion.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.lambda.fusion.ai.AiConstants.RagMode;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.rag.runtime.RagModeResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 {@link RagModeResolver#resolve}：kbIds 为空不启用任何模式；ragMode 空按
 * GENERIC（向后兼容）；AGENTIC/BOTH 原样解析；非法值兜底 GENERIC。
 *
 * @author Jin
 */
class RagModeResolverTest {

    @Test
    void noKnowledgeBasesDisablesAllModes() {
        AppEntity app = new AppEntity();
        app.setRagMode("AGENTIC");

        assertThat(RagModeResolver.resolve(app)).isNull();

        app.setKnowledgeBaseIds(List.of());
        assertThat(RagModeResolver.resolve(app)).isNull();
    }

    @Test
    void blankRagModeFallsBackToGeneric() {
        AppEntity app = new AppEntity();
        app.setKnowledgeBaseIds(List.of("kb1"));

        assertThat(RagModeResolver.resolve(app)).isEqualTo(RagMode.GENERIC);
    }

    @Test
    void explicitModesResolved() {
        AppEntity app = new AppEntity();
        app.setKnowledgeBaseIds(List.of("kb1"));

        app.setRagMode("AGENTIC");
        assertThat(RagModeResolver.resolve(app)).isEqualTo(RagMode.AGENTIC);

        app.setRagMode("both");
        assertThat(RagModeResolver.resolve(app)).isEqualTo(RagMode.BOTH);
    }

    @Test
    void unknownRagModeFallsBackToGeneric() {
        AppEntity app = new AppEntity();
        app.setKnowledgeBaseIds(List.of("kb1"));
        app.setRagMode("UNKNOWN");

        assertThat(RagModeResolver.resolve(app)).isEqualTo(RagMode.GENERIC);
    }
}
