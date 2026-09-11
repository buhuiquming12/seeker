package com.simplerag.adapter.out.sqlite;

import com.simplerag.application.conversation.ChatMessage;
import com.simplerag.application.conversation.StoredMessage;
import com.simplerag.application.dto.ConversationView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteConversationRepositoryTest {
    @TempDir Path temp;

    /** The point of the table: what was asked and answered is still there in the next process. */
    @Test
    void turnsComeBackInOrderWithTheRevisionTheyWereAnsweredUnder() {
        DatabaseManager database = new DatabaseManager(temp.resolve("app.db"));
        SqliteConversationRepository repository = new SqliteConversationRepository(database);
        String knowledgeBase = knowledgeBase(database, "笔记");
        ConversationView conversation = create(repository, knowledgeBase);

        repository.appendMessage(conversation.id(), ChatMessage.user("索引怎么构建？"), 7L);
        repository.appendMessage(conversation.id(), ChatMessage.assistant("分三步。"), 7L);
        // A rebuild moved the source revision on; the transcript keeps both halves.
        repository.appendMessage(conversation.id(), ChatMessage.user("发布是原子的吗？"), 8L);
        repository.appendMessage(conversation.id(), ChatMessage.assistant("是。"), 8L);

        List<StoredMessage> messages = new SqliteConversationRepository(database)
                .messages(conversation.id());
        assertEquals(4, messages.size());
        assertEquals("索引怎么构建？", messages.get(0).message().content());
        assertEquals(ChatMessage.Role.ASSISTANT, messages.get(3).message().role());
        assertEquals(List.of(7L, 7L, 8L, 8L), messages.stream().map(StoredMessage::sourceRevision).toList());
        assertTrue(messages.get(0).from(7L));
        assertFalse(messages.get(2).from(7L));
    }

    @Test
    void theListIsNewestFirstAndCountsTurnsAndNames() throws Exception {
        DatabaseManager database = new DatabaseManager(temp.resolve("app.db"));
        SqliteConversationRepository repository = new SqliteConversationRepository(database);
        String knowledgeBase = knowledgeBase(database, "笔记");

        ConversationView older = create(repository, knowledgeBase);
        repository.appendMessage(older.id(), ChatMessage.user("旧问题"), 1L);
        repository.renameConversation(older.id(), "旧问题");
        // updated_at has millisecond resolution, so two writes in the same tick would tie.
        Thread.sleep(5);
        ConversationView newer = create(repository, knowledgeBase);

        List<ConversationView> listed = repository.conversations(knowledgeBase);
        assertEquals(List.of(newer.id(), older.id()), listed.stream().map(ConversationView::id).toList());
        assertTrue(listed.get(0).empty());
        assertEquals("新对话", listed.get(0).label(), "an unnamed conversation still needs a label");
        assertEquals(1, listed.get(1).messageCount());
        assertEquals("旧问题", listed.get(1).title());
    }

    @Test
    void deletingAConversationTakesItsTurnsWithIt() {
        DatabaseManager database = new DatabaseManager(temp.resolve("app.db"));
        SqliteConversationRepository repository = new SqliteConversationRepository(database);
        String knowledgeBase = knowledgeBase(database, "笔记");
        ConversationView conversation = create(repository, knowledgeBase);
        repository.appendMessage(conversation.id(), ChatMessage.user("会被删掉"), 1L);

        repository.deleteConversation(conversation.id());

        assertTrue(repository.conversation(conversation.id()).isEmpty());
        assertEquals(0, countMessages(database));
    }

    /** An answer only means anything next to the files it was grounded in. */
    @Test
    void deletingTheKnowledgeBaseTakesItsConversationsWithIt() {
        DatabaseManager database = new DatabaseManager(temp.resolve("app.db"));
        AppRepository application = new AppRepository(database);
        SqliteConversationRepository repository = new SqliteConversationRepository(database);
        String knowledgeBase = knowledgeBase(database, "笔记");
        ConversationView conversation = create(repository, knowledgeBase);
        repository.appendMessage(conversation.id(), ChatMessage.user("会随知识库一起消失"), 1L);

        application.deleteKnowledgeBase(knowledgeBase);

        assertTrue(repository.conversations(knowledgeBase).isEmpty());
        assertEquals(0, countMessages(database));
    }

    private static ConversationView create(SqliteConversationRepository repository, String knowledgeBaseId) {
        long now = System.currentTimeMillis();
        ConversationView conversation = new ConversationView(UUID.randomUUID().toString(),
                knowledgeBaseId, "", 0, now, now);
        repository.createConversation(conversation);
        return conversation;
    }

    private static String knowledgeBase(DatabaseManager database, String name) {
        return new AppRepository(database).createKnowledgeBase(name, "").id();
    }

    private static int countMessages(DatabaseManager database) {
        try (var connection = database.connect();
             var rows = connection.createStatement()
                     .executeQuery("SELECT COUNT(*) FROM conversation_message")) {
            return rows.getInt(1);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
