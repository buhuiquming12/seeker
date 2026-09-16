package com.simplerag.adapter.out.sqlite;

import com.simplerag.application.conversation.ChatMessage;
import com.simplerag.application.conversation.StoredMessage;
import com.simplerag.application.dto.ConversationView;
import com.simplerag.application.port.out.ConversationRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Conversations and their turns in the application database. */
public final class SqliteConversationRepository implements ConversationRepository {
    private final DatabaseManager database;
    private final SqliteTransactionManager transactions;

    public SqliteConversationRepository(DatabaseManager database) {
        this.database = database;
        this.transactions = new SqliteTransactionManager(database);
    }

    @Override
    public List<ConversationView> conversations(String knowledgeBaseId) {
        String sql = """
                SELECT c.id, c.knowledge_base_id, c.title, c.created_at, c.updated_at,
                       (SELECT COUNT(*) FROM conversation_message m WHERE m.conversation_id = c.id) AS message_count
                FROM conversation c
                WHERE c.knowledge_base_id = ?
                ORDER BY c.updated_at DESC, c.created_at DESC
                """;
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, knowledgeBaseId);
            try (ResultSet rows = statement.executeQuery()) {
                List<ConversationView> result = new ArrayList<>();
                while (rows.next()) result.add(readConversation(rows));
                return result;
            }
        } catch (SQLException failure) {
            throw new DataAccessException("无法读取对话列表", failure);
        }
    }

    @Override
    public Optional<ConversationView> conversation(String conversationId) {
        String sql = """
                SELECT c.id, c.knowledge_base_id, c.title, c.created_at, c.updated_at,
                       (SELECT COUNT(*) FROM conversation_message m WHERE m.conversation_id = c.id) AS message_count
                FROM conversation c WHERE c.id = ?
                """;
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readConversation(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new DataAccessException("无法读取对话", failure);
        }
    }

    @Override
    public List<StoredMessage> messages(String conversationId) {
        String sql = """
                SELECT id, role, content, source_revision, created_at
                FROM conversation_message WHERE conversation_id = ? ORDER BY seq
                """;
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId);
            try (ResultSet rows = statement.executeQuery()) {
                List<StoredMessage> result = new ArrayList<>();
                while (rows.next()) {
                    ChatMessage message = new ChatMessage(rows.getString("id"),
                            ChatMessage.Role.valueOf(rows.getString("role")), rows.getString("content"),
                            Instant.ofEpochMilli(rows.getLong("created_at")));
                    result.add(new StoredMessage(message, rows.getLong("source_revision")));
                }
                return result;
            }
        } catch (SQLException failure) {
            throw new DataAccessException("无法读取对话内容", failure);
        }
    }

    @Override
    public void createConversation(ConversationView conversation) {
        String sql = "INSERT INTO conversation(id, knowledge_base_id, title, created_at, updated_at) "
                + "VALUES(?, ?, ?, ?, ?)";
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversation.id());
            statement.setString(2, conversation.knowledgeBaseId());
            statement.setString(3, conversation.title() == null ? "" : conversation.title());
            statement.setLong(4, conversation.createdAt());
            statement.setLong(5, conversation.updatedAt());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new DataAccessException("无法新建对话", failure);
        }
    }

    @Override
    public void renameConversation(String conversationId, String title) {
        String sql = "UPDATE conversation SET title = ? WHERE id = ?";
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, title == null ? "" : title);
            statement.setString(2, conversationId);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new DataAccessException("无法重命名对话", failure);
        }
    }

    /**
     * One turn. The sequence number is read and the conversation's own timestamp bumped inside the
     * same transaction, so two turns cannot take the same position and the session list cannot show
     * an order the transcript disagrees with.
     */
    @Override
    public void appendMessage(String conversationId, ChatMessage message, long sourceRevision) {
        transactions.execute("无法保存对话内容", connection -> {
            long seq = nextSequence(conversationId, connection);
            insertMessage(connection, conversationId, message, sourceRevision, seq);
            touchConversation(connection, conversationId);
            return null;
        });
    }

    @Override
    public void appendTurn(String conversationId, ChatMessage user, ChatMessage assistant,
                           long sourceRevision) {
        transactions.execute("无法保存完整对话回合", connection -> {
            long seq = nextSequence(conversationId, connection);
            insertMessage(connection, conversationId, user, sourceRevision, seq);
            insertMessage(connection, conversationId, assistant, sourceRevision, seq + 1);
            touchConversation(connection, conversationId);
            return null;
        });
    }

    @Override
    public void deleteConversation(String conversationId) {
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM conversation WHERE id = ?")) {
            statement.setString(1, conversationId);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new DataAccessException("无法删除对话", failure);
        }
    }

    private static long nextSequence(String conversationId, Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(seq), -1) + 1 FROM conversation_message WHERE conversation_id = ?")) {
            statement.setString(1, conversationId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    private static void insertMessage(Connection connection, String conversationId, ChatMessage message,
                                      long sourceRevision, long seq) throws SQLException {
        String insert = """
                INSERT INTO conversation_message(id, conversation_id, seq, role, content,
                                                 source_revision, created_at)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setString(1, message.id());
            statement.setString(2, conversationId);
            statement.setLong(3, seq);
            statement.setString(4, message.role().name());
            statement.setString(5, message.content());
            statement.setLong(6, sourceRevision);
            statement.setLong(7, message.createdAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static void touchConversation(Connection connection, String conversationId) throws SQLException {
        try (PreparedStatement touch = connection.prepareStatement(
                "UPDATE conversation SET updated_at = ? WHERE id = ?")) {
            touch.setLong(1, System.currentTimeMillis());
            touch.setString(2, conversationId);
            touch.executeUpdate();
        }
    }

    private static ConversationView readConversation(ResultSet rows) throws SQLException {
        return new ConversationView(rows.getString("id"), rows.getString("knowledge_base_id"),
                rows.getString("title"), rows.getInt("message_count"), rows.getLong("created_at"),
                rows.getLong("updated_at"));
    }
}
