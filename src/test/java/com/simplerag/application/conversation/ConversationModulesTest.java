package com.simplerag.application.conversation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationModulesTest {
    /**
     * The saved transcript outlives a rebuild; the model's memory of it must not. A revision change
     * hands out a fresh session so answers grounded in files that have since moved cannot be quoted
     * back as if they still held.
     */
    @Test
    void theModelVisibleHistoryIsBoundToTheConversationAndRevision() {
        ConversationStore store = new ConversationStore();
        ConversationSession first = store.openOrReplace("conversation-1", 1L);
        first.appendUser("hello");
        first.appendAssistant("hi");

        ConversationSession same = store.openOrReplace("conversation-1", 1L);
        assertSame(first, same);
        assertEquals(2, same.size());

        ConversationSession revised = store.openOrReplace("conversation-1", 2L);
        assertNotSame(first, revised);
        assertTrue(revised.isEmpty());
        assertFalse(revised.matches("conversation-1", 1L));
        assertFalse(revised.loaded(), "a replaced session still has to be told what is on disk");
    }

    /** Seeding is what makes a conversation survive a restart, and it respects the same budget. */
    @Test
    void aLoadedSessionIsTrimmedLikeOneThatGrewTurnByTurn() {
        ConversationSession session = new ConversationSession("conversation-1", 4L,
                new ConversationContext(4, 200));
        List<ChatMessage> saved = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            saved.add(ChatMessage.user("question-" + i + "-" + "x".repeat(20)));
            saved.add(ChatMessage.assistant("answer-" + i + "-" + "y".repeat(20)));
        }
        session.load(saved);

        assertTrue(session.loaded());
        assertTrue(session.size() <= 4, "loaded history bypassed the budget: " + session.size());
        assertEquals(ChatMessage.Role.USER, session.messages().get(0).role());
    }

    @Test
    void historyForRequestExcludesNothingWhenLastIsAssistant() {
        ConversationSession session = new ConversationSession("kb", 1L);
        session.appendUser("q1");
        session.appendAssistant("a1");
        List<ChatMessage> history = session.historyForRequest(false);
        assertEquals(2, history.size());
        assertEquals(ChatMessage.Role.USER, history.get(0).role());
    }

    @Test
    void contextTrimsByTurnAndTokenBudget() {
        ConversationContext policy = new ConversationContext(4, 200);
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(ChatMessage.user("question-" + i + "-" + "x".repeat(20)));
            messages.add(ChatMessage.assistant("answer-" + i + "-" + "y".repeat(20)));
        }
        List<ChatMessage> trimmed = policy.trim(messages);
        assertTrue(trimmed.size() <= 4);
        assertTrue(policy.estimatedTokens(trimmed) <= 200);
        assertEquals(ChatMessage.Role.USER, trimmed.get(0).role());
    }

    @Test
    void chatRequestRejectsSystemHistoryAndCopiesLists() {
        assertThrows(IllegalArgumentException.class, () ->
                new ChatRequest("kb", 1L, "q", List.of(ChatMessage.system("nope")), List.of()));
        ChatRequest request = new ChatRequest("kb", 1L, "q",
                List.of(ChatMessage.user("prior")), List.of());
        assertEquals(1, request.history().size());
        assertThrows(UnsupportedOperationException.class, () -> request.history().add(ChatMessage.user("x")));
    }

    @Test
    void sessionDoesNotRetainCitationsInMessages() {
        ConversationSession session = new ConversationSession("kb", 3L);
        session.appendUser("What is the port?");
        session.appendAssistant("It is 3306 [1].");
        for (ChatMessage message : session.messages()) {
            assertFalse(message.content().contains("file://"));
            assertTrue(message.role() == ChatMessage.Role.USER || message.role() == ChatMessage.Role.ASSISTANT);
        }
    }
}