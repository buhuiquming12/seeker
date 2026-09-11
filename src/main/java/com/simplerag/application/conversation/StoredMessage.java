package com.simplerag.application.conversation;

import java.util.Objects;

/**
 * A saved turn together with the source revision it was produced under.
 *
 * <p>The revision travels with the message because a conversation now outlives it. History sent to
 * the model is still cut at a revision change - the answers above it were grounded in files that have
 * since moved - while the transcript keeps every turn and marks where the model's memory restarted.
 */
public record StoredMessage(ChatMessage message, long sourceRevision) {
    public StoredMessage {
        Objects.requireNonNull(message, "message");
    }

    public boolean from(long revision) {
        return sourceRevision == revision;
    }
}
