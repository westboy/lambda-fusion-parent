package com.lambda.fusion.dict.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.cloud.core.exception.IllegalArgumentException;
import com.lambda.fusion.dict.mapper.DictInfoMapper;
import com.lambda.fusion.dict.mapper.DictTypeMapper;
import com.lambda.fusion.dict.model.DictInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 字典项保存/更新的字典值唯一性：同 dictType 下 fieldType 重复显式拒绝
 *
 * <p>历史事故回归守门：选项 value 重复会让前端字典翻译取首个匹配、单选控件全选（如 workflow_category 四项同值 0）。
 *
 * @author Zx
 */
class DictInfoServiceImplTest {

    private DictInfoMapper mapper;

    private DictInfoServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(DictInfoMapper.class);
        service = new DictInfoServiceImpl(new ObjectMapper(), mapper, mock(DictTypeMapper.class));
    }

    private static DictInfo item(String id, String dictType, String fieldType) {
        DictInfo item = new DictInfo();
        item.setId(id);
        item.setDictType(dictType);
        item.setFieldType(fieldType);
        item.setFieldName("名称");
        item.setSort("0");
        item.setEnableState(1);
        return item;
    }

    @Test
    void saveRejectsDuplicateFieldValue() {
        when(mapper.selectOne(any())).thenReturn(item("other", "SEX", "1"));

        assertThatThrownBy(() -> service.saveDictInfo(null, item(null, "SEX", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字典值在该字典类型下已存在");
        verify(mapper, never()).insert(any(DictInfo.class));
    }

    @Test
    void saveAllowsUniqueFieldValue() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(DictInfo.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, DictInfo.class).setId("new-1");
            return 1;
        });
        when(mapper.selectById(any())).thenAnswer(invocation -> item("new-1", "SEX", "9"));

        service.saveDictInfo(null, item(null, "SEX", "9"));

        verify(mapper).insert(any(DictInfo.class));
    }

    @Test
    void updateRejectsDuplicateFieldValue() {
        when(mapper.selectById("1")).thenReturn(item("1", "SEX", "1"));
        when(mapper.selectOne(any())).thenReturn(item("other", "SEX", "2"));

        assertThatThrownBy(() -> service.updateDictInfo("1", item("1", "SEX", "2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字典值在该字典类型下已存在");
        verify(mapper, never()).updateById(any(DictInfo.class));
    }

    @Test
    void updateAllowsKeepingOwnValue() {
        when(mapper.selectById("1")).thenReturn(item("1", "SEX", "1"));
        when(mapper.selectOne(any())).thenReturn(item("1", "SEX", "1"));

        service.updateDictInfo("1", item("1", "SEX", "1"));

        verify(mapper).updateById(any(DictInfo.class));
    }
}
