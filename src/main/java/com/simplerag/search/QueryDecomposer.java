package com.simplerag.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Splits a question that asks several things at once into the individual queries it really asks.
 *
 * <p>Embedding "2023年诺贝尔文学奖得主是哪国人？他的代表作是什么？" as a single vector averages two
 * unrelated topics into a point that sits near neither, so the dense branch retrieves for a question
 * nobody asked. Retrieving each facet separately and fusing the rankings keeps both topics sharp.
 *
 * <p>Deliberately local, deterministic and rule-based. The first retrieval of a turn runs before the
 * user has authorised any remote send, so this must not call a model — the same constraint
 * {@code ConversationQueryResolver} documents.
 *
 * <p>Biased towards <em>not</em> splitting. A single-facet question must come back as exactly one
 * query so existing retrieval behaviour is untouched: a missed split merely forgoes an improvement,
 * while a wrong split shreds one question into fragments that each retrieve nothing.
 */
public final class QueryDecomposer {
    /** A question rarely asks more than three distinct things; past that the split is noise. */
    public static final int MAX_SUB_QUERIES = 3;
    /** Below this a fragment is a stray token ("C", "B在哪"), not something worth retrieving on. */
    private static final int MIN_SUB_QUERY_CHARS = 4;

    /**
     * Boundaries that genuinely separate two asks: sentence terminators, and the conjunctions that
     * introduce a second one. A bare comma is deliberately absent — "重试、超时和降级的配置" joins one
     * subject far more often than it separates two questions.
     */
    private static final Pattern BOUNDARY = Pattern.compile(
            "[？?。；;！!]+"
            + "|[，,]?\\s*(?:以及|还有|另外|顺便|同时)"
            + "|\\s+(?:and also|as well as)\\s+",
            Pattern.CASE_INSENSITIVE);

    /** Punctuation left dangling at a fragment edge once the boundary itself was cut out. */
    private static final Pattern EDGE_PUNCTUATION = Pattern.compile(
            "^[\\s、，,：:；;。！!？?]+|[\\s、，,：:；;。！!？?]+$");

    private final LexicalFeatureExtractor features;

    public QueryDecomposer() {
        this(new LexicalFeatureExtractor());
    }

    public QueryDecomposer(LexicalFeatureExtractor features) {
        this.features = features;
    }

    /**
     * Returns the queries to retrieve for, never empty. Falls back to the original question whenever
     * the split failed to produce at least two usable facets.
     */
    public List<String> decompose(String question) {
        String cleaned = question == null ? "" : question.strip();
        Map<String, String> facets = new LinkedHashMap<>();
        for (String fragment : BOUNDARY.split(cleaned)) {
            String facet = EDGE_PUNCTUATION.matcher(fragment).replaceAll("").strip();
            if (facet.codePointCount(0, facet.length()) < MIN_SUB_QUERY_CHARS) continue;
            // NFKC + lowercase so "JDBC" and "ｊｄｂｃ" do not both survive as separate facets.
            facets.putIfAbsent(features.normalize(facet), facet);
            if (facets.size() >= MAX_SUB_QUERIES) break;
        }
        // A single facet means the question was already single-purpose. Hand back exactly what was
        // asked, so QueryAnalyzer still sees the original punctuation and framing it keys off.
        return facets.size() < 2 ? List.of(cleaned) : List.copyOf(facets.values());
    }
}
