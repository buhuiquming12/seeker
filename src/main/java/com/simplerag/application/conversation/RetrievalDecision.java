package com.simplerag.application.conversation;

import com.simplerag.model.TokenUsage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The model's bounded decision: answer with current evidence, or run one more round of local
 * searches using queries it generated itself.
 *
 * <p>{@link Action#UNAVAILABLE} is deliberately distinct from {@link Action#ANSWER}. Both stop the
 * loop, but only ANSWER means the model judged the evidence sufficient; UNAVAILABLE means we never
 * got a usable decision (unreachable, truncated, or malformed). Collapsing the two hid planner
 * breakage behind answers that looked confident but were built on first-round evidence only.
 *
 * <p>Each query carries a {@link QueryMode}. A {@link QueryMode#KEYWORD} query is retrieved the
 * ordinary hybrid way; a {@link QueryMode#HYPOTHETICAL} one is a HyDE pseudo-document, written as
 * though the answer were already known, and is retrieved by vector similarity alone.
 */
public record RetrievalDecision(Action action, List<TypedQuery> queries, TokenUsage usage) {
    /** One planning round may fan out this many generated queries. */
    public static final int MAX_QUERIES = 3;
    private static final int MAX_QUERY_LENGTH = 500;
    /**
     * A pseudo-document is a paragraph, not a query string, so it needs far more room. Truncating it
     * at the keyword ceiling would cut a sentence in half and embed the fragment.
     */
    private static final int MAX_HYPOTHETICAL_LENGTH = 1_200;

    public enum Action { SEARCH, ANSWER, UNAVAILABLE }

    /**
     * How a generated query should be retrieved. KEYWORD goes through the hybrid BM25 + vector
     * pipeline; HYPOTHETICAL is a HyDE pseudo-document and must go through the vector branch alone,
     * because a paragraph of invented prose used as a BM25 query is mostly noise.
     */
    public enum QueryMode { KEYWORD, HYPOTHETICAL }

    /** One generated query plus the retrieval mode the planner asked for. */
    public record TypedQuery(String text, QueryMode mode) {
        public TypedQuery {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(mode, "mode");
        }

        public boolean hypothetical() {
            return mode == QueryMode.HYPOTHETICAL;
        }
    }

    public RetrievalDecision {
        Objects.requireNonNull(action, "action");
        queries = action == Action.SEARCH ? normalize(queries) : List.of();
        if (action == Action.SEARCH && queries.isEmpty()) {
            throw new IllegalArgumentException("At least one search query is required");
        }
        usage = usage == null ? TokenUsage.UNKNOWN : usage;
    }

    /** Trims, drops blanks, caps length per mode, removes case-insensitive duplicates, bounds fan-out. */
    private static List<TypedQuery> normalize(List<TypedQuery> raw) {
        if (raw == null) return List.of();
        List<TypedQuery> cleaned = new ArrayList<>(Math.min(raw.size(), MAX_QUERIES));
        Set<String> seen = new LinkedHashSet<>();
        for (TypedQuery candidate : raw) {
            if (candidate == null || candidate.text() == null) continue;
            String query = candidate.text().strip();
            if (query.isEmpty()) continue;
            int ceiling = candidate.hypothetical() ? MAX_HYPOTHETICAL_LENGTH : MAX_QUERY_LENGTH;
            if (query.length() > ceiling) query = query.substring(0, ceiling).strip();
            if (!seen.add(query.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))) continue;
            cleaned.add(new TypedQuery(query, candidate.mode()));
            if (cleaned.size() >= MAX_QUERIES) break;
        }
        return List.copyOf(cleaned);
    }

    private static List<TypedQuery> keywords(List<String> queries) {
        if (queries == null) return List.of();
        return queries.stream().filter(Objects::nonNull)
                .map(query -> new TypedQuery(query, QueryMode.KEYWORD)).toList();
    }

    public static RetrievalDecision search(String... queries) {
        return search(Arrays.asList(queries));
    }

    public static RetrievalDecision search(List<String> queries) {
        return new RetrievalDecision(Action.SEARCH, keywords(queries), TokenUsage.UNKNOWN);
    }

    /**
     * Typed queries with explicit modes. Cannot be another {@code search} overload: it would erase to
     * the same {@code search(List)} signature as {@link #search(List)}.
     */
    public static RetrievalDecision plan(List<TypedQuery> queries) {
        return new RetrievalDecision(Action.SEARCH, queries, TokenUsage.UNKNOWN);
    }

    public static RetrievalDecision answer() {
        return new RetrievalDecision(Action.ANSWER, List.of(), TokenUsage.UNKNOWN);
    }

    /** No usable decision was obtained. Carries usage so a failed round is still billed honestly. */
    public static RetrievalDecision unavailable() {
        return new RetrievalDecision(Action.UNAVAILABLE, List.of(), TokenUsage.UNKNOWN);
    }

    /** Attaches what this planning round actually cost, so a turn can report its real total. */
    public RetrievalDecision withUsage(TokenUsage measured) {
        return new RetrievalDecision(action, queries, measured);
    }

    public boolean shouldSearch() {
        return action == Action.SEARCH;
    }

    /** True when the loop stopped because planning failed rather than because evidence sufficed. */
    public boolean plannerUnavailable() {
        return action == Action.UNAVAILABLE;
    }

    /** The generated query texts, mode stripped. */
    public List<String> queryTexts() {
        return queries.stream().map(TypedQuery::text).toList();
    }

    /** The first generated query, or empty for non-search decisions. */
    public String query() {
        return queries.isEmpty() ? "" : queries.get(0).text();
    }
}
