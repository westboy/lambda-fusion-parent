package com.lambda.fusion.ai.rag.store;

import cn.hutool.core.util.IdUtil;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.rag.model.entity.KnowledgeBaseEntity;
import com.lambda.fusion.ai.rag.runtime.IngestChunk;
import com.lambda.fusion.ai.rag.runtime.RetrievedChunk;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * 使用 OceanBase MySQL 模式全文索引的知识库 chunk 存储。所有知识库共享一张表并按
 * {@code kb_id} 隔离；表结构与 IK 分词全文索引由 Liquibase 变更集 {@code lambda-ai-changelog.xml}
 * 创建，本类只做读写。依赖 OB 4.3.5+ 的全文索引能力（BM25 相关性打分、IK 分词器）。
 *
 * @author Jin
 */
public class OceanBaseFullTextIndex implements KnowledgeFullTextIndex {

    /** {@link RetrievedChunk#scoreType()} 的取值；区别于 Lucene 的 BM25 与向量的 COSINE。 */
    public static final String SCORE_TYPE = "OB_FTS";

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final int MAX_IDENTIFIER_LENGTH = 64;

    private static final String SCORE_EXPRESSION = "MATCH(content) AGAINST(? IN NATURAL LANGUAGE MODE)";

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String tableName;

    public OceanBaseFullTextIndex(String jdbcUrl, String username, String password, String tableName) {
        if (StringUtils.isBlank(jdbcUrl)) {
            throw new AiBusinessException(AiErrorCode.CONFIGURATION_ERROR, "OceanBase 全文索引 JDBC URL 不能为空");
        }
        validateIdentifier(tableName);
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.tableName = tableName;
    }

    /** 单事务内先删后插，失败时回滚，保证不会留下部分新文档。 */
    @Override
    public void replaceDocument(
            KnowledgeBaseEntity kb, String docId, String tenantId, String fileName, List<IngestChunk> chunks) {
        String deleteSql = "DELETE FROM " + tableName + " WHERE kb_id = ? AND doc_id = ?";
        String insertSql = "INSERT INTO " + tableName
                + " (id, kb_id, tenant_id, doc_id, chunk_id, content, file_name, chunk_index, chunk_count, "
                + "section_path) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement(deleteSql);
                    PreparedStatement insert = connection.prepareStatement(insertSql)) {
                delete.setString(1, kb.getId());
                delete.setString(2, docId);
                delete.executeUpdate();
                for (IngestChunk chunk : chunks) {
                    int index = 1;
                    insert.setString(index++, IdUtil.getSnowflakeNextIdStr());
                    insert.setString(index++, kb.getId());
                    setNullableString(insert, index++, tenantId);
                    insert.setString(index++, docId);
                    insert.setString(index++, chunk.chunkId());
                    insert.setString(index++, chunk.text());
                    setNullableString(insert, index++, fileName);
                    setNullableInt(insert, index++, chunk.chunkIndex());
                    setNullableInt(insert, index++, chunks.size());
                    setNullableString(insert, index, chunk.sectionPath());
                    insert.addBatch();
                }
                insert.executeBatch();
                connection.commit();
            } catch (SQLException failure) {
                rollbackQuietly(connection);
                throw failure;
            }
        } catch (SQLException | RuntimeException e) {
            throw translateFailure(e);
        }
    }

    @Override
    public List<RetrievedChunk> search(KnowledgeBaseEntity kb, String queryText, int limit) {
        String sql = "SELECT doc_id, chunk_id, content, file_name, chunk_index, chunk_count, section_path, "
                + SCORE_EXPRESSION
                + " AS score FROM "
                + tableName
                + " WHERE kb_id = ? AND "
                + SCORE_EXPRESSION
                + " ORDER BY score DESC LIMIT ?";
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, kb.getId());
            statement.setString(index++, queryText);
            statement.setString(index++, queryText);
            statement.setInt(index, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<RetrievedChunk> chunks = new ArrayList<>();
                while (resultSet.next()) {
                    chunks.add(new RetrievedChunk(
                            resultSet.getString("content"),
                            resultSet.getDouble("score"),
                            kb.getId(),
                            resultSet.getString("doc_id"),
                            resultSet.getString("file_name"),
                            resultSet.getString("chunk_id"),
                            getNullableInt(resultSet, "chunk_index"),
                            getNullableInt(resultSet, "chunk_count"),
                            resultSet.getString("section_path"),
                            SCORE_TYPE));
                }
                return chunks;
            }
        } catch (SQLException | RuntimeException e) {
            throw translateFailure(e);
        }
    }

    @Override
    public void deleteDocument(KnowledgeBaseEntity kb, String docId) {
        String sql = "DELETE FROM " + tableName + " WHERE kb_id = ? AND doc_id = ?";
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, kb.getId());
            statement.setString(2, docId);
            statement.executeUpdate();
        } catch (SQLException | RuntimeException e) {
            throw translateFailure(e);
        }
    }

    @Override
    public boolean exists(KnowledgeBaseEntity kb) {
        String sql = "SELECT 1 FROM " + tableName + " WHERE kb_id = ? LIMIT 1";
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, kb.getId());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException | RuntimeException e) {
            throw translateFailure(e);
        }
    }

    /** 无连接池或句柄缓存，无需释放。 */
    @Override
    public void evict(KnowledgeBaseEntity kb) {
        // 空实现：JDBC 连接按操作短生命周期创建。
    }

    @Override
    public void deleteKnowledgeBase(KnowledgeBaseEntity kb) {
        String sql = "DELETE FROM " + tableName + " WHERE kb_id = ?";
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, kb.getId());
            statement.executeUpdate();
        } catch (SQLException | RuntimeException e) {
            throw translateFailure(e);
        }
    }

    private Connection openConnection() throws SQLException {
        Properties properties = new Properties();
        if (username != null) {
            properties.setProperty("user", username);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        return DriverManager.getConnection(jdbcUrl, properties);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            // 回滚失败时保留原始异常，不掩盖写入失败的真实原因。
        }
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value != null) {
            statement.setString(index, value);
        } else {
            statement.setNull(index, java.sql.Types.VARCHAR);
        }
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value != null) {
            statement.setInt(index, value);
        } else {
            statement.setNull(index, java.sql.Types.INTEGER);
        }
    }

    private static Integer getNullableInt(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    static AiBusinessException translateFailure(Throwable failure) {
        if (failure instanceof AiBusinessException aiBusinessException) {
            return aiBusinessException;
        }
        return new AiBusinessException(AiErrorCode.KB_RETRIEVAL_FAILED, failure);
    }

    private static void validateIdentifier(String identifier) {
        if (identifier == null
                || identifier.length() > MAX_IDENTIFIER_LENGTH
                || !IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new AiBusinessException(AiErrorCode.CONFIGURATION_ERROR, "非法的 OceanBase 全文索引表名: " + identifier);
        }
    }
}
