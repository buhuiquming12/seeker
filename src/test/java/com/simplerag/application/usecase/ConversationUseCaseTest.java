package com.simplerag.application.usecase;

import com.simplerag.application.conversation.ChatMessage;
import com.simplerag.application.conversation.StoredMessage;
import com.simplerag.application.dto.ConversationView;
import com.simplerag.application.port.out.ConversationRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationUseCaseTest {
    @Test
    void aKnowledgeBaseWithNoHistoryGetsOneConversationRatherThanOnePerLook() {
        ConversationUseCase conversations = new ConversationUseCase(new InMemoryConversations());

        ConversationView first = conversations.activeConversation("kb-1");
        ConversationView second = conversations.activeConversation("kb-1");

        assertEquals(first.id(), second.id());
        assertEquals(1, conversations.conversations("kb-1").size());
    }

    /** Nothing has been asked yet, so a second 新对话 would only add an identical empty row. */
    @Test
    void startingANewConversationReusesOneThatIsStillEmpty() {
        ConversationUseCase conversations = new ConversationUseCase(new InMemoryConversations());
        ConversationView first = conversations.activeConversation("kb-1");

        assertEquals(first.id(), conversations.startConversation("kb-1").id());

        conversations.recordTurn(first.id(), "索引怎么构建？", "分三步。", 4L);
        ConversationView second = conversations.startConversation("kb-1");
        assertNotEquals(first.id(), second.id());
        assertEquals(2, conversations.conversations("kb-1").size());
    }

    @Test
    void aConversationIsNamedAfterItsFirstQuestionAndKeepsThatName() {
        ConversationUseCase conversations = new ConversationUseCase(new InMemoryConversations());
        ConversationView conversation = conversations.activeConversation("kb-1");

        conversations.recordTurn(conversation.id(), "索引怎么构建？", "分三步。", 4L);
        conversations.recordTurn(conversation.id(), "那发布呢？", "先写临时文件。", 4L);

        ConversationView saved = conversations.activeConversation("kb-1");
        assertEquals("索引怎么构建？", saved.title());
        assertEquals(4, saved.messageCount());
    }

    @Test
    void aLongFirstQuestionIsElidedIntoAOneLineTitle() {
        String question = "发布索引的时候，为什么要先写临时文件再原子移动，而不是直接就地覆盖旧的索引文件？";
        String title = ConversationView.titleFrom(question);

        assertEquals(ConversationView.TITLE_LIMIT + 1, title.length());
        assertTrue(title.endsWith("…"));
        assertTrue(question.startsWith(title.substring(0, ConversationView.TITLE_LIMIT)));
        assertEquals("换行 也压成 一行", ConversationView.titleFrom("  换行\n 也压成\t一行  "));
    }

    /** The transcript keeps every turn; only the revision on each one says what the model may see. */
    @Test
    void theTranscriptSpansRevisionsAndSaysWhichOneEachTurnCameFrom() {
        ConversationUseCase conversations = new ConversationUseCase(new InMemoryConversations());
        ConversationView conversation = conversations.activeConversation("kb-1");

        conversations.recordTurn(conversation.id(), "重建前的问题", "重建前的回答", 4L);
        conversations.recordTurn(conversation.id(), "重建后的问题", "重建后的回答", 5L);

        List<StoredMessage> transcript = conversations.transcript(conversation.id());
        assertEquals(4, transcript.size());
        assertEquals(2, transcript.stream().filter(message -> message.from(5L)).count());
    }

    @Test
    void deletingTheActiveConversationHandsBackTheNextOne() {
        ConversationUseCase conversations = new ConversationUseCase(new InMemoryConversations());
        ConversationView first = conversations.activeConversation("kb-1");
        conversations.recordTurn(first.id(), "第一段", "回答", 1L);
        ConversationView second = conversations.startConversation("kb-1");
        conversations.recordTurn(second.id(), "第二段", "回答", 1L);

        ConversationView remaining = conversations.deleteConversation("kb-1", second.id());

        assertEquals(first.id(), remaining.id());
        assertEquals(1, conversations.conversations("kb-1").size());
    }

    /** Deleting the last one leaves a page with nothing to type into unless a fresh one appears. */
    @Test
    void deletingTheOnlyConversationStartsAnEmptyOne() {
        ConversationUseCase conversations = new ConversationUseCase(new InMemoryConversations());
        ConversationView only = conversations.activeConversation("kb-1");
        conversations.recordTurn(only.id(), "唯一一段", "回答", 1L);

        ConversationView replacement = conversations.deleteConversation("kb-1", only.id());

        assertNotEquals(only.id(), replacement.id());
        assertTrue(replacement.empty());
    }

    @Test
    void conversationsAreScopedToTheirKnowledgeBase() {
        ConversationUseCase conversations = new ConversationUseCase(new InMemoryConversations());
        ConversationView first = conversations.activeConversation("kb-1");
        conversations.recordTurn(first.id(), "属于 kb-1", "回答", 1L);
        ConversationView second = conversations.activeConversation("kb-2");

        assertNotEquals(first.id(), second.id());
        assertEquals(1, conversations.conversations("kb-1").size());
        assertEquals(1, conversations.conversations("kb-2").size());
        // Switching back returns to the conversation that base was left on.
        assertEquals(first.id(), conversations.activeConversation("kb-1").id());
    }

    /** A blank answer would otherwise be stored as a turn the model can never reproduce. */
    @Test
    void anEmptyTurnIsNotRecorded() {
        ConversationUseCase conversations = new ConversationUseCase(new InMemoryConversations());
        ConversationView conversation = conversations.activeConversation("kb-1");

        conversations.recordTurn(conversation.id(), "问题", "   ", 1L);

        assertTrue(conversations.transcript(conversation.id()).isEmpty());
        assertFalse(conversations.activeConversation("kb-1").messageCount() > 0);
    }

    /** Stands in for the SQLite table, which has its own round-trip test. */
    private static final class InMemoryConversations implements ConversationRepository {
        private final Map<String, ConversationView> conversations = new LinkedHashMap<>();
        private final Map<String, List<StoredMessage>> messages = new LinkedHashMap<>();
        private long clock = 1_000L;

        @Override
        public List<ConversationView> conversations(String knowledgeBaseId) {
            return conversations.values().stream()
                    .filter(conversation -> conversation.knowledgeBaseId().equals(knowledgeBaseId))
                    .map(this::withCount)
                    .sorted(Comparator.comparingLong(ConversationView::updatedAt).reversed())
                    .toList();
        }

        @Override
        public Optional<ConversationView> conversation(String conversationId) {
            return Optional.ofNullable(conversations.get(conversationId)).map(this::withCount);
        }

        @Override
        public List<StoredMessage> messages(String conversationId) {
            return List.copyOf(messages.getOrDefault(conversationId, List.of()));
        }

        @Override
        public void createConversation(ConversationView conversation) {
            conversations.put(conversation.id(), stamped(conversation));
            messages.put(conversation.id(), new ArrayList<>());
        }

        @Override
        public void renameConversation(String conversationId, String title) {
            ConversationView existing = conversations.get(conversationId);
            conversations.put(conversationId, new ConversationView(existing.id(),
                    existing.knowledgeBaseId(), title, 0, existing.createdAt(), existing.updatedAt()));
        }

        @Override
        public void appendMessage(String conversationId, ChatMessage message, long sourceRevision) {
            messages.get(conversationId).add(new StoredMessage(message, sourceRevision));
            conversations.put(conversationId, stamped(conversations.get(conversationId)));
        }

        @Override
        public void deleteConversation(String conversationId) {
            conversations.remove(conversationId);
            messages.remove(conversationId);
        }

        private ConversationView stamped(ConversationView conversation) {
            return new ConversationView(conversation.id(), conversation.knowledgeBaseId(),
                    conversation.title(), 0, conversation.createdAt(), clock++);
        }

        private ConversationView withCount(ConversationView conversation) {
            return new ConversationView(conversation.id(), conversation.knowledgeBaseId(),
                    conversation.title(), messages.getOrDefault(conversation.id(), List.of()).size(),
                    conversation.createdAt(), conversation.updatedAt());
        }
    }
}
