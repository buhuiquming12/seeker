package com.simplerag.application.port.in;

import com.simplerag.application.dto.AskResultView;
import com.simplerag.application.dto.CitationView;
import com.simplerag.application.conversation.AnswerDelta;
import com.simplerag.application.conversation.ChatMessage;
import com.simplerag.rag.ApiConfig;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

public interface AskKnowledge {
    /**
     * Multi-turn streaming ask. {@code history} is prior turns for the same knowledgeBaseId + revision.
     * Freshness gate and retrieval always re-run for the current question; history must not carry old citations.
     *
     * <p>{@code onDelta} receives tagged events: retrieval planning, answer thinking and answer text
     * arrive on one channel but stay distinguishable, so only the answer reaches history.
     */
    AskResultView askStream(String knowledgeBaseId, long expectedRevision, String question,
                            List<ChatMessage> history, ApiConfig config,
                            Consumer<List<CitationView>> onCitations, RemoteSendAuthorizer authorizer,
                            Consumer<AnswerDelta> onDelta)
            throws IOException, InterruptedException;

    /** Single-turn convenience with empty history. */
    default AskResultView askStream(String knowledgeBaseId, long expectedRevision, String question, ApiConfig config,
                                    Consumer<List<CitationView>> onCitations, Consumer<AnswerDelta> onDelta)
            throws IOException, InterruptedException {
        return askStream(knowledgeBaseId, expectedRevision, question, List.of(), config, onCitations,
                review -> true, onDelta);
    }
}
