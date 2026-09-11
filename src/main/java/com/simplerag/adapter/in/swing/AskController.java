package com.simplerag.adapter.in.swing;

import com.simplerag.application.port.in.AskKnowledge;
import com.simplerag.application.port.in.ManageApiSettings;
import com.simplerag.application.port.in.ManageConversations;
import com.simplerag.application.conversation.AnswerDelta;
import com.simplerag.application.conversation.ChatMessage;
import com.simplerag.application.conversation.ConversationSession;
import com.simplerag.application.conversation.ConversationStore;
import com.simplerag.application.conversation.StoredMessage;
import com.simplerag.application.dto.AskResultView;
import com.simplerag.application.dto.CitationView;
import com.simplerag.application.dto.ConversationView;
import com.simplerag.application.port.in.RemoteSendAuthorizer;
import com.simplerag.rag.ApiConfig;
import com.simplerag.rag.ModelApiConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class AskController {
    private final AskKnowledge ask;
    private final ManageApiSettings settings;
    private final ManageConversations conversations;
    private final ConversationStore sessions;

    public AskController(AskKnowledge ask, ManageApiSettings settings, ManageConversations conversations) {
        this(ask, settings, conversations, new ConversationStore());
    }

    public AskController(AskKnowledge ask, ManageApiSettings settings, ManageConversations conversations,
                         ConversationStore sessions) {
        this.ask = ask;
        this.settings = settings;
        this.conversations = conversations;
        this.sessions = sessions;
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

    public List<ConversationView> conversations(String knowledgeBaseId) {
        return conversations.conversations(knowledgeBaseId);
    }

    public ConversationView activeConversation(String knowledgeBaseId) {
        return conversations.activeConversation(knowledgeBaseId);
    }

    public ConversationView startConversation(String knowledgeBaseId) {
        return conversations.startConversation(knowledgeBaseId);
    }

    public ConversationView selectConversation(String knowledgeBaseId, String conversationId) {
        return conversations.selectConversation(knowledgeBaseId, conversationId);
    }

    public ConversationView deleteConversation(String knowledgeBaseId, String conversationId) {
        // The cached window of a conversation that no longer exists would otherwise sit there until
        // the process ends, keyed by an id nothing can reach.
        sessions.clear(conversationId);
        return conversations.deleteConversation(knowledgeBaseId, conversationId);
    }

    public List<StoredMessage> transcript(String conversationId) {
        return conversations.transcript(conversationId);
    }

    /**
     * The model-visible history of this conversation at the current revision, read from storage the
     * first time the session is opened and kept in memory afterwards.
     */
    public ConversationSession sessionFor(String conversationId, KnowledgeController.TaskIdentity identity) {
        ConversationSession session = sessions.openOrReplace(conversationId, identity.sourceRevision());
        if (!session.loaded()) session.load(historyAt(conversationId, identity.sourceRevision()));
        return session;
    }

    private List<ChatMessage> historyAt(String conversationId, long sourceRevision) {
        List<ChatMessage> result = new ArrayList<>();
        for (StoredMessage stored : conversations.transcript(conversationId)) {
            if (stored.from(sourceRevision)) result.add(stored.message());
        }
        return result;
    }

    /**
     * Streams an answer using the conversation's history at the current revision.
     * On success, the turn is appended to the session and written to storage.
     * History never includes citation snippets; retrieval always re-runs for the current question.
     */
    public AskResultView ask(String conversationId, KnowledgeController.TaskIdentity identity,
                             String question, ApiConfig config,
                             Consumer<List<CitationView>> onCitations, RemoteSendAuthorizer authorizer,
                             Consumer<AnswerDelta> onDelta)
            throws IOException, InterruptedException {
        ConversationSession session = sessionFor(conversationId, identity);
        // Snapshot history before appending the current user turn so the model sees prior turns only.
        List<ChatMessage> history = session.historyForRequest(false);
        AskResultView result = ask.askStream(identity.knowledgeBaseId(), identity.sourceRevision(),
                question, history, config, onCitations, authorizer, onDelta);
        // Only commit turns after success, and only if the store still owns this session object
        // (conversation/revision must not have changed mid-request).
        ConversationSession live = sessions.openOrReplace(conversationId, identity.sourceRevision());
        if (live == session) {
            session.appendUser(question);
            session.appendAssistant(result.text());
            conversations.recordTurn(conversationId, question, result.text(), identity.sourceRevision());
        }
        return result;
    }
}
