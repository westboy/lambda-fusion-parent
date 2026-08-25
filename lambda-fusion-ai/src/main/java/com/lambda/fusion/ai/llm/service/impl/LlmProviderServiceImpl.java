package com.lambda.fusion.ai.llm.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.fusion.ai.AiConstants.ProviderType;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.llm.mapper.LlmProviderMapper;
import com.lambda.fusion.ai.llm.model.CreateLlmProvider;
import com.lambda.fusion.ai.llm.model.LlmProviderPageQuery;
import com.lambda.fusion.ai.llm.model.UpdateLlmProvider;
import com.lambda.fusion.ai.llm.model.entity.LlmProviderEntity;
import com.lambda.fusion.ai.llm.service.LlmProviderService;
import com.lambda.fusion.ai.runtime.event.ConfigChangedEvent;
import com.lambda.fusion.ai.security.KeyEncryptionService;
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
public class LlmProviderServiceImpl implements LlmProviderService {

    private final LlmProviderMapper llmProviderMapper;
    private final KeyEncryptionService keyEncryptionService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public Page<LlmProviderEntity> page(LlmProviderPageQuery query) {
        return llmProviderMapper.selectPage(query.getPage(), query.getLambdaQueryWrapper());
    }

    @Override
    public LlmProviderEntity get(String id) {
        return requireExists(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LlmProviderEntity create(CreateLlmProvider dto) {
        validateProviderType(dto.getProviderType());
        ensureNameUnique(dto.getName(), null);
        LlmProviderEntity entity = new LlmProviderEntity();
        entity.setId(IdUtil.getSnowflakeNextIdStr());
        entity.setName(dto.getName());
        entity.setProviderType(dto.getProviderType());
        entity.setBaseUrl(dto.getBaseUrl());
        entity.setApiKeyEncrypted(keyEncryptionService.encrypt(dto.getApiKey()));
        entity.setEnabled(dto.getEnabled());
        entity.setRemark(dto.getRemark());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        llmProviderMapper.insert(entity);
        eventPublisher.publishEvent(ConfigChangedEvent.all());
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(String id, UpdateLlmProvider dto) {
        LlmProviderEntity entity = requireExists(id);
        if (StringUtils.isNotBlank(dto.getProviderType())) {
            validateProviderType(dto.getProviderType());
            entity.setProviderType(dto.getProviderType());
        }
        if (StringUtils.isNotBlank(dto.getName()) && !dto.getName().equals(entity.getName())) {
            ensureNameUnique(dto.getName(), id);
            entity.setName(dto.getName());
        }
        if (dto.getBaseUrl() != null) {
            entity.setBaseUrl(dto.getBaseUrl());
        }
        if (StringUtils.isNotBlank(dto.getApiKey())) {
            entity.setApiKeyEncrypted(keyEncryptionService.encrypt(dto.getApiKey()));
        }
        if (dto.getEnabled() != null) {
            entity.setEnabled(dto.getEnabled());
        }
        if (dto.getRemark() != null) {
            entity.setRemark(dto.getRemark());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        llmProviderMapper.updateById(entity);
        eventPublisher.publishEvent(ConfigChangedEvent.all());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        requireExists(id);
        llmProviderMapper.deleteById(id);
        eventPublisher.publishEvent(ConfigChangedEvent.all());
    }

    @Override
    public LlmProviderEntity loadById(String id) {
        return requireExists(id);
    }

    private LlmProviderEntity requireExists(String id) {
        LlmProviderEntity entity = llmProviderMapper.selectOne(
                new LambdaQueryWrapper<LlmProviderEntity>().eq(LlmProviderEntity::getId, id));
        if (entity == null) {
            throw new AiBusinessException(AiErrorCode.LLM_PROVIDER_NOT_FOUND, id);
        }
        return entity;
    }

    private void ensureNameUnique(String name, String excludeId) {
        boolean exists = llmProviderMapper.exists(new LambdaQueryWrapper<LlmProviderEntity>()
                .eq(LlmProviderEntity::getName, name)
                .ne(excludeId != null, LlmProviderEntity::getId, excludeId));
        if (exists) {
            throw new AiBusinessException(AiErrorCode.LLM_PROVIDER_NAME_EXISTS, name);
        }
    }

    private void validateProviderType(String providerType) {
        if (ProviderType.of(providerType) == null) {
            throw new AiBusinessException(AiErrorCode.LLM_PROVIDER_TYPE_NOT_SUPPORTED, providerType);
        }
    }
}
