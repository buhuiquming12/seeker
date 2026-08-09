package com.simplerag.search;

import com.simplerag.model.SearchResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Merges the results of several sub-queries by interleaving them, so every facet of a decomposed
 * question gets its share of the context budget.
 *
 * <p>Deliberately <em>not</em> reciprocal rank fusion, which {@link RetrievalPipeline} uses one level
 * down. RRF answers "these retrievers all saw the same query, which results do they agree on"; the
 * agreement is the signal. Here each sub-query is a different question, so agreement is not the
 * signal and scores are not comparable across facets — "怎么配置" matches half the corpus and its
 * top hit is worth far less than a specific facet's top hit. Measured on multi-facet questions, RRF
 * let a weak facet's leader displace a strong facet's answer, dropping Recall@5 from 0.92 to 0.75.
 *
 * <p>Round-robin instead gives facet <i>i</i> its own slot in every cycle: the first result is the
 * best answer to the first sub-question, the second is the best answer to the second, and so on. A
 * chunk that answers two facets is taken once, at its first appearance, and the facet that missed
 * out simply advances to its next candidate — so agreement costs nothing and no facet is starved.
 */
public final class QueryFusion {
    private QueryFusion() { }

    /**
     * Runs {@code search} for every query and interleaves the rankings into the top {@code limit}.
     *
     * <p>Scores are passed through as the retriever produced them, so they stay comparable with the
     * single-query path that {@code ContextSelector} and the citation panel are calibrated against.
     *
     * <p>A single query is returned untouched apart from the limit, which keeps the one-facet path —
     * most questions — byte-for-byte identical to searching directly.
     */
    public static List<SearchResult> fuse(List<String> queries,
                                          Function<String, List<SearchResult>> search,
                                          int limit) {
        if (queries == null || queries.isEmpty() || limit <= 0) return List.of();
        if (queries.size() == 1) {
            List<SearchResult> single = search.apply(queries.get(0));
            if (single == null) return List.of();
            return single.size() <= limit ? single : List.copyOf(single.subList(0, limit));
        }

        List<List<SearchResult>> branches = new ArrayList<>(queries.size());
        int deepest = 0;
        for (String query : queries) {
            List<SearchResult> ranked = search.apply(query);
            branches.add(ranked == null ? List.of() : ranked);
            deepest = Math.max(deepest, ranked == null ? 0 : ranked.size());
        }

        Set<String> taken = new HashSet<>();
        List<SearchResult> merged = new ArrayList<>(limit);
        // Cursors advance independently: a facet whose candidate was already taken by an earlier facet
        // moves on within the same cycle rather than forfeiting its slot.
        int[] cursors = new int[branches.size()];
        for (int cycle = 0; cycle < deepest && merged.size() < limit; cycle++) {
            for (int branch = 0; branch < branches.size() && merged.size() < limit; branch++) {
                List<SearchResult> ranked = branches.get(branch);
                while (cursors[branch] < ranked.size()) {
                    SearchResult hit = ranked.get(cursors[branch]++);
                    if (hit == null || hit.chunk() == null) continue;
                    if (!taken.add(hit.chunk().id())) continue;
                    merged.add(new SearchResult(hit.chunk(), hit.score(),
                            "子查询交错融合 · " + hit.reason()));
                    break;
                }
            }
        }
        return List.copyOf(merged);
    }
}
