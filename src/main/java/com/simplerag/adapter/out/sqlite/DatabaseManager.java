package com.simplerag.adapter.out.sqlite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseManager {
    private final Path databasePath;

    public DatabaseManager() {
        this(Path.of(System.getProperty("user.home"), ".simplerag", "simplerag.db"));
    }

    public DatabaseManager(Path databasePath) {
        this.databasePath = databasePath.toAbsolutePath().normalize();
        initialize();
    }

    public Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private void initialize() {
        try {
            Files.createDirectories(databasePath.getParent());
            try (Connection connection = connect()) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS knowledge_base (
                          id TEXT PRIMARY KEY,
                          name TEXT NOT NULL,
                          description TEXT NOT NULL DEFAULT '',
                          created_at INTEGER NOT NULL,
                          updated_at INTEGER NOT NULL
                        )
                        """);
                    statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS knowledge_source (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          knowledge_base_id TEXT NOT NULL,
                          path TEXT NOT NULL,
                          UNIQUE(knowledge_base_id, path),
                          FOREIGN KEY(knowledge_base_id) REFERENCES knowledge_base(id) ON DELETE CASCADE
                        )
                        """);
                    statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS app_setting (
                          setting_key TEXT PRIMARY KEY,
                          setting_value TEXT NOT NULL
                        )
                        """);
                    statement.executeUpdate("CREATE TABLE IF NOT EXISTS schema_version(version INTEGER NOT NULL)");
                    try (var rows = statement.executeQuery("SELECT COUNT(*) FROM schema_version")) {
                        if (rows.next() && rows.getInt(1) == 0) {
                            statement.executeUpdate("INSERT INTO schema_version(version) VALUES(1)");
                        }
                    }
                    int version;
                    try (var rows = statement.executeQuery("SELECT version FROM schema_version")) {
                        version = rows.next() ? rows.getInt(1) : 1;
                    }
                    if (version < 2) {
                        statement.executeUpdate("ALTER TABLE knowledge_base ADD COLUMN source_revision INTEGER NOT NULL DEFAULT 0");
                        statement.executeUpdate("ALTER TABLE knowledge_base ADD COLUMN published_index_revision INTEGER");
                        statement.executeUpdate("ALTER TABLE knowledge_base ADD COLUMN index_status TEXT NOT NULL DEFAULT 'EMPTY'");
                        statement.executeUpdate("ALTER TABLE knowledge_base ADD COLUMN last_index_error TEXT NOT NULL DEFAULT ''");
                        statement.executeUpdate("""
                                CREATE TABLE knowledge_index (
                                  knowledge_base_id TEXT NOT NULL,
                                  revision INTEGER NOT NULL,
                                  file_name TEXT NOT NULL,
                                  source_set_hash TEXT NOT NULL,
                                  embedding_model_signature TEXT NOT NULL,
                                  embedding_dimension INTEGER NOT NULL,
                                  chunking_version INTEGER NOT NULL,
                                  index_format_version INTEGER NOT NULL,
                                  built_at INTEGER NOT NULL,
                                  PRIMARY KEY (knowledge_base_id, revision),
                                  FOREIGN KEY(knowledge_base_id) REFERENCES knowledge_base(id) ON DELETE CASCADE
                                )
                                """);
                        statement.executeUpdate("UPDATE schema_version SET version = 2");
                        version = 2;
                    }
                    if (version < 3) {
                        statement.executeUpdate("ALTER TABLE knowledge_base ADD COLUMN last_verified_source_hash TEXT NOT NULL DEFAULT ''");
                        statement.executeUpdate("ALTER TABLE knowledge_base ADD COLUMN last_verified_at INTEGER");
                        statement.executeUpdate("ALTER TABLE knowledge_base ADD COLUMN freshness_reason TEXT NOT NULL DEFAULT ''");
                        statement.executeUpdate("UPDATE schema_version SET version = 3");
                        version = 3;
                    }
                    if (version < 4) {
                        // Conversations used to live in a map that died with the process. They cascade
                        // from the knowledge base because an answer only means anything next to the
                        // files it was grounded in.
                        statement.executeUpdate("""
                                CREATE TABLE conversation (
                                  id TEXT PRIMARY KEY,
                                  knowledge_base_id TEXT NOT NULL,
                                  title TEXT NOT NULL DEFAULT '',
                                  created_at INTEGER NOT NULL,
                                  updated_at INTEGER NOT NULL,
                                  FOREIGN KEY(knowledge_base_id) REFERENCES knowledge_base(id) ON DELETE CASCADE
                                )
                                """);
                        statement.executeUpdate(
                                "CREATE INDEX idx_conversation_base ON conversation(knowledge_base_id, updated_at DESC)");
                        statement.executeUpdate("""
                                CREATE TABLE conversation_message (
                                  id TEXT PRIMARY KEY,
                                  conversation_id TEXT NOT NULL,
                                  seq INTEGER NOT NULL,
                                  role TEXT NOT NULL,
                                  content TEXT NOT NULL,
                                  source_revision INTEGER NOT NULL,
                                  created_at INTEGER NOT NULL,
                                  FOREIGN KEY(conversation_id) REFERENCES conversation(id) ON DELETE CASCADE
                                )
                                """);
                        statement.executeUpdate(
                                "CREATE INDEX idx_conversation_message_seq ON conversation_message(conversation_id, seq)");
                        statement.executeUpdate("UPDATE schema_version SET version = 4");
                        version = 4;
                    }
                    if (version < 5) {
                        // Older builds did not enforce sequence uniqueness. Re-number deterministically
                        // before replacing the ordinary index so an existing duplicate cannot make the
                        // migration fail at startup.
                        statement.executeUpdate("""
                                WITH ordered AS (
                                  SELECT id, ROW_NUMBER() OVER (
                                    PARTITION BY conversation_id
                                    ORDER BY seq, created_at, id
                                  ) - 1 AS new_seq
                                  FROM conversation_message
                                )
                                UPDATE conversation_message
                                SET seq = (SELECT new_seq FROM ordered
                                           WHERE ordered.id = conversation_message.id)
                                """);
                        statement.executeUpdate("DROP INDEX idx_conversation_message_seq");
                        statement.executeUpdate(
                                "CREATE UNIQUE INDEX idx_conversation_message_seq "
                                        + "ON conversation_message(conversation_id, seq)");
                        statement.executeUpdate("UPDATE schema_version SET version = 5");
                    }
                    connection.commit();
                } catch (Exception failure) {
                    connection.rollback();
                    throw failure;
                }
            }
        } catch (Exception failure) {
            throw new DataAccessException("无法初始化本地数据库", failure);
        }
    }

    public Path path() {
        return databasePath;
    }
}
