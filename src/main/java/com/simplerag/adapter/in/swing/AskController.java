package com.simplerag.adapter.in.swing;

import com.simplerag.application.port.in.AskKnowledge;
import com.simplerag.application.port.in.ManageApiSettings;
import com.simplerag.application.conversation.AnswerDelta;
import com.simplerag.application.conversation.ChatMessage;
import com.simplerag.application.conversation.ConversationSession;
import com.simplerag.application.conversation.ConversationStore;
import com.simplerag.application.dto.AskResultView;
import com.simplerag.application.dto.CitationView;
import com.simplerag.application.port.in.RemoteSendAuthorizer;
import com.simplerag.rag.ApiConfig;
import com.simplerag.rag.ModelApiConfig;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

public final class AskController {
    private final AskKnowledge ask;
    private final ManageApiSettings settings;
    private final ConversationStore conversations;

    public AskController(AskKnowledge ask, ManageApiSettings settings) {
        this(ask, settings, new ConversationStore());
    }

    public AskController(AskKnowledge ask, ManageApiSettings settings, ConversationStore conversations) {
        this.ask = ask;
        this.settings = settings;
        this.conversations = conversations;
    }

    public ApiConfig config() { return settings.apiConfig(); }
    public void saveConfig(ApiConfig config) { settings.saveApiConfig(config); }
    public List<String> fetchModels(ApiConfig config) throws IOException, InterruptedException {
        return settings.fetchModels(config);
    }
    public ModelApiConfig embeddingConfig() { return settings.embeddingApiConfig(); }
    public void saveEmbeddingConfig(ModelApiConfig config) { settings.saveEmbeddingApiConfig(config); }
    public ModelApiConfig rerankConfig() { return settings.rerankApiConfig(); }
    public void saveRerankConfig(ModelApiConfig config) { settings.saveRerankApiConfig(config); }
    public boolean localOnly(String knowledgeBaseId) { return settings.localOnly(knowledgeBaseId); }
    public void saveLocalOnly(String knowledgeBaseId, boolean value) { settings.saveLocalOnly(knowledgeBaseId, value); }
    public void trustHost(String host) { settings.trustHost(host); }

    public ConversationStore conversations() {
        return conversations;
    }

    public ConversationSession sessionFor(KnowledgeController.TaskIdentity identity) {
        return conversations.openOrReplace(identity.knowledgeBaseId(), identity.sourceRevision());
    }

    public void clearSession(KnowledgeController.TaskIdentity identity) {
        conversations.clear(identity.knowledgeBaseId());
    }

    /**
     * Streams an answer using in-memory multi-turn history bound to knowledgeBaseId + sourceRevision.
     * On success, appends the user question and assistant reply to the session.
     * History never includes citation snippets; retrieval always re-runs for the current question.
     */
    public AskResultView ask(KnowledgeController.TaskIdentity identity, String question, ApiConfig config,
                             Consumer<List<CitationView>> onCitations, RemoteSendAuthorizer authorizer,
                             Consumer<AnswerDelta> onDelta)
            throws IOException, InterruptedException {
        ConversationSession session = sessionFor(identity);
        // Snapshot history before appending the current user turn so the model sees prior turns only.
        List<ChatMessage> history = session.historyForRequest(false);
        AskResultView result = ask.askStream(identity.knowledgeBaseId(), identity.sourceRevision(),
                question, history, config, onCitations, authorizer, onDelta);
        // Only commit turns after success, and only if the store still owns this session object
        // (knowledgeBase/revision must not have changed mid-request).
        ConversationSession live = conversations.openOrReplace(
                identity.knowledgeBaseId(), identity.sourceRevision());
        if (live == session) {
            session.appendUser(question);
            session.appendAssistant(result.text());
        }
        return result;
    }
}
