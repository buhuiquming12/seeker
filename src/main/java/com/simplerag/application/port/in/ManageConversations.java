package com.simplerag.application.port.in;

import com.simplerag.application.conversation.StoredMessage;
import com.simplerag.application.dto.ConversationView;

import java.util.List;

/** The saved conversations of a knowledge base and which one the ask page is showing. */
public interface ManageConversations {
    List<ConversationView> conversations(String knowledgeBaseId);

    /**
     * The conversation to show for this knowledge base, created if the base has none yet. Which one
     * that is survives a knowledge-base switch within a session and falls back to the most recently
     * used one after a restart.
     */
    ConversationView activeConversation(String knowledgeBaseId);

    /** Starts and selects an empty conversation, reusing one that is already empty. */
    ConversationView startConversation(String knowledgeBaseId);

    ConversationView selectConversation(String knowledgeBaseId, String conversationId);

    /** @return the conversation that becomes active in its place */
    ConversationView deleteConversation(String knowledgeBaseId, String conversationId);

    List<StoredMessage> transcript(String conversationId);

    /** Saves a completed turn and names the conversation after its first question. */
    void recordTurn(String conversationId, String question, String answer, long sourceRevision);
}
