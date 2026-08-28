package com.lambda.fusion.ai.rag.store;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.VDBStoreBase;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 使用 OceanBase MySQL 模式原生 VECTOR 类型的 AgentScope 向量存储。 */
@SuppressWarnings("removal")
public class OceanBaseVectorStore implements VDBStoreBase {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final int MAX_IDENTIFIER_LENGTH = 64;

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String tableName;
    private final int dimensions;

    public OceanBaseVectorStore(String jdbcUrl, String username, String password, String tableName, int dimensions)
            throws VectorStoreException {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("OceanBase JDBC URL cannot be blank");
        }
        validateIdentifier(tableName);
        if (dimensions <= 0) {
            throw new IllegalArgumentException("Vector dimensions must be positive");
        }
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.tableName = tableName;
        this.dimensions = dimensions;
        ensureTable();
    }

    @Override
    public Mono<Void> add(List<Document> documents) {
        if (documents == null) {
            return Mono.error(new IllegalArgumentException("Documents cannot be null"));
        }
        if (documents.isEmpty()) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> {
                    addDocuments(documents);
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public Mono<List<Document>> search(SearchDocumentDto request) {
        if (request == null || request.getQueryEmbedding() == null) {
            return Mono.error(new IllegalArgumentException("Search request and query embedding cannot be null"));
        }
        return Mono.fromCallable(() -> searchDocuments(request)).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Boolean> delete(String docId) {
        if (docId == null || docId.isBlank()) {
            return Mono.error(new IllegalArgumentException("Document ID cannot be blank"));
        }
        return Mono.fromCallable(() -> deleteDocument(docId)).subscribeOn(Schedulers.boundedElastic());
    }

    private void ensureTable() throws VectorStoreException {
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + "id VARCHAR(64) PRIMARY KEY, "
                + "embedding VECTOR(" + dimensions + ") NOT NULL, "
                + "doc_id VARCHAR(256), "
                + "chunk_id VARCHAR(256), "
                + "content LONGTEXT, "
                + "payload JSON, "
                + "KEY idx_" + tableName + "_doc_id (doc_id), "
                + "VECTOR INDEX idx_" + tableName
                + "_embedding (embedding) WITH (distance=COSINE, type=HNSW)"
                + ")";
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new VectorStoreException("Failed to initialize OceanBase vector table: " + tableName, e);
        }
    }

    private void addDocuments(List<Document> documents) throws VectorStoreException {
        String sql = "INSERT INTO " + tableName
                + " (id, embedding, doc_id, chunk_id, content, payload) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE embedding=VALUES(embedding), doc_id=VALUES(doc_id), "
                + "chunk_id=VALUES(chunk_id), content=VALUES(content), payload=VALUES(payload)";
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Document document : documents) {
                validateDocument(document);
                DocumentMetadata metadata = document.getMetadata();
                statement.setString(1, stableId(metadata));
                statement.setString(2, vectorLiteral(document.getEmbedding()));
                statement.setString(3, metadata.getDocId());
                statement.setString(4, metadata.getChunkId());
                statement.setString(5, metadata.getContentText());
                statement.setString(6, JsonUtils.getJsonCodec().toJson(metadata.getPayload()));
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException | RuntimeException e) {
            throw new VectorStoreException("Failed to add documents to OceanBase", e);
        }
    }

    private List<Document> searchDocuments(SearchDocumentDto request) throws VectorStoreException {
        validateDimensions(request.getQueryEmbedding());
        String score = "1 - cosine_distance(embedding, ?)";
        StringBuilder sql = new StringBuilder("SELECT doc_id, chunk_id, content, payload, ")
                .append(score)
                .append(" AS score FROM ")
                .append(tableName);
        if (request.getScoreThreshold() != null) {
            sql.append(" WHERE ").append(score).append(" >= ?");
        }
        sql.append(" ORDER BY cosine_distance(embedding, ?) APPROXIMATE LIMIT ?");
        String vector = vectorLiteral(request.getQueryEmbedding());
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setString(index++, vector);
            if (request.getScoreThreshold() != null) {
                statement.setString(index++, vector);
                statement.setDouble(index++, request.getScoreThreshold());
            }
            statement.setString(index++, vector);
            statement.setInt(index, request.getLimit());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Document> results = new ArrayList<>();
                while (resultSet.next()) {
                    Map<String, Object> payload = decodePayload(resultSet.getString("payload"));
                    DocumentMetadata metadata = DocumentMetadata.builder()
                            .docId(resultSet.getString("doc_id"))
                            .chunkId(resultSet.getString("chunk_id"))
                            .content(TextBlock.builder()
                                    .text(resultSet.getString("content"))
                                    .build())
                            .payload(payload)
                            .build();
                    Document document = new Document(metadata);
                    document.setScore(resultSet.getDouble("score"));
                    results.add(document);
                }
                return results;
            }
        } catch (SQLException | RuntimeException e) {
            throw new VectorStoreException("Failed to search documents in OceanBase", e);
        }
    }

    private boolean deleteDocument(String docId) throws VectorStoreException {
        String sql = "DELETE FROM " + tableName + " WHERE doc_id = ?";
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, docId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new VectorStoreException("Failed to delete document from OceanBase", e);
        }
    }

    private void validateDocument(Document document) {
        if (document == null || document.getMetadata() == null || document.getEmbedding() == null) {
            throw new IllegalArgumentException("Document, metadata and embedding cannot be null");
        }
        validateDimensions(document.getEmbedding());
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

    private void validateDimensions(double[] embedding) {
        if (embedding.length != dimensions) {
            throw new IllegalArgumentException(
                    "Embedding dimension mismatch: expected " + dimensions + ", got " + embedding.length);
        }
    }

    private static void validateIdentifier(String identifier) {
        if (identifier == null
                || identifier.length() > MAX_IDENTIFIER_LENGTH
                || !IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Invalid OceanBase table name: " + identifier);
        }
    }

    private static String stableId(DocumentMetadata metadata) {
        String source = metadata.getDocId() + '\u0000' + metadata.getChunkId();
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String vectorLiteral(double[] vector) {
        StringBuilder literal = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(vector[i]);
        }
        return literal.append(']').toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decodePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        return JsonUtils.getJsonCodec().fromJson(payload, Map.class);
    }
}
