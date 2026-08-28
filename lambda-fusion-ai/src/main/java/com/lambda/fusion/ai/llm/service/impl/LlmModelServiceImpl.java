package com.lambda.fusion.ai.llm.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.fusion.ai.AiConstants.MediaGenerationOperation;
import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiConstants.ProviderType;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.llm.mapper.LlmModelMapper;
import com.lambda.fusion.ai.llm.model.CreateLlmModel;
import com.lambda.fusion.ai.llm.model.LlmModelPage;
import com.lambda.fusion.ai.llm.model.UpdateLlmModel;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import com.lambda.fusion.ai.llm.model.entity.LlmProviderEntity;
import com.lambda.fusion.ai.llm.service.LlmModelService;
import com.lambda.fusion.ai.llm.service.LlmProviderService;
import com.lambda.fusion.ai.runtime.event.ConfigChangedEvent;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class LlmModelServiceImpl implements LlmModelService {

    private final LlmModelMapper llmModelMapper;
    private final LlmProviderService llmProviderService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public Page<LlmModelEntity> page(LlmModelPage query) {
        return llmModelMapper.selectPage(query.getPage(), query.getLambdaQueryWrapper());
    }

    @Override
    public LlmModelEntity get(String id) {
        return requireExists(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LlmModelEntity create(CreateLlmModel dto) {
        validateConfiguration(
                dto.getProviderId(), dto.getModelType(), dto.getEmbeddingDimensions(), dto.getMediaOperation());
        ensureNameUnique(dto.getName(), null);
        LlmModelEntity entity = new LlmModelEntity();
        entity.setId(IdUtil.getSnowflakeNextIdStr());
        entity.setProviderId(dto.getProviderId());
        entity.setName(dto.getName());
        entity.setModelName(dto.getModelName());
        entity.setModelType(dto.getModelType());
        entity.setEmbeddingDimensions(dto.getEmbeddingDimensions());
        entity.setMediaOperation(dto.getMediaOperation());
        entity.setDefaultTemperature(dto.getDefaultTemperature());
        entity.setDefaultMaxTokens(dto.getDefaultMaxTokens());
        entity.setContextWindowTokens(dto.getContextWindowTokens());
        entity.setSupportsVision(dto.getSupportsVision());
        entity.setEnabled(dto.getEnabled());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        llmModelMapper.insert(entity);
        eventPublisher.publishEvent(ConfigChangedEvent.all()); // 全量失效 Agent 缓存
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(String id, UpdateLlmModel dto) {
        LlmModelEntity entity = requireExists(id);
        String targetProviderId = StringUtils.defaultIfBlank(dto.getProviderId(), entity.getProviderId());
        ModelType targetModelType = dto.getModelType() == null ? entity.getModelType() : dto.getModelType();
        Integer targetEmbeddingDimensions = targetModelType == ModelType.EMBEDDING
                ? dto.getEmbeddingDimensions() != null ? dto.getEmbeddingDimensions() : entity.getEmbeddingDimensions()
                : dto.getEmbeddingDimensions();
        MediaGenerationOperation targetOperation = dto.getMediaOperation() != null
                ? dto.getMediaOperation()
                : isMedia(targetModelType) ? entity.getMediaOperation() : null;
        validateConfiguration(targetProviderId, targetModelType, targetEmbeddingDimensions, targetOperation);
        if (!targetProviderId.equals(entity.getProviderId())) {
            entity.setProviderId(targetProviderId);
        }
        if (StringUtils.isNotBlank(dto.getName()) && !dto.getName().equals(entity.getName())) {
            ensureNameUnique(dto.getName(), id);
            entity.setName(dto.getName());
        }
        if (StringUtils.isNotBlank(dto.getModelName())) {
            entity.setModelName(dto.getModelName());
        }
        if (dto.getModelType() != null) {
            entity.setModelType(dto.getModelType());
        }
        entity.setEmbeddingDimensions(targetEmbeddingDimensions);
        entity.setMediaOperation(targetOperation);
        if (dto.getDefaultTemperature() != null) {
            entity.setDefaultTemperature(dto.getDefaultTemperature());
        }
        if (dto.getDefaultMaxTokens() != null) {
            entity.setDefaultMaxTokens(dto.getDefaultMaxTokens());
        }
        if (dto.getContextWindowTokens() != null) {
            entity.setContextWindowTokens(dto.getContextWindowTokens());
        }
        if (dto.getSupportsVision() != null) {
            entity.setSupportsVision(dto.getSupportsVision());
        }
        if (dto.getEnabled() != null) {
            entity.setEnabled(dto.getEnabled());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        llmModelMapper.updateById(entity);
        eventPublisher.publishEvent(ConfigChangedEvent.all());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        requireExists(id);
        llmModelMapper.deleteById(id);
        eventPublisher.publishEvent(ConfigChangedEvent.all());
    }

    @Override
    public LlmModelEntity loadById(String id) {
        return requireExists(id);
    }

    private LlmModelEntity requireExists(String id) {
        LlmModelEntity entity =
                llmModelMapper.selectOne(new LambdaQueryWrapper<LlmModelEntity>().eq(LlmModelEntity::getId, id));
        if (entity == null) {
            throw new AiBusinessException(AiErrorCode.LLM_MODEL_NOT_FOUND, id);
        }
        return entity;
    }

    private void ensureNameUnique(String name, String excludeId) {
        boolean exists = llmModelMapper.exists(new LambdaQueryWrapper<LlmModelEntity>()
                .eq(LlmModelEntity::getName, name)
                .ne(excludeId != null, LlmModelEntity::getId, excludeId));
        if (exists) {
            throw new AiBusinessException(AiErrorCode.LLM_MODEL_NAME_EXISTS, name);
        }
    }

    private void validateConfiguration(
            String providerId,
            ModelType modelType,
            Integer embeddingDimensions,
            MediaGenerationOperation mediaOperation) {
        LlmProviderEntity provider = llmProviderService.loadById(providerId);
        ProviderType providerType = ProviderType.of(provider.getProviderType());
        if (providerType == null) {
            throw new AiBusinessException(AiErrorCode.LLM_PROVIDER_TYPE_NOT_SUPPORTED, provider.getProviderType());
        }
        if (!providerType.supports(modelType)) {
            throw new AiBusinessException(AiErrorCode.LLM_MODEL_TYPE_NOT_SUPPORTED, modelType);
        }
        validateEmbeddingDimensions(modelType, embeddingDimensions);
        if (!isMedia(modelType)) {
            if (mediaOperation != null) {
                throw new AiBusinessException(AiErrorCode.LLM_MEDIA_OPERATION_INVALID, "非媒体模型不能设置创作操作");
            }
            return;
        }
        if (mediaOperation == null) {
            throw new AiBusinessException(AiErrorCode.LLM_MEDIA_OPERATION_INVALID, "媒体模型必须选择创作操作");
        }
        if (mediaOperation.getModelType() != modelType) {
            throw new AiBusinessException(AiErrorCode.LLM_MEDIA_OPERATION_INVALID, "创作操作与模型类型不匹配");
        }
        if (!providerType.supports(mediaOperation)) {
            throw new AiBusinessException(AiErrorCode.LLM_MEDIA_OPERATION_INVALID, "当前提供方不支持该创作操作");
        }
    }

    private static void validateEmbeddingDimensions(ModelType modelType, Integer embeddingDimensions) {
        if (modelType == ModelType.EMBEDDING) {
            if (embeddingDimensions == null || embeddingDimensions <= 0) {
                throw new AiBusinessException(
                        AiErrorCode.LLM_EMBEDDING_DIMENSIONS_INVALID, "EMBEDDING 类型必须配置大于0的默认向量维度");
            }
            return;
        }
        if (embeddingDimensions != null) {
            throw new AiBusinessException(AiErrorCode.LLM_EMBEDDING_DIMENSIONS_INVALID, "非 EMBEDDING 类型不能配置默认向量维度");
        }
    }

    private static boolean isMedia(ModelType modelType) {
        return modelType == ModelType.IMAGE_GEN || modelType == ModelType.VIDEO_GEN || modelType == ModelType.AUDIO_GEN;
    }
}
