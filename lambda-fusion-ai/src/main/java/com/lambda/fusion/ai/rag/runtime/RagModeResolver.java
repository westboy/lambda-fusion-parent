package com.lambda.fusion.ai.rag.runtime;

import com.lambda.fusion.ai.AiConstants.RagMode;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import lombok.experimental.UtilityClass;

/** 解析应用实际生效的知识检索模式。 */
@UtilityClass
public class RagModeResolver {

    /** 未绑定知识库时不启用 RAG；历史空值或非法值按 GENERIC 兼容。 */
    public static RagMode resolve(AppEntity app) {
        if (app.getKnowledgeBaseIds() == null || app.getKnowledgeBaseIds().isEmpty()) {
            return null;
        }
        RagMode ragMode = RagMode.of(app.getRagMode());
        return ragMode != null ? ragMode : RagMode.GENERIC;
    }
}
