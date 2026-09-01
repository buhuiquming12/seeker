package com.simplerag.model;

import java.util.List;

/**
 * @param reasoning the model's chain of thought, kept out of {@code text} on purpose: {@code text} is
 *                  what becomes conversation history and what the user copies, and thinking belongs in
 *                  neither.
 */
public record RagAnswer(String text, List<RagCitation> citations, String model, TokenUsage usage,
                        String reasoning) {
    public RagAnswer {
        usage = usage == null ? TokenUsage.UNKNOWN : usage;
        reasoning = reasoning == null ? "" : reasoning;
    }

    public RagAnswer(String text, List<RagCitation> citations, String model, TokenUsage usage) {
        this(text, citations, model, usage, "");
    }

    public RagAnswer(String text, List<RagCitation> citations, String model) {
        this(text, citations, model, TokenUsage.UNKNOWN, "");
    }
}
