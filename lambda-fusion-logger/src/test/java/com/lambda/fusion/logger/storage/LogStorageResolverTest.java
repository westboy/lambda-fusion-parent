package com.lambda.fusion.logger.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.lambda.fusion.logger.exception.LoggerBusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link LogStorageResolver} 按存储类型路由的单元测试。
 */
class LogStorageResolverTest {

    @Test
    void shouldResolveByTypeIgnoringCase() {
        LogStorage mysql = mock(LogStorage.class);
        org.mockito.Mockito.when(mysql.type()).thenReturn("MYSQL");
        LogStorageResolver resolver = new LogStorageResolver(List.of(mysql));

        assertThat(resolver.resolve("mysql")).isSameAs(mysql);
        assertThat(resolver.resolve("MYSQL")).isSameAs(mysql);
    }

    @Test
    void shouldDefaultToFirstWhenTypeNull() {
        // type 为 null 时不匹配任何实现，应抛出业务异常
        LogStorage mysql = mock(LogStorage.class);
        org.mockito.Mockito.when(mysql.type()).thenReturn("MYSQL");
        LogStorageResolver resolver = new LogStorageResolver(List.of(mysql));

        assertThatThrownBy(() -> resolver.resolve(null)).isInstanceOf(LoggerBusinessException.class);
    }

    @Test
    void shouldThrowWhenTypeUnsupported() {
        LogStorage mysql = mock(LogStorage.class);
        org.mockito.Mockito.when(mysql.type()).thenReturn("MYSQL");
        LogStorageResolver resolver = new LogStorageResolver(List.of(mysql));

        assertThatThrownBy(() -> resolver.resolve("KAFKA")).isInstanceOf(LoggerBusinessException.class);
    }
}
