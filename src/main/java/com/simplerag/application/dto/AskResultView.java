package com.simplerag.application.dto;

import com.simplerag.model.TokenUsage;

import java.util.List;

/**
 * @param reasoning the turn's chain of thought — retrieval planning and answer thinking combined.
 *                  Separate from {@code text} so the answer stays clean for history and clipboard.
 */
public record AskResultView(String text, List<CitationView> citations, String model, TokenUsage usage,
                            String reasoning) {
    public AskResultView {
        citations = List.copyOf(citations);
        usage = usage == null ? TokenUsage.UNKNOWN : usage;
        reasoning = reasoning == null ? "" : reasoning;
    }

    public AskResultView(String text, List<CitationView> citations, String model, TokenUsage usage) {
        this(text, citations, model, usage, "");
    }

    public AskResultView(String text, List<CitationView> citations, String model) {
        this(text, citations, model, TokenUsage.UNKNOWN, "");
    }
}
