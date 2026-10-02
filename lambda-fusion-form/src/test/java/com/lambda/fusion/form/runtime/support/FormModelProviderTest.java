package com.lambda.fusion.form.runtime.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lambda.fusion.form.FormProperties;
import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.entity.FormDefVersionEntity;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.design.mapper.FormDefVersionMapper;
import com.lambda.fusion.form.exception.FormBusinessException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class FormModelProviderTest {
    private FormProperties properties;
    private FormModelCache cache;
    private FormModelProvider provider;
    private FormDefEntity definition;
    private FormDefVersionMapper versions;
    private FormModelCompiler compiler;
    private SnapshotJson json;

    @BeforeEach
    void setUp() {
        properties = new FormProperties();
        properties.setAllowedTables(List.of("la_demo_leave"));
        cache = new FormModelCache(properties);
        json = new SnapshotJson(new ObjectMapper());
        compiler = new FormModelCompiler(json);
        FormDefMapper definitions = mock(FormDefMapper.class);
        versions = mock(FormDefVersionMapper.class);
        definition = new FormDefEntity();
        definition.setId("formA");
        definition.setFormKey("leave_demo");
        definition.setTableName("la_demo_leave");
        definition.setStatus("PUBLISHED");
        definition.setPublishedVersion(1L);
        when(definitions.selectOne(any())).thenReturn(definition);
        when(versions.selectOne(any())).thenAnswer(invocation -> version(definition.getPublishedVersion()));
        provider = new FormModelProvider(definitions, versions, compiler, cache, properties);
    }

    @Test
    void lateOldLoadCannotBeUsedAsTheCurrentVersion() {
        FormModel original = provider.require("leave_demo");
        definition.setPublishedVersion(2L);
        provider.evictAfterCommit("formA");
        assertThat(provider.require("leave_demo").version()).isEqualTo(2);
        // 模拟已读取 v1 的请求在 v2 提交失效之后才回填。
        cache.put("formA", original);
        assertThat(provider.require("leave_demo").version()).isEqualTo(2);
        definition.setPublishedVersion(1L);
        assertThat(provider.require("leave_demo").version()).isEqualTo(1);
    }

    @Test
    void sameFormKeyInAnotherTenantDoesNotReuseTheFirstDefinitionsModel() {
        FormModel first = provider.require("leave_demo");
        definition.setId("formB");
        assertThat(provider.require("leave_demo")).isNotSameAs(first);
        assertThat(cache.get("formA", 1)).isSameAs(first);
    }

    @Test
    void cachedModelDoesNotBypassDisabledStatusOrTableBoundary() {
        provider.require("leave_demo");
        definition.setStatus("DISABLED");
        assertThatThrownBy(() -> provider.require("leave_demo")).isInstanceOf(FormBusinessException.class);
        definition.setStatus("PUBLISHED");
        properties.setAllowedTables(List.of());
        assertThatThrownBy(() -> provider.require("leave_demo")).isInstanceOf(FormBusinessException.class);
    }

    @Test
    void evictsAfterCommitAndPreservesCacheOnRollback() {
        FormModel model = provider.require("leave_demo");
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:h2:mem:cache_commit", "sa", "")));
        transaction.executeWithoutResult(status -> {
            provider.evictAfterCommit("formA");
            assertThat(cache.get("formA", 1)).isSameAs(model);
            status.setRollbackOnly();
        });
        assertThat(cache.get("formA", 1)).isSameAs(model);
        transaction.executeWithoutResult(status -> {
            provider.evictAfterCommit("formA");
            assertThat(cache.get("formA", 1)).isSameAs(model);
        });
        assertThat(cache.get("formA", 1)).isNull();
    }

    private FormDefVersionEntity version(long number) {
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("leave_demo");
        snapshot.setFormType("SINGLE");
        snapshot.setTableName("la_demo_leave");
        FormDefVersionEntity version = new FormDefVersionEntity();
        version.setVersion(number);
        version.setSnapshotJson(json.write(snapshot));
        return version;
    }
}
