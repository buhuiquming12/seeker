package com.simplerag.application.conversation;

import java.util.Objects;

/**
 * One incremental piece of a streamed turn, tagged with what it is.
 *
 * <p>A turn no longer emits a single kind of text. A reasoning model produces a chain of thought
 * before its answer, and the adaptive retrieval loop produces a decision — plus the queries it wrote
 * itself — between the first search and the answer. All three reach the UI through the same channel,
 * so the channel has to say which is which: thinking must never be appended to the answer text,
 * because the answer text is what goes into conversation history, the clipboard and the citation
 * export.
 */
public record AnswerDelta(Stage stage, String text) {
    /**
     * PLANNING covers the retrieval loop's own reasoning and the queries it generated; ANSWER_THINKING
     * is the reasoning that precedes the final answer; ANSWER is the answer itself.
     */
    public enum Stage { PLANNING, ANSWER_THINKING, ANSWER }

    public AnswerDelta {
        Objects.requireNonNull(stage, "stage");
        text = text == null ? "" : text;
    }

    public static AnswerDelta planning(String text) {
        return new AnswerDelta(Stage.PLANNING, text);
    }

    public static AnswerDelta thinking(String text) {
        return new AnswerDelta(Stage.ANSWER_THINKING, text);
    }

    public static AnswerDelta answer(String text) {
        return new AnswerDelta(Stage.ANSWER, text);
    }

    /** True for everything the user may fold away: the answer itself is never foldable. */
    public boolean reasoning() {
        return stage != Stage.ANSWER;
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }
}
