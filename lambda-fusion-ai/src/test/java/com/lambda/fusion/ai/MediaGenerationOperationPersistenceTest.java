package com.lambda.fusion.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler;
import com.lambda.fusion.ai.AiConstants.MediaGenerationOperation;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

class MediaGenerationOperationPersistenceTest {

    @Test
    void readsOperationNameAsStringEnumValue() throws Exception {
        assertThat(MybatisEnumTypeHandler.findEnumValueFieldName(MediaGenerationOperation.class))
                .contains("code");

        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getObject("media_operation", String.class)).thenReturn("TEXT_TO_VIDEO");
        PreparedStatement statement = mock(PreparedStatement.class);

        var handler = new MybatisEnumTypeHandler<>(MediaGenerationOperation.class);

        assertThat(handler.getResult(resultSet, "media_operation")).isEqualTo(MediaGenerationOperation.TEXT_TO_VIDEO);
        handler.setParameter(statement, 1, MediaGenerationOperation.TEXT_TO_VIDEO, JdbcType.VARCHAR);
        verify(statement).setObject(1, "TEXT_TO_VIDEO", JdbcType.VARCHAR.TYPE_CODE);
        assertThat(MediaGenerationOperation.TEXT_TO_VIDEO.getCode()).isEqualTo("TEXT_TO_VIDEO");
    }
}
