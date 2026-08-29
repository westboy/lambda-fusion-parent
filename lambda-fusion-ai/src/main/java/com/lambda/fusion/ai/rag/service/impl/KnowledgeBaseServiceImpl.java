package com.lambda.fusion.ai.rag.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.fusion.ai.AiConstants.KnowledgeRetrievalMode;
import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiProperties;
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
    private final AiProperties aiProperties;
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
        KnowledgeRetrievalMode retrievalMode = resolveRetrievalMode(dto.getRetrievalMode());
        EmbeddingConfiguration embedding =
                resolveEmbeddingConfiguration(retrievalMode, dto.getEmbeddingModelId(), dto.getDimensions(), null);
        ensureNameUnique(dto.getName(), null);
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(IdUtil.getSnowflakeNextIdStr());
        entity.setTenantId(AuthUtils.getTenantId());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setRetrievalMode(retrievalMode.getCode());
        entity.setEmbeddingModelId(embedding.modelId());
        entity.setDimensions(embedding.dimensions());
        if (retrievalMode.isVectorRequired()) {
            // 每知识库一张向量表，表名由系统生成（SearchDocumentDto 无 payload 过滤，无法单表按 KB 隔离）
            entity.setVectorTable("ai_kb_" + entity.getId());
        }
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
        KnowledgeRetrievalMode currentMode = resolveRetrievalMode(entity.getRetrievalMode());
        KnowledgeRetrievalMode nextMode = StringUtils.isNotBlank(dto.getRetrievalMode())
                ? requireRetrievalMode(dto.getRetrievalMode())
                : currentMode;
        String nextEmbeddingModelId = StringUtils.isNotBlank(dto.getEmbeddingModelId())
                ? dto.getEmbeddingModelId()
                : entity.getEmbeddingModelId();
        EmbeddingConfiguration embedding =
                resolveEmbeddingConfiguration(nextMode, nextEmbeddingModelId, null, entity.getDimensions());
        boolean reindexRequired =
                nextMode != currentMode || !StringUtils.equals(embedding.modelId(), entity.getEmbeddingModelId());
        if (StringUtils.isNotBlank(dto.getName()) && !dto.getName().equals(entity.getName())) {
            ensureNameUnique(dto.getName(), id);
            entity.setName(dto.getName());
        }
        if (dto.getDescription() != null) {
            entity.setDescription(dto.getDescription());
        }
        entity.setRetrievalMode(nextMode.getCode());
        entity.setEmbeddingModelId(embedding.modelId());
        entity.setDimensions(embedding.dimensions());
        if (nextMode.isVectorRequired() && StringUtils.isBlank(entity.getVectorTable())) {
            entity.setVectorTable("ai_kb_" + entity.getId());
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
        evictAdapter(id); // 配置变更，失效实体、向量连接和 Lucene 句柄缓存
        if (reindexRequired) {
            documentServiceProvider.getObject().reingestByKbId(id);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        requireExists(id);
        documentServiceProvider.getObject().deleteByKbId(id);
        SimpleKnowledgeAdapter adapter = adapterProvider.getIfAvailable();
        if (adapter != null) {
            adapter.deleteKnowledgeBase(id);
        }
        knowledgeBaseMapper.deleteById(id);
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

    private LlmModelEntity validateEmbeddingModel(String modelId) {
        LlmModelEntity model = llmModelService.loadById(modelId);
        if (model.getModelType() != ModelType.EMBEDDING || Boolean.FALSE.equals(model.getEnabled())) {
            throw new AiBusinessException(AiErrorCode.KB_EMBEDDING_MODEL_INVALID, modelId);
        }
        return model;
    }

    private KnowledgeRetrievalMode resolveRetrievalMode(String code) {
        return requireRetrievalMode(
                StringUtils.defaultIfBlank(code, aiProperties.getRag().getDefaultRetrievalMode()));
    }

    private static KnowledgeRetrievalMode requireRetrievalMode(String code) {
        KnowledgeRetrievalMode mode = KnowledgeRetrievalMode.of(code);
        if (mode == null) {
            throw new AiBusinessException(AiErrorCode.KB_RETRIEVAL_MODE_INVALID, code);
        }
        return mode;
    }

    private EmbeddingConfiguration resolveEmbeddingConfiguration(
            KnowledgeRetrievalMode mode, String modelId, Integer requestedDimensions, Integer existingDimensions) {
        if (StringUtils.isBlank(modelId)) {
            if (mode.isVectorRequired()) {
                throw new AiBusinessException(AiErrorCode.KB_EMBEDDING_MODEL_REQUIRED);
            }
            if (requestedDimensions != null) {
                throw new AiBusinessException(AiErrorCode.CONFIGURATION_ERROR, "未配置嵌入模型时不能单独配置向量维度");
            }
            return new EmbeddingConfiguration(null, null);
        }
        LlmModelEntity model = validateEmbeddingModel(modelId);
        Integer modelDimensions = model.getEmbeddingDimensions();
        Integer dimensions = existingDimensions != null
                ? existingDimensions
                : requestedDimensions != null ? requestedDimensions : modelDimensions;
        if (dimensions == null || dimensions <= 0) {
            throw new AiBusinessException(AiErrorCode.LLM_EMBEDDING_DIMENSIONS_INVALID, "请先为嵌入模型配置默认向量维度，或在知识库中显式填写");
        }
        if (existingDimensions != null && modelDimensions != null && !existingDimensions.equals(modelDimensions)) {
            throw new AiBusinessException(
                    AiErrorCode.LLM_EMBEDDING_DIMENSIONS_INVALID,
                    "新嵌入模型维度 " + modelDimensions + " 与知识库既有维度 " + existingDimensions + " 不一致");
        }
        return new EmbeddingConfiguration(modelId, dimensions);
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

    private record EmbeddingConfiguration(String modelId, Integer dimensions) {}
}
