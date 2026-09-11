package com.simplerag.application.usecase;

import com.simplerag.application.conversation.ChatMessage;
import com.simplerag.application.conversation.StoredMessage;
import com.simplerag.application.dto.ConversationView;
import com.simplerag.application.port.in.ManageConversations;
import com.simplerag.application.port.out.ConversationRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Saved conversations per knowledge base.
 *
 * <p>Which conversation is active is held here rather than in a column: it is a property of the
 * window, not of the data, and after a restart the most recently used one is the right answer anyway.
 */
public final class ConversationUseCase implements ManageConversations {
    private final ConversationRepository repository;
    /** knowledgeBaseId -> conversationId the user is looking at. */
    private final Map<String, String> selected = new ConcurrentHashMap<>();

    public ConversationUseCase(ConversationRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public List<ConversationView> conversations(String knowledgeBaseId) {
        return repository.conversations(knowledgeBaseId);
    }

    @Override
    public ConversationView activeConversation(String knowledgeBaseId) {
        String chosen = selected.get(knowledgeBaseId);
        if (chosen != null) {
            Optional<ConversationView> found = repository.conversation(chosen);
            if (found.isPresent()) return found.get();
            // Deleting the knowledge base takes its conversations with it.
            selected.remove(knowledgeBaseId);
        }
        List<ConversationView> existing = repository.conversations(knowledgeBaseId);
        if (!existing.isEmpty()) return select(knowledgeBaseId, existing.get(0));
        return startConversation(knowledgeBaseId);
    }

    @Override
    public ConversationView startConversation(String knowledgeBaseId) {
        List<ConversationView> existing = repository.conversations(knowledgeBaseId);
        // Pressing 新对话 twice, or opening the application twice without asking anything, must not
        // leave a stack of identical empty rows behind.
        if (!existing.isEmpty() && existing.get(0).empty()) return select(knowledgeBaseId, existing.get(0));
        long now = System.currentTimeMillis();
        ConversationView created = new ConversationView(UUID.randomUUID().toString(), knowledgeBaseId,
                "", 0, now, now);
        repository.createConversation(created);
        return select(knowledgeBaseId, created);
    }

    @Override
    public ConversationView selectConversation(String knowledgeBaseId, String conversationId) {
        return repository.conversation(conversationId)
                .map(conversation -> select(knowledgeBaseId, conversation))
                .orElseGet(() -> activeConversation(knowledgeBaseId));
    }

    @Override
    public ConversationView deleteConversation(String knowledgeBaseId, String conversationId) {
        repository.deleteConversation(conversationId);
        selected.remove(knowledgeBaseId, conversationId);
        return activeConversation(knowledgeBaseId);
    }

    @Override
    public List<StoredMessage> transcript(String conversationId) {
        return repository.messages(conversationId);
    }

    @Override
    public void recordTurn(String conversationId, String question, String answer, long sourceRevision) {
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) return;
        repository.appendMessage(conversationId, ChatMessage.user(question), sourceRevision);
        repository.appendMessage(conversationId, ChatMessage.assistant(answer), sourceRevision);
        repository.conversation(conversationId)
                .filter(conversation -> conversation.title().isBlank())
                .ifPresent(conversation ->
                        repository.renameConversation(conversationId, ConversationView.titleFrom(question)));
    }

    private ConversationView select(String knowledgeBaseId, ConversationView conversation) {
        selected.put(knowledgeBaseId, conversation.id());
        return conversation;
    }
}
