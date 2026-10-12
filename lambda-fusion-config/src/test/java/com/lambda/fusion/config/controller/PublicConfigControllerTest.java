package com.lambda.fusion.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.config.model.QueryConfigList;
import com.lambda.fusion.config.model.entity.ConfigEntity;
import com.lambda.fusion.config.service.ConfigService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 公共配置端点单元测试：命名空间限定、键值映射与空值/重复键防御。
 *
 * @author zx
 */
class PublicConfigControllerTest {

    private final ConfigService configService = mock(ConfigService.class);
    private final PublicConfigController controller = new PublicConfigController(configService);

    private static ConfigEntity config(String key, String value) {
        ConfigEntity entity = new ConfigEntity();
        entity.setKey(key);
        entity.setValue(value);
        return entity;
    }

    @Test
    void returnsPublicNamespaceAsKeyValueMap() {
        when(configService.listConfigs(any()))
                .thenReturn(List.of(config("ai.console.copilot.app-id", "app-1"), config("other.public.key", "v2")));

        Map<String, String> result = controller.publicConfigs();

        assertThat(result)
                .containsEntry("ai.console.copilot.app-id", "app-1")
                .containsEntry("other.public.key", "v2")
                .hasSize(2);
        // 查询必须限定 public 命名空间（不泄漏应用私有配置）
        ArgumentCaptor<QueryConfigList> captor = ArgumentCaptor.forClass(QueryConfigList.class);
        verify(configService).listConfigs(captor.capture());
        assertThat(captor.getValue().getApplication()).isEqualTo("public");
    }

    @Test
    void blankKeysAndNullValuesAreFiltered() {
        when(configService.listConfigs(any()))
                .thenReturn(List.of(config("", "orphan"), config("valid", null), config("kept", "v")));

        Map<String, String> result = controller.publicConfigs();

        assertThat(result).containsOnlyKeys("valid", "kept");
        assertThat(result).containsEntry("kept", "v");
    }

    /** 同键重复（脏数据）时取首条，不因 toMap 冲突抛错。 */
    @Test
    void duplicateKeysKeepFirstEntry() {
        when(configService.listConfigs(any())).thenReturn(List.of(config("dup", "first"), config("dup", "second")));

        Map<String, String> result = controller.publicConfigs();

        assertThat(result).containsOnlyKeys("dup").containsEntry("dup", "first");
    }
}
