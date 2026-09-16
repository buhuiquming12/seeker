package com.simplerag.adapter.out.sqlite;

import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.IndexStatus;
import com.simplerag.search.IndexManifest;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class AppRepository implements com.simplerag.application.port.out.KnowledgeBaseRepository,
        com.simplerag.application.port.out.SettingsRepository {
    private final DatabaseManager database;
    private final SqliteTransactionManager transactions;

    public AppRepository(DatabaseManager database) {
        this.database = database;
        this.transactions = new SqliteTransactionManager(database);
    }

    public List<KnowledgeBase> listKnowledgeBases() {
        String sql = "SELECT * FROM knowledge_base ORDER BY updated_at DESC";
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            List<KnowledgeBase> result = new ArrayList<>();
            while (rows.next()) result.add(readKnowledgeBase(rows));
            return result;
        } catch (SQLException failure) {
            throw new DataAccessException("无法读取知识库", failure);
        }
    }

    public Optional<KnowledgeBase> findKnowledgeBase(String id) {
        String sql = "SELECT * FROM knowledge_base WHERE id = ?";
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readKnowledgeBase(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new DataAccessException("无法读取知识库", failure);
        }
    }

    public KnowledgeBase createKnowledgeBase(String name, String description) {
        String cleaned = requireName(name);
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO knowledge_base(id, name, description, created_at, updated_at) VALUES(?, ?, ?, ?, ?)";
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, cleaned);
            statement.setString(3, description == null ? "" : description.strip());
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
            return findKnowledgeBase(id).orElseThrow();
        } catch (SQLException failure) {
            throw new DataAccessException("无法创建知识库", failure);
        }
    }

    public KnowledgeBase updateKnowledgeBase(String id, String name, String description) {
        String cleaned = requireName(name);
        long now = System.currentTimeMillis();
        String sql = "UPDATE knowledge_base SET name = ?, description = ?, updated_at = ? WHERE id = ?";
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cleaned);
            statement.setString(2, description == null ? "" : description.strip());
            statement.setLong(3, now);
            statement.setString(4, id);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("知识库不存在");
            return findKnowledgeBase(id).orElseThrow();
        } catch (SQLException failure) {
            throw new DataAccessException("无法更新知识库", failure);
        }
    }

    public void deleteKnowledgeBase(String id) {
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM knowledge_base WHERE id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new DataAccessException("无法删除知识库", failure);
        }
    }

    public List<Path> listSources(String knowledgeBaseId) {
        String sql = "SELECT path FROM knowledge_source WHERE knowledge_base_id = ? ORDER BY id";
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, knowledgeBaseId);
            try (ResultSet rows = statement.executeQuery()) {
                List<Path> result = new ArrayList<>();
                while (rows.next()) result.add(Path.of(rows.getString(1)));
                return result;
            }
        } catch (SQLException failure) {
            throw new DataAccessException("无法读取数据源", failure);
        }
    }

    public void addSource(String knowledgeBaseId, Path path) {
        String sql = "INSERT OR IGNORE INTO knowledge_source(knowledge_base_id, path) VALUES(?, ?)";
        transactions.execute("无法添加数据源", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, knowledgeBaseId);
                statement.setString(2, path.toAbsolutePath().normalize().toString());
                if (statement.executeUpdate() == 1) markSourcesChanged(knowledgeBaseId, connection);
            }
            return null;
        });
    }

    public void removeSource(String knowledgeBaseId, Path path) {
        String sql = "DELETE FROM knowledge_source WHERE knowledge_base_id = ? AND path = ?";
        transactions.execute("无法移除数据源", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, knowledgeBaseId);
                statement.setString(2, path.toAbsolutePath().normalize().toString());
                if (statement.executeUpdate() == 1) markSourcesChanged(knowledgeBaseId, connection);
            }
            return null;
        });
    }

    public Optional<String> getSetting(String key) {
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT setting_value FROM app_setting WHERE setting_key = ?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new DataAccessException("无法读取应用设置", failure);
        }
    }

    public void putSetting(String key, String value) {
        String sql = "INSERT INTO app_setting(setting_key, setting_value) VALUES(?, ?) "
                + "ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value";
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, value == null ? "" : value);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new DataAccessException("无法保存应用设置", failure);
        }
    }

    private static KnowledgeBase readKnowledgeBase(ResultSet rows) throws SQLException {
        Object published = rows.getObject("published_index_revision");
        return new KnowledgeBase(rows.getString("id"), rows.getString("name"), rows.getString("description"),
                rows.getLong("created_at"), rows.getLong("updated_at"), rows.getLong("source_revision"),
                published == null ? null : ((Number) published).longValue(),
                IndexStatus.valueOf(rows.getString("index_status")), rows.getString("last_index_error"),
                rows.getString("last_verified_source_hash"), nullableLong(rows, "last_verified_at"),
                rows.getString("freshness_reason"));
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        Object value = rows.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private static String requireName(String name) {
        String cleaned = name == null ? "" : name.strip();
        if (cleaned.isEmpty()) throw new IllegalArgumentException("知识库名称不能为空");
        if (cleaned.length() > 60) throw new IllegalArgumentException("知识库名称不能超过 60 个字符");
        return cleaned;
    }

    private static void markSourcesChanged(String knowledgeBaseId, Connection connection) throws SQLException {
        try (PreparedStatement touch = connection.prepareStatement("""
                UPDATE knowledge_base
                SET updated_at = ?, source_revision = source_revision + 1,
                    index_status = 'DIRTY', last_index_error = '',
                    freshness_reason = '数据源配置已变化，需要重建索引'
                WHERE id = ?
                """)) {
            touch.setLong(1, System.currentTimeMillis());
            touch.setString(2, knowledgeBaseId);
            touch.executeUpdate();
        }
    }

    public boolean beginIndexBuild(String knowledgeBaseId, long revision) {
        return beginIndexBuildRevision(knowledgeBaseId, revision) != null;
    }

    @Override
    public Long beginIndexBuildRevision(String knowledgeBaseId, long expectedRevision) {
        String update = """
                UPDATE knowledge_base
                SET source_revision = CASE
                      WHEN published_index_revision = source_revision THEN source_revision + 1
                      ELSE source_revision END,
                    index_status = 'BUILDING', last_index_error = '', updated_at = ?
                WHERE id = ? AND source_revision = ?
                  AND index_status IN ('EMPTY', 'READY', 'DIRTY', 'FAILED', 'INCOMPATIBLE')
                """;
        String select = "SELECT source_revision FROM knowledge_base WHERE id = ?";
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, knowledgeBaseId);
                statement.setLong(3, expectedRevision);
                if (statement.executeUpdate() != 1) {
                    connection.rollback();
                    return null;
                }
            }
            long revision;
            try (PreparedStatement statement = connection.prepareStatement(select)) {
                statement.setString(1, knowledgeBaseId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        connection.rollback();
                        return null;
                    }
                    revision = rows.getLong(1);
                }
            }
            connection.commit();
            return revision;
        } catch (SQLException failure) {
            throw new DataAccessException("无法开始索引构建", failure);
        }
    }

    public void markIndexBuildFailed(String knowledgeBaseId, long revision, String error) {
        String sql = """
                UPDATE knowledge_base
                SET index_status = 'FAILED', last_index_error = ?
                WHERE id = ? AND source_revision = ? AND index_status = 'BUILDING'
                """;
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, error == null ? "" : error);
            statement.setString(2, knowledgeBaseId);
            statement.setLong(3, revision);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new DataAccessException("无法记录索引失败状态", failure);
        }
    }

    public void markIndexIncompatible(String knowledgeBaseId, String error) {
        updateStatus(knowledgeBaseId, IndexStatus.INCOMPATIBLE, error);
    }

    public void markIndexDirty(String knowledgeBaseId, String error) {
        updateStatus(knowledgeBaseId, IndexStatus.DIRTY, error);
    }

    @Override
    public boolean markIndexBuildDiscarded(String knowledgeBaseId, long revision, String error) {
        String sql = """
                UPDATE knowledge_base SET index_status = 'DIRTY', last_index_error = ?
                WHERE id = ? AND source_revision = ? AND index_status = 'BUILDING'
                """;
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, error == null ? "" : error);
            statement.setString(2, knowledgeBaseId);
            statement.setLong(3, revision);
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new DataAccessException("无法丢弃旧索引构建", failure);
        }
    }

    public boolean markIndexDirtyIfCurrent(String knowledgeBaseId, long sourceRevision, String reason,
                                           String observedSourceHash, Long verifiedAt) {
        String sql = """
                UPDATE knowledge_base
                SET source_revision = source_revision + 1,
                    index_status = 'DIRTY', last_index_error = ?, freshness_reason = ?,
                    last_verified_source_hash = COALESCE(?, last_verified_source_hash),
                    last_verified_at = COALESCE(?, last_verified_at), updated_at = ?
                WHERE id = ? AND source_revision = ?
                  AND index_status IN ('READY', 'BUILDING', 'FAILED', 'DIRTY')
                """;
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            String message = reason == null ? "源文件状态无法证明，需要重建索引" : reason;
            statement.setString(1, message);
            statement.setString(2, message);
            statement.setObject(3, observedSourceHash == null || observedSourceHash.isBlank() ? null : observedSourceHash);
            statement.setObject(4, verifiedAt);
            statement.setLong(5, System.currentTimeMillis());
            statement.setString(6, knowledgeBaseId);
            statement.setLong(7, sourceRevision);
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new DataAccessException("无法标记源文件变化", failure);
        }
    }

    public void recordSourceVerification(String knowledgeBaseId, long sourceRevision, String sourceHash,
                                         long verifiedAt) {
        String sql = """
                UPDATE knowledge_base
                SET last_verified_source_hash = ?, last_verified_at = ?,
                    freshness_reason = CASE WHEN index_status = 'READY' THEN '' ELSE freshness_reason END
                WHERE id = ? AND source_revision = ?
                """;
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sourceHash);
            statement.setLong(2, verifiedAt);
            statement.setString(3, knowledgeBaseId);
            statement.setLong(4, sourceRevision);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new DataAccessException("无法记录源文件核对结果", failure);
        }
    }

    public boolean publishIndex(IndexManifest manifest, String fileName) {
        String insert = """
                INSERT INTO knowledge_index(knowledge_base_id, revision, file_name, source_set_hash,
                  embedding_model_signature, embedding_dimension, chunking_version, index_format_version, built_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(knowledge_base_id, revision) DO UPDATE SET
                  file_name=excluded.file_name, source_set_hash=excluded.source_set_hash,
                  embedding_model_signature=excluded.embedding_model_signature,
                  embedding_dimension=excluded.embedding_dimension, chunking_version=excluded.chunking_version,
                  index_format_version=excluded.index_format_version, built_at=excluded.built_at
                """;
        String publish = """
                UPDATE knowledge_base SET published_index_revision = ?, index_status = 'READY',
                  last_index_error = '', freshness_reason = '', last_verified_source_hash = ?,
                  last_verified_at = ?, updated_at = ?
                WHERE id = ? AND source_revision = ? AND index_status = 'BUILDING'
                """;
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, manifest.knowledgeBaseId());
                statement.setLong(2, manifest.sourceRevision());
                statement.setString(3, fileName);
                statement.setString(4, manifest.sourceSetHash());
                statement.setString(5, manifest.embeddingModelSignature());
                statement.setInt(6, manifest.embeddingDimension());
                statement.setInt(7, manifest.chunkingVersion());
                statement.setInt(8, manifest.indexFormatVersion());
                statement.setLong(9, manifest.builtAt());
                statement.executeUpdate();
            }
            int changed;
            try (PreparedStatement statement = connection.prepareStatement(publish)) {
                statement.setLong(1, manifest.sourceRevision());
                statement.setString(2, manifest.sourceSetHash());
                statement.setLong(3, System.currentTimeMillis());
                statement.setLong(4, System.currentTimeMillis());
                statement.setString(5, manifest.knowledgeBaseId());
                statement.setLong(6, manifest.sourceRevision());
                changed = statement.executeUpdate();
            }
            if (changed != 1) {
                connection.rollback();
                return false;
            }
            connection.commit();
            return true;
        } catch (SQLException failure) {
            throw new DataAccessException("无法发布索引", failure);
        }
    }

    public Optional<String> findIndexFile(String knowledgeBaseId, long revision) {
        String sql = "SELECT file_name FROM knowledge_index WHERE knowledge_base_id = ? AND revision = ?";
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, knowledgeBaseId);
            statement.setLong(2, revision);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new DataAccessException("无法读取索引发布记录", failure);
        }
    }

    private boolean updateStatusConditionally(String id, long revision, IndexStatus status, String error) {
        String sql = "UPDATE knowledge_base SET index_status = ?, last_index_error = ? WHERE id = ? AND source_revision = ?";
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setString(2, error == null ? "" : error);
            statement.setString(3, id);
            statement.setLong(4, revision);
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new DataAccessException("无法更新索引状态", failure);
        }
    }

    private void updateStatus(String id, IndexStatus status, String error) {
        String sql = "UPDATE knowledge_base SET index_status = ?, last_index_error = ? WHERE id = ?";
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setString(2, error == null ? "" : error);
            statement.setString(3, id);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new DataAccessException("无法更新索引状态", failure);
        }
    }

    @Override
    public Path databasePath() {
        return database.path();
    }
}
