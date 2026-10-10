package com.lambda.fusion.dict.service.impl;

import static com.lambda.fusion.core.FusionConstants.JOINER;
import static com.lambda.fusion.dict.DictConstants.*;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.IService;
import com.google.common.collect.Maps;
import com.lambda.cloud.core.principal.LoginUser;
import com.lambda.cloud.core.utils.Assert;
import com.lambda.fusion.core.service.AbstractCrudService;
import com.lambda.fusion.core.tree.builder.TreeBuilder;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.core.utils.SqlParamUtils;
import com.lambda.fusion.dict.mapper.DictInfoMapper;
import com.lambda.fusion.dict.mapper.DictTypeMapper;
import com.lambda.fusion.dict.model.*;
import com.lambda.fusion.dict.service.DictInfoService;
import com.lambda.fusion.dict.support.registry.DictRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 多级数据字典详细信息
 *
 * @author Jin
 */
@SuppressFBWarnings("EI_EXPOSE_REP2")
@RequiredArgsConstructor
@Service
@Transactional(rollbackFor = Exception.class)
public class DictInfoServiceImpl extends AbstractCrudService<DictInfo, InputDictInfo, DictInfoMapper>
        implements DictInfoService, IService<DictInfo> {

    private final ObjectMapper objectMapper;

    private final DictInfoMapper dictInfoMapper;

    private final DictTypeMapper dictTypeMapper;

    @Override
    public IPage<DictInfo> page(QueryDictInfoPage pageQueryDTO) {
        IPage<DictInfo> dictInfoPage =
                dictInfoMapper.selectPage(pageQueryDTO.getPage(), pageQueryDTO.getLambdaQueryWrapper());
        return dictInfoPage.convert(dictInfo -> {
            Map<String, Object> stringObjectMap = convertMap(dictInfo.getExtra());
            dictInfo.setParameters(stringObjectMap);
            dictInfo.setExtra(StringUtils.EMPTY);
            return dictInfo;
        });
    }

    @Override
    public List<DictInfo> selectDictInfo(QueryDictInfo queryDTO) {
        String tenantId = AuthUtils.getTenantId();
        List<DictInfo> dictInfos = dictInfoMapper.selectDictInfo(new LambdaQueryWrapper<DictInfo>()
                .eq(DictInfo::getDictType, queryDTO.getDictType())
                .eq(StrUtil.isNotEmpty(tenantId), DictInfo::getTenantId, tenantId)
                .like(StrUtil.isNotEmpty(queryDTO.getFieldType()), DictInfo::getFieldType, queryDTO.getFieldType())
                .like(StrUtil.isNotEmpty(queryDTO.getFieldName()), DictInfo::getFieldName, queryDTO.getFieldName())
                .eq(queryDTO.getEnableState() != null, DictInfo::getEnableState, queryDTO.getEnableState())
                .eq(StrUtil.isNotEmpty(queryDTO.getDictInfoId()), DictInfo::getParentId, queryDTO.getDictInfoId())
                .orderByAsc(DictInfo::getDictType, DictInfo::getSort)
                .orderByDesc(DictInfo::getId));
        if (CollectionUtils.isEmpty(dictInfos)) {
            return Collections.emptyList();
        }
        dictInfos.forEach(info -> {
            info.setParameters(convertMap(info.getExtra()));
            info.setExtra(StringUtils.EMPTY);
        });
        return TreeBuilder.build(dictInfos);
    }

    @Override
    public DictInfo saveDictInfo(LoginUser operator, DictInfo source) {
        String dictType = source.getDictType();
        String fieldType = source.getFieldType();
        String fieldName = source.getFieldName();
        Assert.notNull(source, ERROR_DICT_NOT_NULL);
        Assert.hasText(dictType, MSG_DICT_TYPE_NOT_EMPTY);
        Assert.hasText(fieldType, MSG_DICT_FIELD_TYPE_NOT_EMPTY);
        Assert.hasText(fieldName, MSG_DICT_FIELD_NAME_NOT_EMPTY);
        Assert.notNull(source.getSort(), MSG_DICT_SORT_NUMBER_NOT_EMPTY);
        Assert.notNull(source.getEnableState(), MSG_DICT_ENABLED_NOT_EMPTY);
        // 字典值在同级字典类型内必须唯一：选项 value 重复会让前端翻译与单选语义失真（历史事故：四项同值 0）
        Assert.isFalse(dictInfoExists(source), MSG_DICT_FIELD_VALUE_EXISTED);
        source.setExtra(
                CollectionUtils.isNotEmpty(source.getParameters()) ? convertJson(source.getParameters()) : null);
        if (StringUtils.isNotBlank(source.getParentId())) {
            DictInfo parent = dictInfoMapper.selectById(source.getParentId());
            if (null != parent) {
                source.setParentKeys(parent.buildParentKeys());
                source.setLevel(parent.getLevel() + 1);
            }
        } else {
            source.setLevel(DEFAULT_LEVEL);
        }
        dictInfoMapper.insert(source);
        DictInfo dictInfo = dictInfoMapper.selectById(source.getId());
        dictInfo.setParameters(convertMap(dictInfo.getExtra()));
        return dictInfo;
    }

    public boolean dictInfoExists(DictInfo dictInfo) {
        LambdaQueryWrapper<DictInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DictInfo::getDictType, dictInfo.getDictType());
        wrapper.eq(DictInfo::getFieldType, dictInfo.getFieldType());
        // 租户
        if (StringUtils.isNotBlank(dictInfo.getTenantId())) {
            wrapper.eq(DictInfo::getTenantId, dictInfo.getTenantId());
        }
        DictInfo target = dictInfoMapper.selectOne(wrapper);
        return target != null && !target.getId().equals(dictInfo.getId());
    }

    @Override
    public void updateDictInfo(String id, DictInfo dictInfo) {
        Assert.notNull(id, MSG_DICT_ID_NOT_EMPTY);
        Assert.notNull(dictInfoMapper.selectById(id), MSG_DICT_UPDATE_DATA_NOT_EXISTED);
        dictInfo.setExtra(
                CollectionUtils.isNotEmpty(dictInfo.getParameters()) ? convertJson(dictInfo.getParameters()) : null);
        // 重复值显式拒绝，不再静默丢弃提交值（静默行为曾掩盖字典值重复的配置错误）
        Assert.isFalse(dictInfoExists(dictInfo), MSG_DICT_FIELD_VALUE_EXISTED);
        dictInfoMapper.updateById(dictInfo);
        dictInfoMapper.selectById(id);
    }

    @Override
    public void updateEnableState(OperationDictState operationDTO) {
        DictInfo parameter = new DictInfo();
        parameter.setId(operationDTO.getId());
        parameter.setEnableState(operationDTO.getState());
        dictInfoMapper.updateById(parameter);
    }

    @Override
    public void updateSelectableState(OperationDictState operationDTO) {
        DictInfo parameter = new DictInfo();
        parameter.setId(operationDTO.getId());
        parameter.setSelectable(operationDTO.getState());
        dictInfoMapper.updateById(parameter);
    }

    @Override
    public Map<String, Object> getStaticDictInfoGroup(String dictType) {
        String tenantId = AuthUtils.getTenantId();
        List<DictInfoGroup> lists = dictInfoMapper.getAllDictInfoGroup(
                StringUtils.isNotBlank(dictType) ? SqlParamUtils.fuzzyQuery(dictType) : dictType, tenantId);
        Map<String, Object> map = Maps.newHashMapWithExpectedSize(lists.size());
        for (DictInfoGroup info : lists) {
            map.put(info.getDictType(), info.getDictList());
        }
        return map;
    }

    @Override
    public Map<String, DictType> getCompositeDictInfoGroup(String dictType) {
        final LambdaQueryWrapper<DictTypeTree> query = Wrappers.lambdaQuery(DictTypeTree.class);
        if (StringUtils.isNotEmpty(dictType)) {
            query.like(DictTypeTree::getDictType, dictType);
        }
        final List<DictTypeTree> dictTypeTrees = dictTypeMapper.selectList(query);
        Map<String, DictType> result = Maps.newHashMapWithExpectedSize(dictTypeTrees.size());

        // Enum List
        final List<DictTypeTree> enumList = DictRegistry.getDictTypeList();
        if (CollectionUtils.isNotEmpty(enumList)) {
            dictTypeTrees.addAll(enumList);
        }

        for (DictTypeTree dictTypeTreeItem : dictTypeTrees) {
            final DictType vo = BeanUtil.copyProperties(dictTypeTreeItem, DictType.class);
            result.put(vo.getDictType(), vo);
        }
        String tenantId = AuthUtils.getTenantId();
        List<DictInfoGroup> lists = dictInfoMapper.getAllDictInfoGroup(
                StringUtils.isNotBlank(dictType) ? SqlParamUtils.fuzzyQuery(dictType) : dictType, tenantId);
        for (DictInfoGroup info : lists) {
            if (result.containsKey(info.getDictType())) {
                final DictType vo = result.get(info.getDictType());
                vo.setData(info.getDictList());
            }
        }
        return result;
    }

    @Override
    public List<DictInfo> getTreeData(String dictType) {
        if (StringUtils.isBlank(dictType)) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        LambdaQueryWrapper<DictTypeTree> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DictTypeTree::getDictType, dictType);
        DictTypeTree dictTypeTreeEntity = dictTypeMapper.selectOne(wrapper);
        if (dictTypeTreeEntity == null) {
            return Collections.emptyList();
        }
        String currentKey = dictTypeTreeEntity.getParentKeys();
        if (StringUtils.isNotBlank(currentKey)) {
            if (currentKey.contains(JOINER)) {
                ids = Arrays.asList(currentKey.split(JOINER));
            } else {
                ids.add(currentKey);
            }
        } else {
            ids.add(dictTypeTreeEntity.getId());
        }
        List<DictInfo> outcomes = dictInfoMapper.treeList(ids, AuthUtils.getTenantId());
        return TreeBuilder.build(outcomes);
    }

    @Override
    public List<DictInfo> getSubTreeData(String dictType) {
        List<DictInfo> outcomes = new ArrayList<>();
        if (StringUtils.isNotBlank(dictType)) {
            LambdaQueryWrapper<DictTypeTree> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DictTypeTree::getDictType, dictType);
            DictTypeTree dictTypeTreeEntity = dictTypeMapper.selectOne(wrapper);
            String currentKey = dictTypeTreeEntity.getId();
            LambdaQueryWrapper<DictTypeTree> conditions = new LambdaQueryWrapper<>();
            conditions.likeRight(DictTypeTree::getParentKeys, currentKey).or().eq(DictTypeTree::getId, currentKey);
            List<DictTypeTree> dictTypeTrees = dictTypeMapper.selectList(conditions);
            if (CollectionUtils.isNotEmpty(dictTypeTrees)) {
                List<String> ids = dictTypeTrees.stream().map(DictTypeTree::id).collect(Collectors.toList());
                List<DictInfo> list = dictInfoMapper.treeList(ids, AuthUtils.getTenantId());
                list.forEach(info -> info.setParameters(
                        StringUtils.isNotBlank(info.getExtra()) ? convertMap(info.getExtra()) : null));
                outcomes = TreeBuilder.build(list);
            }
        }
        outcomes.sort(Comparator.comparing(DictInfo::getSort));
        return outcomes;
    }

    @Override
    public List<DictInfo> getDictInfoByParentId(String parentId) {
        DictInfo dictionaryEntry = dictInfoMapper.selectById(parentId);
        if (Objects.isNull(dictionaryEntry)) {
            return Collections.emptyList();
        }
        return TreeBuilder.build(queryParentDictInfo(dictionaryEntry));
    }

    @Override
    public boolean deleteDictInfoById(String id) {
        try {
            dictInfoMapper.deleteById(id);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<DictInfo> queryParentDictInfo(DictInfo dictionaryEntry) {
        DictInfo wrapper = new DictInfo();
        String keys = dictionaryEntry.getParentKeys();
        if (StringUtils.isNotBlank(keys)) {
            wrapper.setParentId(SqlParamUtils.fuzzyQuery(keys.substring(keys.length() - PARENT_KEY_SUBSTRING_LENGTH)));
        } else {
            wrapper.setId(dictionaryEntry.getId());
        }
        wrapper.setLevel(dictionaryEntry.getLevel());
        wrapper.setDictType(dictionaryEntry.getDictType());
        wrapper.setTenantId(AuthUtils.getTenantId());

        List<DictInfo> target = dictInfoMapper.getDictInfoList(wrapper);
        target.forEach(info -> {
            info.setParameters(convertMap(info.getExtra()));
            info.setExtra(StringUtils.EMPTY);
        });
        return target;
    }

    private String convertJson(Map<String, Object> map) {
        return objectMapper.writeValueAsString(map);
    }

    private Map<String, Object> convertMap(String extra) {
        if (StringUtils.isNotBlank(extra)) {
            return objectMapper.readValue(extra, new TypeReference<>() {});
        }
        return null;
    }
}
