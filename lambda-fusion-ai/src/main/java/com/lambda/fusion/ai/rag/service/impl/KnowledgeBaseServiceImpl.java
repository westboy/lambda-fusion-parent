package com.lambda.fusion.ai.rag.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import com.lambda.fusion.ai.llm.service.LlmModelService;
import com.lambda.fusion.ai.rag.mapper.KnowledgeBaseMapper;
import com.lambda.fusion.ai.rag.model.CreateKnowledgeBase;
import com.lambda.fusion.ai.rag.model.KnowledgeBasePage;
import com.lambda.fusion.ai.rag.model.TestKnowledgeRetrieval;
import com.lambda.fusion.ai.rag.model.UpdateKnowledgeBase;
import com.lambda.fusion.ai.rag.model.entity.KnowledgeBaseEntity;
import com.lambda.fusion.ai.rag.runtime.RetrievalProbe;
import com.lambda.fusion.ai.rag.runtime.SimpleKnowledgeAdapter;
import com.lambda.fusion.ai.rag.service.KnowledgeBaseService;
import com.lambda.fusion.ai.rag.service.KnowledgeDocumentService;
import com.lambda.fusion.core.utils.AuthUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final LlmModelService llmModelService;
    private final ObjectProvider<SimpleKnowledgeAdapter> adapterProvider;
    // 文档服务反向依赖知识库服务（删除时校验），ObjectProvider 延迟解析打破构造环
    private final ObjectProvider<KnowledgeDocumentService> documentServiceProvider;

    @Override
    public Page<KnowledgeBaseEntity> page(KnowledgeBasePage query) {
        return knowledgeBaseMapper.selectPage(query.getPage(), query.getLambdaQueryWrapper());
    }

    @Override
    public KnowledgeBaseEntity get(String id) {
        return requireExists(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseEntity create(CreateKnowledgeBase dto) {
        validateEmbeddingModel(dto.getEmbeddingModelId());
        ensureNameUnique(dto.getName(), null);
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(IdUtil.getSnowflakeNextIdStr());
        entity.setTenantId(AuthUtils.getTenantId());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setEmbeddingModelId(dto.getEmbeddingModelId());
        entity.setDimensions(dto.getDimensions());
        // 每知识库一张向量表，表名由系统生成（SearchDocumentDto 无 payload 过滤，无法单表按 KB 隔离）
        entity.setVectorTable("ai_kb_" + entity.getId());
        entity.setRetrieveLimit(dto.getRetrieveLimit());
        entity.setScoreThreshold(dto.getScoreThreshold());
        entity.setRemark(dto.getRemark());
        entity.setEnabled(dto.getEnabled());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        knowledgeBaseMapper.insert(entity);
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(String id, UpdateKnowledgeBase dto) {
        KnowledgeBaseEntity entity = requireExists(id);
        if (StringUtils.isNotBlank(dto.getName()) && !dto.getName().equals(entity.getName())) {
            ensureNameUnique(dto.getName(), id);
            entity.setName(dto.getName());
        }
        if (dto.getDescription() != null) {
            entity.setDescription(dto.getDescription());
        }
        // 嵌入模型可换（dimensions/vector_table 建库后不可变）；模型变更后旧向量数据需重建文档
        if (StringUtils.isNotBlank(dto.getEmbeddingModelId())
                && !dto.getEmbeddingModelId().equals(entity.getEmbeddingModelId())) {
            validateEmbeddingModel(dto.getEmbeddingModelId());
            entity.setEmbeddingModelId(dto.getEmbeddingModelId());
        }
        if (dto.getRetrieveLimit() != null) {
            entity.setRetrieveLimit(dto.getRetrieveLimit());
        }
        if (dto.getScoreThreshold() != null) {
            entity.setScoreThreshold(dto.getScoreThreshold());
        }
        if (dto.getRemark() != null) {
            entity.setRemark(dto.getRemark());
        }
        if (dto.getEnabled() != null) {
            entity.setEnabled(dto.getEnabled());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        knowledgeBaseMapper.updateById(entity);
        evictAdapter(id); // 配置变更，失效检索缓存（实体 + 向量库连接）
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        requireExists(id);
        documentServiceProvider.getObject().deleteByKbId(id);
        knowledgeBaseMapper.deleteById(id);
        evictAdapter(id);
    }

    @Override
    public RetrievalProbe testRetrieval(String id, TestKnowledgeRetrieval command) {
        KnowledgeBaseEntity kb = requireExists(id);
        if (!Boolean.TRUE.equals(kb.getEnabled())) {
            throw new AiBusinessException(AiErrorCode.KB_DISABLED, id);
        }
        SimpleKnowledgeAdapter adapter = adapterProvider.getIfAvailable();
        if (adapter == null) {
            throw new AiBusinessException(AiErrorCode.KB_RAG_NOT_ENABLED);
        }
        try {
            return adapter.probe(kb, command.getQuery().trim(), command.getLimit(), command.getScoreThreshold());
        } catch (AiBusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("知识库 {} 检索测试失败", id, e);
            throw new AiBusinessException(AiErrorCode.KB_RETRIEVAL_FAILED, e);
        }
    }

    @Override
    public KnowledgeBaseEntity loadById(String id) {
        return requireExists(id);
    }

    private void evictAdapter(String kbId) {
        SimpleKnowledgeAdapter adapter = adapterProvider.getIfAvailable();
        if (adapter != null) {
            adapter.evict(kbId);
        }
    }

    private void validateEmbeddingModel(String modelId) {
        LlmModelEntity model = llmModelService.loadById(modelId);
        if (model.getModelType() != ModelType.EMBEDDING || Boolean.FALSE.equals(model.getEnabled())) {
            throw new AiBusinessException(AiErrorCode.KB_EMBEDDING_MODEL_INVALID, modelId);
        }
    }

    private KnowledgeBaseEntity requireExists(String id) {
        KnowledgeBaseEntity entity = knowledgeBaseMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBaseEntity>().eq(KnowledgeBaseEntity::getId, id));
        if (entity == null) {
            throw new AiBusinessException(AiErrorCode.KB_NOT_FOUND, id);
        }
        return entity;
    }

    private void ensureNameUnique(String name, String excludeId) {
        boolean exists = knowledgeBaseMapper.exists(new LambdaQueryWrapper<KnowledgeBaseEntity>()
                .eq(KnowledgeBaseEntity::getTenantId, AuthUtils.getTenantId())
                .eq(KnowledgeBaseEntity::getName, name)
                .ne(excludeId != null, KnowledgeBaseEntity::getId, excludeId));
        if (exists) {
            throw new AiBusinessException(AiErrorCode.KB_NAME_EXISTS, name);
        }
    }
}
