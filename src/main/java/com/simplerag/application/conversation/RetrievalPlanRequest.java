package com.simplerag.application.conversation;

import com.simplerag.model.RagCitation;

import java.util.List;
import java.util.Objects;

/** Evidence and search history supplied to the model when deciding whether another retrieval is useful. */
public record RetrievalPlanRequest(
        String knowledgeBaseId,
        long sourceRevision,
        String question,
        List<ChatMessage> history,
        List<RagCitation> citations,
        List<RetrievalAttempt> attempts,
        int remainingSearches,
        boolean semanticRetrievalAvailable
) {
    public RetrievalPlanRequest {
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(citations, "citations");
        Objects.requireNonNull(attempts, "attempts");
        knowledgeBaseId = knowledgeBaseId.strip();
        question = question.strip();
        if (knowledgeBaseId.isEmpty()) throw new IllegalArgumentException("knowledgeBaseId must not be blank");
        if (question.isEmpty()) throw new IllegalArgumentException("Question must not be blank");
        if (remainingSearches < 1) throw new IllegalArgumentException("remainingSearches must be positive");
        history = List.copyOf(history);
        citations = List.copyOf(citations);
        attempts = List.copyOf(attempts);
    }

    /**
     * Assumes vector retrieval is available. Kept so callers that never ask for a HyDE pseudo-document
     * — and every existing test — do not have to state a capability they do not use.
     */
    public RetrievalPlanRequest(String knowledgeBaseId, long sourceRevision, String question,
                                List<ChatMessage> history, List<RagCitation> citations,
                                List<RetrievalAttempt> attempts, int remainingSearches) {
        this(knowledgeBaseId, sourceRevision, question, history, citations, attempts,
                remainingSearches, true);
    }
}
