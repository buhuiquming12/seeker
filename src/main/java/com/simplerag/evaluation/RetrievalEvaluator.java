package com.simplerag.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.SearchResult;
import com.simplerag.search.QueryDecomposer;
import com.simplerag.search.QueryFusion;
import com.simplerag.search.RetrievalStrategy;
import com.simplerag.search.SemanticSearchEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class RetrievalEvaluator {
    /** Ablation row name for "RRF_RERANK, but with the query decomposed and the rankings fused". */
    public static final String QUERY_OPTIMIZATION = "QUERY_OPTIMIZATION";

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public RetrievalEvaluationReport evaluate(RetrievalEvaluationDataset dataset,
                                               SemanticSearchEngine engine,
                                               double indexingMillis) {
        return evaluate(dataset, engine, indexingMillis, RetrievalStrategy.RRF_RERANK);
    }

    public RetrievalEvaluationReport evaluate(RetrievalEvaluationDataset dataset,
                                               SemanticSearchEngine engine,
                                               double indexingMillis,
                                               RetrievalStrategy strategy) {
        return measure(dataset, engine, indexingMillis, strategy.name(),
                query -> engine.search(query, 20, "全部", strategy));
    }

    /**
     * Scores the same dataset with query decomposition and cross-query fusion in front of retrieval.
     *
     * <p>Deliberately retrieves through {@code engine.search} rather than {@code searchContext}, even
     * though the ask path uses the latter: {@code searchContext} also runs MMR and stitches adjacent
     * chunks into synthetic passages, which changes {@code content()} and would make the nDCG passage
     * match measure something different from the other rows. This row differs from
     * {@code RRF_RERANK} in exactly one respect — the query is decomposed and the rankings fused.
     */
    public RetrievalEvaluationReport evaluateWithQueryOptimization(RetrievalEvaluationDataset dataset,
                                                                   SemanticSearchEngine engine,
                                                                   double indexingMillis) {
        QueryDecomposer decomposer = new QueryDecomposer();
        return measure(dataset, engine, indexingMillis, QUERY_OPTIMIZATION,
                query -> QueryFusion.fuse(decomposer.decompose(query),
                        facet -> engine.search(facet, 20, "全部", RetrievalStrategy.RRF_RERANK), 20));
    }

    private RetrievalEvaluationReport measure(RetrievalEvaluationDataset dataset,
                                               SemanticSearchEngine engine,
                                               double indexingMillis,
                                               String label,
                                               Function<String, List<SearchResult>> retrieve) {
        List<RetrievalEvaluationReport.CaseResult> results = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        Map<String, MutableMetrics> categories = new LinkedHashMap<>();
        double recall5 = 0, recall20 = 0, hit5 = 0, precision5 = 0, mrr = 0, ndcg = 0;
        double uniqueness = 0, coldNanos = 0, cachedNanos = 0;
        for (RetrievalEvaluationCase item : dataset.cases()) {
            long started = System.nanoTime();
            List<SearchResult> ranked = retrieve.apply(item.query());
            long cold = System.nanoTime() - started;
            coldNanos += cold;
            started = System.nanoTime();
            retrieve.apply(item.query());
            long cached = System.nanoTime() - started;
            cachedNanos += cached;
            latencies.add(cached);

            List<String> documents = ranked.stream().map(result -> result.chunk().fileName()).toList();
            List<String> uniqueDocuments = documents.stream().distinct().toList();
            double caseRecall5 = recallAt(uniqueDocuments, item.expectedDocuments(), 5, item.isAnswerable());
            double caseRecall20 = recallAt(uniqueDocuments, item.expectedDocuments(), 20, item.isAnswerable());
            double caseHit5 = hitRateAt(uniqueDocuments, item.expectedDocuments(), 5, item.isAnswerable());
            double casePrecision5 = precisionAt(uniqueDocuments, item.expectedDocuments(), 5, item.isAnswerable());
            double caseMrr = reciprocalRank(uniqueDocuments, item.expectedDocuments(), 10);
            double caseNdcg = ndcgAt(distinctDocuments(ranked), item, 10);
            boolean forbidden = uniqueDocuments.stream().limit(10).anyMatch(item.mustNotReturn()::contains);
            int positiveRank = firstRank(uniqueDocuments, item.expectedDocuments());
            int hardNegativeRank = firstRank(uniqueDocuments, item.hardNegatives());
            boolean hardNegative = hardNegativeRank >= 0 && (positiveRank < 0 || hardNegativeRank < positiveRank);
            double caseUniqueness = documents.isEmpty() ? 1.0
                    : uniqueDocuments.stream().limit(10).count() / (double) Math.min(10, documents.size());

            recall5 += caseRecall5;
            recall20 += caseRecall20;
            hit5 += caseHit5;
            precision5 += casePrecision5;
            mrr += caseMrr;
            ndcg += caseNdcg;
            uniqueness += caseUniqueness;
            categories.computeIfAbsent(item.category(), ignored -> new MutableMetrics())
                    .add(caseRecall5, caseHit5, caseMrr, caseNdcg);
            results.add(new RetrievalEvaluationReport.CaseResult(item.id(), item.split(), item.category(),
                    caseRecall5, caseRecall20, caseHit5, casePrecision5, caseMrr, caseNdcg,
                    forbidden, hardNegative, caseUniqueness, documents));
        }
        int count = dataset.cases().size();
        Map<String, RetrievalEvaluationReport.AggregateMetrics> categoryReport = new LinkedHashMap<>();
        categories.forEach((name, value) -> categoryReport.put(name, value.toReport()));
        return new RetrievalEvaluationReport(dataset.name(), dataset.version(),
                engine.rankingPolicyVersion(), label, Instant.now().toString(), count,
                recall5 / count, recall20 / count, hit5 / count, precision5 / count,
                mrr / count, ndcg / count, uniqueness / count,
                nanosToMillis(coldNanos / count), nanosToMillis(cachedNanos / count),
                percentileMillis(latencies, 0.50), percentileMillis(latencies, 0.95), indexingMillis,
                estimateMemoryPerThousandChunks(engine.snapshot().chunks()), categoryReport, results);
    }

    public RetrievalAblationReport evaluateAblations(RetrievalEvaluationDataset dataset,
                                                      SemanticSearchEngine engine,
                                                      double indexingMillis) {
        Map<String, RetrievalEvaluationReport> reports = new LinkedHashMap<>();
        for (RetrievalStrategy strategy : RetrievalStrategy.values()) {
            if (strategy == RetrievalStrategy.DENSE && !engine.semanticEnabled()) continue;
            reports.put(strategy.name(), evaluate(dataset, engine, indexingMillis, strategy));
        }
        // Same corpus, same model, same strategy as RRF_RERANK — only the query handling differs, so
        // the delta between these two rows is the query optimisation's contribution and nothing else.
        reports.put(QUERY_OPTIMIZATION, evaluateWithQueryOptimization(dataset, engine, indexingMillis));
        return new RetrievalAblationReport(dataset.name(), dataset.version(), Instant.now().toString(), reports);
    }

    public void save(RetrievalEvaluationReport report, Path output) throws IOException {
        saveObject(report, output);
    }

    public void save(RetrievalAblationReport report, Path output) throws IOException {
        saveObject(report, output);
    }

    private void saveObject(Object report, Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        JSON.writeValue(output.toFile(), report);
    }

    public void verifyThresholds(RetrievalEvaluationDataset dataset, RetrievalEvaluationReport report) {
        List<String> failures = new ArrayList<>();
        if (report.recallAt5() < dataset.minimumRecallAt5()) failures.add("Recall@5");
        if (report.recallAt20() < dataset.minimumRecallAt20()) failures.add("Recall@20");
        if (report.hitRateAt5() < dataset.minimumHitRateAt5()) failures.add("HitRate@5");
        if (report.mrrAt10() < dataset.minimumMrrAt10()) failures.add("MRR@10");
        if (report.ndcgAt10() < dataset.minimumNdcgAt10()) failures.add("nDCG@10");
        if (report.cases().stream().anyMatch(RetrievalEvaluationReport.CaseResult::forbiddenResultReturned))
            failures.add("mustNotReturn");
        if (report.cases().stream().anyMatch(RetrievalEvaluationReport.CaseResult::hardNegativeOutrankedPositive))
            failures.add("hardNegativeOutrankedPositive");
        if (!failures.isEmpty()) throw new IllegalStateException("检索质量门禁失败: " + String.join(", ", failures));
    }

    private static double recallAt(List<String> ranked, List<String> expected, int limit, boolean answerable) {
        if (!answerable) return ranked.isEmpty() ? 1 : 0;
        Set<String> hits = new HashSet<>(ranked.subList(0, Math.min(limit, ranked.size())));
        return expected.stream().filter(hits::contains).count() / (double) expected.size();
    }

    private static double hitRateAt(List<String> ranked, List<String> expected, int limit, boolean answerable) {
        if (!answerable) return ranked.isEmpty() ? 1 : 0;
        return ranked.stream().limit(limit).anyMatch(expected::contains) ? 1 : 0;
    }

    private static double precisionAt(List<String> ranked, List<String> expected, int limit, boolean answerable) {
        if (!answerable) return ranked.isEmpty() ? 1 : 0;
        int denominator = Math.min(limit, ranked.size());
        if (denominator == 0) return 0;
        long relevant = ranked.stream().limit(limit).filter(expected::contains).count();
        return relevant / (double) denominator;
    }

    private static int firstRank(List<String> ranked, List<String> expected) {
        for (int index = 0; index < ranked.size(); index++) {
            if (expected.contains(ranked.get(index))) return index;
        }
        return -1;
    }

    private static double reciprocalRank(List<String> ranked, List<String> expected, int limit) {
        for (int i = 0; i < Math.min(limit, ranked.size()); i++) {
            if (expected.contains(ranked.get(i))) return 1.0 / (i + 1);
        }
        return 0;
    }

    private static List<SearchResult> distinctDocuments(List<SearchResult> ranked) {
        Set<String> seen = new HashSet<>();
        return ranked.stream().filter(result -> seen.add(result.chunk().fileName())).toList();
    }

    private static double ndcgAt(List<SearchResult> ranked, RetrievalEvaluationCase item, int limit) {
        if (!item.isAnswerable()) return ranked.isEmpty() ? 1 : 0;
        double dcg = 0;
        for (int i = 0; i < Math.min(limit, ranked.size()); i++) {
            int relevance = relevance(ranked.get(i).chunk(), item);
            dcg += (Math.pow(2, relevance) - 1) / log2(i + 2);
        }
        int passageMatches = item.expectedPassages().isEmpty() ? 0 : 1;
        List<Integer> ideal = new ArrayList<>();
        for (int i = 0; i < item.expectedDocuments().size(); i++) ideal.add(i < passageMatches ? 2 : 1);
        ideal.sort(java.util.Comparator.reverseOrder());
        double idcg = 0;
        for (int i = 0; i < Math.min(limit, ideal.size()); i++)
            idcg += (Math.pow(2, ideal.get(i)) - 1) / log2(i + 2);
        return idcg == 0 ? 0 : Math.min(1.0, dcg / idcg);
    }

    private static int relevance(DocumentChunk chunk, RetrievalEvaluationCase item) {
        if (!item.expectedDocuments().contains(chunk.fileName())) return 0;
        String content = chunk.content().toLowerCase(Locale.ROOT);
        boolean passage = item.expectedPassages().stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(content::contains);
        return passage ? 2 : 1;
    }

    private static double percentileMillis(List<Long> nanos, double percentile) {
        if (nanos.isEmpty()) return 0;
        List<Long> sorted = nanos.stream().sorted().toList();
        int index = Math.min(sorted.size() - 1, Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1));
        return nanosToMillis(sorted.get(index));
    }

    private static double estimateMemoryPerThousandChunks(List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) return 0;
        long bytes = 0;
        for (DocumentChunk chunk : chunks) {
            bytes += (long) chunk.content().length() * 2;
            bytes += (long) chunk.id().length() * 2 + (long) chunk.path().length() * 2 + 64;
            if (chunk.embedding() != null) bytes += (long) chunk.embedding().length * Float.BYTES;
        }
        return bytes * 1000.0 / chunks.size();
    }

    private static double log2(double value) { return Math.log(value) / Math.log(2); }
    private static double nanosToMillis(double nanos) { return nanos / 1_000_000.0; }

    private static final class MutableMetrics {
        private int count;
        private double recall;
        private double hit;
        private double mrr;
        private double ndcg;

        void add(double recall, double hit, double mrr, double ndcg) {
            count++;
            this.recall += recall;
            this.hit += hit;
            this.mrr += mrr;
            this.ndcg += ndcg;
        }

        RetrievalEvaluationReport.AggregateMetrics toReport() {
            return new RetrievalEvaluationReport.AggregateMetrics(count, recall / count, hit / count,
                    mrr / count, ndcg / count);
        }
    }
}
