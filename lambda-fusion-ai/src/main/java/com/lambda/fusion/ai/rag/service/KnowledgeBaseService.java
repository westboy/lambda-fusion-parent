package com.lambda.fusion.ai.rag.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.fusion.ai.rag.model.CreateKnowledgeBase;
import com.lambda.fusion.ai.rag.model.KnowledgeBasePage;
import com.lambda.fusion.ai.rag.model.KnowledgeIndexRebuildResult;
import com.lambda.fusion.ai.rag.model.TestKnowledgeRetrieval;
import com.lambda.fusion.ai.rag.model.UpdateKnowledgeBase;
import com.lambda.fusion.ai.rag.model.entity.KnowledgeBaseEntity;
import com.lambda.fusion.ai.rag.runtime.RetrievalProbe;

public interface KnowledgeBaseService {

    Page<KnowledgeBaseEntity> page(KnowledgeBasePage query);

    KnowledgeBaseEntity get(String id);

    KnowledgeBaseEntity create(CreateKnowledgeBase dto);

    void update(String id, UpdateKnowledgeBase dto);

    void delete(String id);

    RetrievalProbe testRetrieval(String id, TestKnowledgeRetrieval command);

    KnowledgeIndexRebuildResult rebuildIndexes(String id);

    /**
     * 按主键加载（含禁用记录）；不存在抛出业务异常。供运行时检索适配器使用。
     */
    KnowledgeBaseEntity loadById(String id);
}
