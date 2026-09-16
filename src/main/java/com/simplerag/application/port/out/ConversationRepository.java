package com.simplerag.application.port.out;

import com.simplerag.application.conversation.ChatMessage;
import com.simplerag.application.conversation.StoredMessage;
import com.simplerag.application.dto.ConversationView;

import java.util.List;
import java.util.Optional;

/** Durable conversations and their turns, scoped to a knowledge base. */
public interface ConversationRepository {
    /** Newest first, which is the order the session list shows and the default active one. */
    List<ConversationView> conversations(String knowledgeBaseId);

    Optional<ConversationView> conversation(String conversationId);

    /** Every turn in order, including turns from source revisions the model no longer remembers. */
    List<StoredMessage> messages(String conversationId);

    void createConversation(ConversationView conversation);

    void renameConversation(String conversationId, String title);

    void appendMessage(String conversationId, ChatMessage message, long sourceRevision);

    /** Persists both sides of a completed turn atomically when the adapter supports transactions. */
    default void appendTurn(String conversationId, ChatMessage user, ChatMessage assistant,
                            long sourceRevision) {
        appendMessage(conversationId, user, sourceRevision);
        appendMessage(conversationId, assistant, sourceRevision);
    }

    void deleteConversation(String conversationId);
}
