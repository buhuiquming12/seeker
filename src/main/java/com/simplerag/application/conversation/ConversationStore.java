package com.simplerag.application.conversation;

import com.simplerag.common.text.TextValues;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The model-visible half of a conversation, cached per conversation id.
 *
 * <p>The transcript itself is durable and lives in the repository; what is held here is only the
 * window of it the model is allowed to see. A session is replaced when sourceRevision changes so
 * answers grounded in files that have since moved cannot be quoted back as if they still held.
 */
public final class ConversationStore {
    private final Map<String, ConversationSession> sessions = new ConcurrentHashMap<>();
    private final ConversationContext contextPolicy;

    public ConversationStore() {
        this(ConversationContext.defaults());
    }

    public ConversationStore(ConversationContext contextPolicy) {
        this.contextPolicy = Objects.requireNonNull(contextPolicy, "contextPolicy");
    }

    public ConversationSession openOrReplace(String conversationId, long sourceRevision) {
        Objects.requireNonNull(conversationId, "conversationId");
        String key = TextValues.trimToEmpty(conversationId);
        return sessions.compute(key, (ignored, existing) -> {
            if (existing != null && existing.matches(key, sourceRevision)) {
                return existing;
            }
            return new ConversationSession(key, sourceRevision, contextPolicy);
        });
    }

    public Optional<ConversationSession> find(String conversationId) {
        if (conversationId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(TextValues.trimToEmpty(conversationId)));
    }

    public ConversationSession requireMatching(String conversationId, long sourceRevision) {
        ConversationSession session = openOrReplace(conversationId, sourceRevision);
        if (!session.matches(conversationId, sourceRevision)) {
            throw new IllegalStateException("会话与当前知识库版本不一致，请重新开始对话");
        }
        return session;
    }

    public void clear(String conversationId) {
        if (conversationId == null) {
            return;
        }
        sessions.remove(TextValues.trimToEmpty(conversationId));
    }

    public void clearAll() {
        sessions.clear();
    }

    public int sessionCount() {
        return sessions.size();
    }
}
