package com.simplerag.search;

import com.simplerag.application.port.out.TextEmbedder;
import com.simplerag.application.diagnostics.DiagnosticSink;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.SearchResult;
import com.simplerag.model.SemanticHighlight;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class SemanticSearchEngine {
    /** One turn issues at most a decomposed first round plus three planning rounds of queries. */
    private static final int QUERY_EMBEDDING_CACHE_SIZE = 16;

    private final TextEmbedder embeddingProvider;
    private final DocumentScanner documentScanner;
    private final DocumentReaderRegistry readerRegistry;
    private final ChunkerRegistry chunkerRegistry;
    private final LexicalFeatureExtractor lexicalFeatures;
    private final QueryAnalyzer queryAnalyzer;
    private final LexicalScorer lexicalScorer;
    private final RetrievalPipeline retrievalPipeline;
    private final ContextSelector contextSelector;
    private final SemanticHighlightService highlightService;
    private final SemanticScorer semanticScorer;
    private final RankingPolicy rankingPolicy;
    private final DiagnosticSink diagnostics;
    private volatile State state = State.empty();
    private volatile List<String> roots = List.of();
    private volatile long indexedAt;
    private volatile boolean embeddingsActive;
    private volatile boolean semanticCompatible;
    private volatile IndexManifest manifest;
    private volatile List<DocumentIndexEntry> documentEntries = List.of();
    /**
     * Query embeddings, keyed on the analyzer's semantic text. A single slot was enough while every
     * turn issued one query; a decomposed question issues several, and the planner adds more, so a
     * one-slot cache never hit and re-embedded work it had just done.
     */
    private final Map<String, float[]> queryEmbeddingCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > QUERY_EMBEDDING_CACHE_SIZE;
        }
    };

    public SemanticSearchEngine(TextEmbedder embeddingProvider) {
        this(embeddingProvider, new DocumentScanner(), new DocumentReaderRegistry(), new ChunkerRegistry(),
                new LexicalFeatureExtractor(), new SemanticScorer(), RankingPolicy.defaultPolicy(), DiagnosticSink.noop());
    }

    public SemanticSearchEngine(TextEmbedder embeddingProvider, DiagnosticSink diagnostics) {
        this(embeddingProvider, new DocumentScanner(), new DocumentReaderRegistry(), new ChunkerRegistry(),
                new LexicalFeatureExtractor(), new SemanticScorer(), RankingPolicy.defaultPolicy(), diagnostics);
    }

    public SemanticSearchEngine(TextEmbedder embeddingProvider, SecondStageReranker reranker,
                                DiagnosticSink diagnostics) {
        this(embeddingProvider, new DocumentScanner(), new DocumentReaderRegistry(), new ChunkerRegistry(),
                new LexicalFeatureExtractor(), new SemanticScorer(), RankingPolicy.defaultPolicy(),
                reranker, diagnostics);
    }

    public SemanticSearchEngine(TextEmbedder embeddingProvider, SemanticScorer semanticScorer,
                                RankingPolicy rankingPolicy) {
        this(embeddingProvider, new DocumentScanner(), new DocumentReaderRegistry(), new ChunkerRegistry(),
                new LexicalFeatureExtractor(), semanticScorer, rankingPolicy, DiagnosticSink.noop());
    }

    public SemanticSearchEngine(TextEmbedder embeddingProvider, DocumentScanner documentScanner,
                                DocumentReaderRegistry readerRegistry, ChunkerRegistry chunkerRegistry,
                                LexicalFeatureExtractor lexicalFeatures, SemanticScorer semanticScorer,
                                RankingPolicy rankingPolicy) {
        this(embeddingProvider, documentScanner, readerRegistry, chunkerRegistry, lexicalFeatures,
                semanticScorer, rankingPolicy, DiagnosticSink.noop());
    }

    public SemanticSearchEngine(TextEmbedder embeddingProvider, DocumentScanner documentScanner,
                                DocumentReaderRegistry readerRegistry, ChunkerRegistry chunkerRegistry,
                                LexicalFeatureExtractor lexicalFeatures, SemanticScorer semanticScorer,
                                RankingPolicy rankingPolicy, DiagnosticSink diagnostics) {
        this(embeddingProvider, documentScanner, readerRegistry, chunkerRegistry, lexicalFeatures,
                semanticScorer, rankingPolicy, new FeatureReranker(), diagnostics);
    }

    public SemanticSearchEngine(TextEmbedder embeddingProvider, DocumentScanner documentScanner,
                                DocumentReaderRegistry readerRegistry, ChunkerRegistry chunkerRegistry,
                                LexicalFeatureExtractor lexicalFeatures, SemanticScorer semanticScorer,
                                RankingPolicy rankingPolicy, SecondStageReranker reranker,
                                DiagnosticSink diagnostics) {
        this.embeddingProvider = embeddingProvider;
        this.documentScanner = documentScanner;
        this.readerRegistry = readerRegistry;
        this.chunkerRegistry = chunkerRegistry;
        this.lexicalFeatures = lexicalFeatures;
        this.queryAnalyzer = new QueryAnalyzer(lexicalFeatures);
        this.lexicalScorer = new LexicalScorer(lexicalFeatures);
        this.retrievalPipeline = new RetrievalPipeline(semanticScorer, lexicalScorer,
                reranker == null ? new FeatureReranker() : reranker);
        this.contextSelector = new ContextSelector();
        this.highlightService = new SemanticHighlightService(embeddingProvider, semanticScorer);
        this.semanticScorer = semanticScorer;
        this.rankingPolicy = rankingPolicy;
        this.diagnostics = diagnostics == null ? DiagnosticSink.noop() : diagnostics;
    }

    public IndexReport index(List<Path> sourceRoots, Consumer<IndexProgress> progress) throws IOException {
        return index(sourceRoots, null, progress);
    }

    public IndexReport index(List<Path> sourceRoots, IndexSnapshot previous,
                             Consumer<IndexProgress> progress) throws IOException {
        IncrementalIndexBuilder.BuildResult result = new IncrementalIndexBuilder(embeddingProvider,
                documentScanner, readerRegistry, chunkerRegistry).build(sourceRoots, previous, progress);
        clearHighlightCache();
        this.state = State.build(result.chunks(), lexicalFeatures);
        this.roots = result.roots();
        this.indexedAt = System.currentTimeMillis();
        this.documentEntries = result.documentEntries();
        this.embeddingsActive = state.hasEmbeddings;
        this.semanticCompatible = state.hasEmbeddings && embeddingProvider.isConfigured();
        return result.report();
    }

    public List<SearchResult> search(String query, int limit, String extensionFilter) {
        return search(query, limit, extensionFilter, RetrievalStrategy.RRF_RERANK);
    }

    /** Search entry point used by the evaluation harness for reproducible ablations. */
    public List<SearchResult> search(String query, int limit, String extensionFilter,
                                     RetrievalStrategy strategy) {
        State current = state;
        QueryAnalyzer.AnalyzedQuery analyzed = queryAnalyzer.analyze(query,
                current.documentFrequency, current.documents.size());
        if (analyzed.empty()) return List.of();
        RetrievalStrategy selected = strategy == null ? RetrievalStrategy.RRF_RERANK : strategy;
        if (analyzed.tokens().isEmpty() && (!semanticCompatible || !selected.usesDense())) return List.of();
        float[] semanticQuery = selected.usesDense() ? prepareSemanticQuery(analyzed, current) : null;
        return retrievalPipeline.search(analyzed, current.documents, current.documentFrequency,
                semanticQuery, limit, extensionFilter, selected);
    }

    /** Search plus MMR, per-document quotas and parent/adjacent context expansion for generation. */
    public List<SearchResult> searchContext(String query, int limit, String extensionFilter) {
        return searchContext(List.of(query), limit, extensionFilter, RetrievalStrategy.RRF_RERANK);
    }

    /**
     * Context retrieval for a decomposed question: every sub-query is retrieved separately and the
     * rankings are fused before MMR and context expansion run once over the combined pool.
     *
     * <p>Fusing before selection rather than after is what makes the facets comparable — MMR then sees
     * the whole pool, so its redundancy penalty can tell a chunk two facets both want from a chunk
     * only one facet found. A single query is an identity pass through the fusion, so the ordinary
     * one-facet path is unchanged.
     */
    public List<SearchResult> searchContext(List<String> queries, int limit, String extensionFilter,
                                            RetrievalStrategy strategy) {
        if (queries == null || queries.isEmpty() || limit <= 0) return List.of();
        RetrievalStrategy selected = strategy == null ? RetrievalStrategy.RRF_RERANK : strategy;
        int candidateLimit = Math.max(20, limit * 4);
        List<SearchResult> ranked = QueryFusion.fuse(queries,
                query -> search(query, candidateLimit, extensionFilter, selected), candidateLimit);
        return contextSelector.select(ranked,
                state.documents.stream().map(RetrievalDocument::chunk).toList(), limit);
    }

    private float[] prepareSemanticQuery(QueryAnalyzer.AnalyzedQuery analyzed, State current) {
        refreshSemanticCompatibility();
        if (!semanticCompatible) return null;
        try {
            float[] query = semanticQuery(analyzed.semanticText());
            if (!semanticScorer.queryCompatible(query,
                    current.documents.stream().map(RetrievalDocument::chunk).toList())) return null;
            embeddingsActive = true;
            return query;
        } catch (IOException failure) {
            embeddingsActive = false;
            diagnostics.record("vector fallback reason", "retrieval", failure.getClass().getSimpleName(),
                    Map.of("reason", failure.getMessage() == null ? "embedding failed" : failure.getMessage()));
            return null;
        }
    }

    public List<SemanticHighlight> semanticHighlights(String query, DocumentChunk chunk, int limit)
            throws IOException {
        return highlightService.locate(query, chunk, limit, semanticCompatible);
    }

    public void restore(IndexSnapshot snapshot) {
        clearHighlightCache();
        this.state = State.build(snapshot.chunks(), lexicalFeatures);
        this.roots = List.copyOf(snapshot.roots());
        this.indexedAt = snapshot.indexedAt();
        this.manifest = snapshot.manifest();
        this.documentEntries = snapshot.documentEntries();
        this.semanticCompatible = isSemanticCompatible(snapshot);
        this.embeddingsActive = semanticCompatible;
    }

    public IndexSnapshot snapshot() {
        List<DocumentChunk> chunks = state.documents.stream().map(RetrievalDocument::chunk).toList();
        return new IndexSnapshot(IndexSnapshot.CURRENT_VERSION, roots, chunks, indexedAt,
                state.hasEmbeddings ? embeddingProvider.modelName() : "", manifest, documentEntries);
    }

    public IndexSnapshot snapshot(IndexManifest value) {
        this.manifest = value;
        return snapshot();
    }

    public List<String> roots() {
        return roots;
    }

    /**
     * File-level manifest entries of the published index.
     *
     * <p>Exposed separately from {@link #snapshot()} because that method rebuilds the whole chunk
     * list on every call; callers that only need per-file coverage should not pay for it.
     */
    public List<DocumentIndexEntry> documentEntries() {
        return documentEntries;
    }

    public int chunkCount() {
        return state.documents.size();
    }

    public int fileCount() {
        return (int) state.documents.stream().map(indexed -> indexed.chunk().path()).distinct().count();
    }

    public Set<String> extensions() {
        return state.documents.stream().map(indexed -> indexed.chunk().extension())
                .filter(value -> !value.isBlank()).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    public boolean semanticEnabled() {
        refreshSemanticCompatibility();
        return semanticCompatible && embeddingsActive && state.hasEmbeddings;
    }

    public int rankingPolicyVersion() {
        return rankingPolicy.version();
    }

    public void markStale() {
        semanticCompatible = false;
        embeddingsActive = false;
        clearHighlightCache();
    }

    public boolean semanticModelConfigured() {
        return embeddingProvider.isConfigured();
    }

    public String semanticStatus() {
        if (!embeddingProvider.isConfigured()) return "未安装语义模型";
        if (!state.hasEmbeddings) return "模型已安装，需重建索引";
        refreshSemanticCompatibility();
        if (!semanticCompatible) return "索引向量与当前模型不兼容，需重建";
        return embeddingProvider.status();
    }

    private void refreshSemanticCompatibility() {
        if (manifest == null) return;
        boolean compatible = state.hasEmbeddings && isSemanticCompatible(snapshot());
        semanticCompatible = compatible;
        if (!compatible) embeddingsActive = false;
    }

    private boolean isSemanticCompatible(IndexSnapshot snapshot) {
        return state.hasEmbeddings && semanticScorer.compatible(embeddingProvider, snapshot.manifest(),
                state.documents.stream().map(RetrievalDocument::chunk).toList());
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("索引构建已取消");
        }
    }

    /**
     * Also drops cached query embeddings. They are keyed on query text alone, so a rebuild that
     * swapped the embedding model would otherwise keep serving vectors from the previous one.
     */
    private synchronized void clearHighlightCache() {
        highlightService.clear();
        queryEmbeddingCache.clear();
    }

    private synchronized float[] semanticQuery(String query) throws IOException {
        float[] cached = queryEmbeddingCache.get(query);
        if (cached != null) return cached;
        float[] embedding = embeddingProvider.embed(List.of(query)).get(0);
        queryEmbeddingCache.put(query, embedding);
        return embedding;
    }

    public record IndexProgress(int processed, int total, Path currentFile, String stage) {
    }

    public record IndexReport(int files, int chunks, int skipped, List<IndexBuildWarning> warnings,
                              Map<String, Long> stageMillis) {
        public IndexReport {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            stageMillis = stageMillis == null ? Map.of() : Map.copyOf(stageMillis);
        }

        public IndexReport(int files, int chunks, int skipped, List<IndexBuildWarning> warnings) {
            this(files, chunks, skipped, warnings, Map.of());
        }

        public IndexReport(int files, int chunks, int skipped) {
            this(files, chunks, skipped, List.of(), Map.of());
        }
    }

    private record State(List<RetrievalDocument> documents, Map<String, Integer> documentFrequency,
                         boolean hasEmbeddings) {
        static State empty() {
            return new State(List.of(), Map.of(), false);
        }

        static State build(List<DocumentChunk> source, LexicalFeatureExtractor features) {
            List<Map<String, Double>> tokenMaps = source.stream()
                    .map(chunk -> features.weightedTokens(chunk.fileName() + "\n"
                            + chunk.sourceLocation() + "\n" + chunk.content(), false)).toList();
            Map<String, Integer> df = new HashMap<>();
            tokenMaps.forEach(tokens -> new HashSet<>(tokens.keySet())
                    .forEach(token -> df.merge(token, 1, Integer::sum)));
            List<RetrievalDocument> indexed = new ArrayList<>(source.size());
            for (int i = 0; i < source.size(); i++) {
                Map<String, Double> tokens = tokenMaps.get(i);
                Map<String, Double> vector = features.tfIdf(tokens, df, source.size());
                double length = tokens.values().stream().mapToDouble(Double::doubleValue).sum();
                indexed.add(new RetrievalDocument(source.get(i), tokens, vector,
                        features.norm(vector), Math.max(1.0, length)));
            }
            boolean hasEmbeddings = source.stream().anyMatch(DocumentChunk::hasEmbedding);
            return new State(List.copyOf(indexed), Map.copyOf(df), hasEmbeddings);
        }
    }
}
