package com.simplerag.application.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The turns of one conversation that the model is still allowed to remember.
 *
 * <p>Bound to a conversation plus the source revision it is being continued at. The saved transcript
 * outlives a revision change; this does not - answers above the change were grounded in files that
 * have since moved, so a new binding starts from nothing and the page marks where that happened.
 */
public final class ConversationSession {
    private final String conversationId;
    private final long sourceRevision;
    private final List<ChatMessage> messages = new ArrayList<>();
    private final ConversationContext contextPolicy;
    private boolean loaded;

    public ConversationSession(String conversationId, long sourceRevision) {
        this(conversationId, sourceRevision, ConversationContext.defaults());
    }

    public ConversationSession(String conversationId, long sourceRevision, ConversationContext contextPolicy) {
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId").strip();
        if (this.conversationId.isEmpty()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        this.sourceRevision = sourceRevision;
        this.contextPolicy = Objects.requireNonNull(contextPolicy, "contextPolicy");
    }

    public String id() {
        return conversationId;
    }

    public long sourceRevision() {
        return sourceRevision;
    }

    public synchronized boolean matches(String conversationId, long sourceRevision) {
        return this.conversationId.equals(conversationId) && this.sourceRevision == sourceRevision;
    }

    public synchronized List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    public synchronized boolean isEmpty() {
        return messages.isEmpty();
    }

    public synchronized int size() {
        return messages.size();
    }

    public synchronized void appendUser(String content) {
        messages.add(ChatMessage.user(content));
        trimInPlace();
    }

    public synchronized void appendAssistant(String content) {
        messages.add(ChatMessage.assistant(content));
        trimInPlace();
    }

    public synchronized void clear() {
        messages.clear();
    }

    /**
     * Whether this session has been given the turns already on disk.
     *
     * <p>A session is created empty and only the caller knows where its history comes from, so the
     * flag - not emptiness - is what says the lookup has happened: a conversation that really has no
     * turns at this revision would otherwise be read again before every question.
     */
    public synchronized boolean loaded() {
        return loaded;
    }

    /** Seeds this session from saved turns, trimming them to the context budget as if appended. */
    public synchronized void load(List<ChatMessage> history) {
        messages.clear();
        messages.addAll(Objects.requireNonNull(history, "history"));
        trimInPlace();
        loaded = true;
    }

    /**
     * Prior turns eligible for the next model call (excludes the current user question once appended).
     * Callers should snapshot history before appending the current user turn, or pass excludeLastUser=true.
     */
    public synchronized List<ChatMessage> historyForRequest(boolean excludeLastUser) {
        if (messages.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> source = messages;
        if (excludeLastUser) {
            ChatMessage last = messages.get(messages.size() - 1);
            if (last.role() == ChatMessage.Role.USER) {
                source = messages.subList(0, messages.size() - 1);
            }
        }
        return contextPolicy.trim(source);
    }

    public synchronized List<ChatMessage> trimmedMessages() {
        return contextPolicy.trim(messages);
    }

    private void trimInPlace() {
        List<ChatMessage> kept = contextPolicy.trim(messages);
        if (kept.size() == messages.size()) {
            return;
        }
        messages.clear();
        messages.addAll(kept);
    }

    @Override
    public String toString() {
        return "ConversationSession{conversationId='" + conversationId
                + "', sourceRevision=" + sourceRevision + ", messages=" + messages.size() + '}';
    }
}
