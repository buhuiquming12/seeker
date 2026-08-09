package com.simplerag.search;

import com.simplerag.application.port.out.TextEmbedder;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiQueryFusionTest {
    @TempDir Path temp;

    @Test
    void singleQueryIsAnIdentityPassThrough() {
        List<SearchResult> ranked = List.of(hit("a", 0.9), hit("b", 0.4));

        List<SearchResult> fused = QueryFusion.fuse(List.of("one"), query -> ranked, 10);

        // Same scores, same reasons: the one-facet path must not be rescaled or relabelled.
        assertEquals(ranked, fused);
    }

    @Test
    void singleQueryStillHonoursTheLimit() {
        List<SearchResult> ranked = List.of(hit("a", 0.9), hit("b", 0.4), hit("c", 0.1));

        assertEquals(2, QueryFusion.fuse(List.of("one"), query -> ranked, 2).size());
    }

    @Test
    void everyFacetGetsItsOwnSlotInTheMergedHead() {
        Map<String, List<SearchResult>> perQuery = Map.of(
                "facet-a", List.of(hit("a1", 0.99), hit("a2", 0.90)),
                "facet-b", List.of(hit("b1", 0.31), hit("b2", 0.30)));

        List<SearchResult> fused = QueryFusion.fuse(List.of("facet-a", "facet-b"), perQuery::get, 10);

        // The weak facet's leader still takes slot two: scores are not comparable across different
        // sub-questions, so the second question must not be crowded out by the first question's tail.
        assertEquals(List.of("a1", "b1", "a2", "b2"),
                fused.stream().map(result -> result.chunk().id()).toList());
    }

    @Test
    void aChunkAnsweringTwoFacetsIsTakenOnceAndTheOtherFacetAdvances() {
        Map<String, List<SearchResult>> perQuery = Map.of(
                "facet-a", List.of(hit("shared", 0.90), hit("a2", 0.80)),
                "facet-b", List.of(hit("shared", 0.88), hit("b2", 0.70)));

        List<SearchResult> fused = QueryFusion.fuse(List.of("facet-a", "facet-b"), perQuery::get, 10);

        assertEquals(List.of("shared", "b2", "a2"),
                fused.stream().map(result -> result.chunk().id()).toList());
    }

    @Test
    void retrieverScoresArePassedThroughUnchanged() {
        Map<String, List<SearchResult>> perQuery = Map.of(
                "facet-a", List.of(hit("a1", 0.99)),
                "facet-b", List.of(hit("b1", 0.31)));

        List<SearchResult> fused = QueryFusion.fuse(List.of("facet-a", "facet-b"), perQuery::get, 10);

        // ContextSelector's MMR weighting is calibrated against the pipeline's own [0,1] scores.
        assertEquals(0.99, fused.get(0).score(), 1e-9);
        assertEquals(0.31, fused.get(1).score(), 1e-9);
    }

    @Test
    void aShorterBranchDoesNotStallTheMerge() {
        Map<String, List<SearchResult>> perQuery = Map.of(
                "facet-a", List.of(hit("a1", 0.9), hit("a2", 0.8), hit("a3", 0.7)),
                "facet-b", List.of(hit("b1", 0.6)));

        List<SearchResult> fused = QueryFusion.fuse(List.of("facet-a", "facet-b"), perQuery::get, 10);

        assertEquals(List.of("a1", "b1", "a2", "a3"),
                fused.stream().map(result -> result.chunk().id()).toList());
    }

    @Test
    void fusedResultsDeclareTheFusionInTheirReason() {
        Map<String, List<SearchResult>> perQuery = Map.of(
                "facet-a", List.of(hit("a1", 0.30)),
                "facet-b", List.of(hit("b1", 0.28)));

        List<SearchResult> fused = QueryFusion.fuse(List.of("facet-a", "facet-b"), perQuery::get, 10);

        assertTrue(fused.get(0).reason().startsWith("子查询交错融合 · "), fused.get(0).reason());
        assertTrue(fused.get(0).reason().endsWith("test"), fused.get(0).reason());
    }

    @Test
    void toleratesEmptyAndMissingBranches() {
        assertTrue(QueryFusion.fuse(List.of(), query -> List.of(hit("a", 1.0)), 5).isEmpty());
        assertTrue(QueryFusion.fuse(null, query -> List.of(hit("a", 1.0)), 5).isEmpty());
        assertTrue(QueryFusion.fuse(List.of("a"), query -> null, 5).isEmpty());
        assertTrue(QueryFusion.fuse(List.of("a", "b"), query -> null, 5).isEmpty());
        assertTrue(QueryFusion.fuse(List.of("a", "b"), query -> List.of(hit("x", 0.5)), 0).isEmpty());
    }

    @Test
    void engineSingleQueryContextMatchesTheMultiQueryOverloadExactly() throws Exception {
        Files.writeString(temp.resolve("database-connection.md"),
                "MySQL connection through JDBC and a HikariCP connection pool");
        Files.writeString(temp.resolve("unrelated.md"), "UI navigation and keyboard shortcuts");
        SemanticSearchEngine engine = new SemanticSearchEngine(new DisabledEmbedder());
        engine.index(List.of(temp), null);

        List<SearchResult> legacy = engine.searchContext("database connection", 5, "all");
        List<SearchResult> viaList = engine.searchContext(List.of("database connection"), 5, "all",
                RetrievalStrategy.RRF_RERANK);

        assertFalse(legacy.isEmpty());
        assertEquals(legacy.size(), viaList.size());
        for (int i = 0; i < legacy.size(); i++) {
            assertEquals(legacy.get(i).chunk().id(), viaList.get(i).chunk().id());
            assertEquals(legacy.get(i).score(), viaList.get(i).score(), 1e-9);
            assertEquals(legacy.get(i).reason(), viaList.get(i).reason());
        }
    }

    @Test
    void engineFusesDistinctFacetsIntoOneContextSet() throws Exception {
        Files.writeString(temp.resolve("database-connection.md"),
                "MySQL connection through JDBC and a HikariCP connection pool");
        Files.writeString(temp.resolve("shortcuts.md"),
                "Keyboard shortcuts collapse the sidebar and widen the editor area");
        SemanticSearchEngine engine = new SemanticSearchEngine(new DisabledEmbedder());
        engine.index(List.of(temp), null);

        List<SearchResult> fused = engine.searchContext(
                List.of("JDBC connection pool", "collapse the sidebar"), 5, "all",
                RetrievalStrategy.RRF_RERANK);

        List<String> files = fused.stream().map(result -> result.chunk().fileName()).toList();
        assertTrue(files.contains("database-connection.md"), files.toString());
        assertTrue(files.contains("shortcuts.md"), files.toString());
    }

    private static SearchResult hit(String id, double score) {
        DocumentChunk chunk = new DocumentChunk(id, "docs/" + id + ".md", "docs", id + ".md", ".md",
                1, 3, "L1-3", "content for " + id, 1L, null);
        return new SearchResult(chunk, score, "test");
    }

    private static final class DisabledEmbedder implements TextEmbedder {
        public boolean isConfigured() { return false; }
        public List<float[]> embed(List<String> texts) throws IOException { throw new IOException("disabled"); }
        public String modelName() { return "disabled"; }
        public String status() { return "disabled"; }
        public void close() { }
    }
}
