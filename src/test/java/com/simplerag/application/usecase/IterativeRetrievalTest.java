package com.simplerag.application.usecase;

import com.simplerag.application.conversation.ChatMessage;
import com.simplerag.application.conversation.ChatRequest;
import com.simplerag.application.conversation.RetrievalDecision;
import com.simplerag.application.conversation.RetrievalPlanRequest;
import com.simplerag.application.port.out.ChatModel;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.RagAnswer;
import com.simplerag.model.RagCitation;
import com.simplerag.model.SearchResult;
import com.simplerag.rag.ApiConfig;
import com.simplerag.search.RetrievalStrategy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IterativeRetrievalTest {
    private static final ApiConfig CONFIG = new ApiConfig("https://example.test/v1", "key", "model");

    @Test
    void modelCanAdaptivelySearchSeveralTimesAndScopesAreRepublished() throws Exception {
        PlanningChat chat = new PlanningChat(List.of(
                RetrievalDecision.search("beta implementation"),
                RetrievalDecision.search("gamma callers"),
                RetrievalDecision.answer()));
        IterativeRetrieval retrieval = new IterativeRetrieval(chat);
        List<String> queries = new ArrayList<>();
        List<Integer> publishedSizes = new ArrayList<>();
        AtomicInteger freshnessChecks = new AtomicInteger();

        IterativeRetrieval.Result result = retrieval.collect("kb", 7L, "how does it work?", List.of(), CONFIG,
                (query, strategy) -> {
                    queries.add(query);
                    if (query.startsWith("beta")) return List.of(hit("c2", 0.9), hit("c3", 0.8));
                    if (query.startsWith("gamma")) return List.of(hit("c4", 0.7));
                    return List.of(hit("c1", 1.0), hit("c2", 0.95));
                }, scope -> publishedSizes.add(scope.size()), freshnessChecks::incrementAndGet);

        assertEquals(List.of("how does it work?", "beta implementation", "gamma callers"), queries);
        assertEquals(List.of(2, 3, 4), publishedSizes);
        assertEquals(3, freshnessChecks.get());
        assertEquals(List.of("c1", "c2", "c3", "c4"),
                result.citations().stream().map(citation -> citation.chunk().id()).toList());
        assertEquals(List.of(1, 2, 3, 4), result.citations().stream().map(RagCitation::number).toList());
        assertEquals(List.of(2, 3, 4), chat.evidenceSizes);
    }

    @Test
    void severalGeneratedQueriesRunInOneRoundBeforeTheNextPlanningCall() throws Exception {
        // Two independent facets must not cost two planning round trips.
        PlanningChat chat = new PlanningChat(List.of(
                RetrievalDecision.search(List.of("QueryAnalyzer semanticText", "BOILERPLATE pattern")),
                RetrievalDecision.answer()));
        IterativeRetrieval retrieval = new IterativeRetrieval(chat);
        List<String> queries = new ArrayList<>();

        IterativeRetrieval.Result result = retrieval.collect("kb", 1L, "how is the query cleaned?", List.of(), CONFIG,
                (query, strategy) -> {
                    queries.add(query);
                    if (query.startsWith("QueryAnalyzer")) return List.of(hit("c2", 0.9));
                    if (query.startsWith("BOILERPLATE")) return List.of(hit("c3", 0.8));
                    return List.of(hit("c1", 1.0));
                }, ignored -> { }, () -> { });

        assertEquals(List.of("how is the query cleaned?", "QueryAnalyzer semanticText", "BOILERPLATE pattern"),
                queries);
        assertEquals(List.of("c1", "c2", "c3"),
                result.citations().stream().map(citation -> citation.chunk().id()).toList());
        // Both queries ran inside a single planning round, so the planner was consulted twice overall.
        assertEquals(2, chat.evidenceSizes.size());
    }

    @Test
    void aPronounOnlyFollowUpIsResolvedAgainstThePreviousQuestionBeforeTheFirstSearch() throws Exception {
        // The planner cannot help here: it only runs after the user authorises the send, so a bare
        // "它在哪？" would otherwise be sent to the index verbatim and match nothing.
        IterativeRetrieval retrieval = new IterativeRetrieval(new PlanningChat(List.of(RetrievalDecision.answer())));
        List<String> queries = new ArrayList<>();
        List<ChatMessage> history = List.of(
                ChatMessage.user("FreshnessGate 是怎么校验索引版本的？"),
                ChatMessage.assistant("它在 requireFresh 中比较 sourceRevision。"));

        retrieval.collect("kb", 1L, "它在哪？", history, CONFIG,
                (query, strategy) -> {
                    queries.add(query);
                    return List.of(hit("c1", 0.9));
                }, ignored -> { }, () -> { });

        assertEquals(1, queries.size());
        assertTrue(queries.get(0).contains("FreshnessGate"),
                "the follow-up should carry the subject of the previous turn");
        assertTrue(queries.get(0).contains("它在哪？"));
    }

    @Test
    void aSelfContainedQuestionIsSearchedVerbatim() throws Exception {
        IterativeRetrieval retrieval = new IterativeRetrieval(new PlanningChat(List.of(RetrievalDecision.answer())));
        List<String> queries = new ArrayList<>();
        String question = "FreshnessGate.requireFresh 在哪个类里抛出 StaleTaskException？";

        retrieval.collect("kb", 1L, question,
                List.of(ChatMessage.user("索引版本怎么校验？")), CONFIG,
                (query, strategy) -> {
                    queries.add(query);
                    return List.of(hit("c1", 0.9));
                }, ignored -> { }, () -> { });

        assertEquals(List.of(question), queries);
    }

    @Test
    void anUnusablePlannerDecisionIsSurfacedSeparatelyFromADeliberateAnswer() throws Exception {
        IterativeRetrieval retrieval = new IterativeRetrieval(
                new PlanningChat(List.of(RetrievalDecision.unavailable())));

        IterativeRetrieval.Result unusable = retrieval.collect("kb", 1L, "q", List.of(), CONFIG,
                (query, strategy) -> List.of(hit("c1", 0.9)), ignored -> { }, () -> { });
        IterativeRetrieval.Result satisfied = new IterativeRetrieval(
                new PlanningChat(List.of(RetrievalDecision.answer())))
                .collect("kb", 1L, "q", List.of(), CONFIG,
                        (query, strategy) -> List.of(hit("c1", 0.9)), ignored -> { }, () -> { });

        assertTrue(unusable.plannerUnavailable());
        assertFalse(satisfied.plannerUnavailable());
    }

    @Test
    void retrievalStopsAtCitationBudgetEvenWhenModelKeepsSearching() throws Exception {
        ChatModel chat = new PlanningChat(List.of(
                RetrievalDecision.search("follow up 1"),
                RetrievalDecision.search("follow up 2"),
                RetrievalDecision.search("follow up 3")));
        IterativeRetrieval retrieval = new IterativeRetrieval(chat);
        AtomicInteger searchNumber = new AtomicInteger();

        IterativeRetrieval.Result result = retrieval.collect("kb", 1L, "initial", List.of(), CONFIG,
                (query, strategy) -> {
                    int batch = searchNumber.getAndIncrement();
                    List<SearchResult> hits = new ArrayList<>();
                    for (int i = 0; i < 8; i++) hits.add(hit("b" + batch + "-" + i, 1.0 - i / 10.0));
                    return hits;
                }, ignored -> { }, () -> { });

        assertEquals(12, result.citations().size());
        assertEquals(3, searchNumber.get());
    }

    @Test
    void anUnreachablePlannerFallsBackToTheEvidenceAlreadyCollected() throws Exception {
        // The exact failure reported from the field: the peer cut the TLS stream mid-record.
        ChatModel chat = new UnreachablePlanner(
                new IOException("BUFFER_UNDERFLOW with EOF, 11248 bytes non decrypted."));
        IterativeRetrieval retrieval = new IterativeRetrieval(chat);
        AtomicInteger searches = new AtomicInteger();

        IterativeRetrieval.Result result = retrieval.collect("kb", 3L, "where are credentials stored?", List.of(), CONFIG,
                (query, strategy) -> {
                    searches.incrementAndGet();
                    return List.of(hit("c1", 0.9), hit("c2", 0.8));
                }, ignored -> { }, () -> { });

        assertEquals(List.of("c1", "c2"), result.citations().stream().map(citation -> citation.chunk().id()).toList());
        assertEquals(1, searches.get());
    }

    @Test
    void aHypotheticalQueryIsRetrievedByVectorSimilarityAlone() throws Exception {
        // A HyDE pseudo-document is invented prose; fed to BM25 it would match its own filler words.
        PlanningChat chat = new PlanningChat(List.of(
                RetrievalDecision.plan(List.of(
                        new RetrievalDecision.TypedQuery("max_retries config key",
                                RetrievalDecision.QueryMode.KEYWORD),
                        new RetrievalDecision.TypedQuery(
                                "Retry behaviour is controlled by the max_retries key, default 3.",
                                RetrievalDecision.QueryMode.HYPOTHETICAL))),
                RetrievalDecision.answer()));
        IterativeRetrieval retrieval = new IterativeRetrieval(chat);
        List<String> queries = new ArrayList<>();
        List<RetrievalStrategy> strategies = new ArrayList<>();

        retrieval.collect("kb", 1L, "how does retry work?", List.of(), CONFIG,
                (query, strategy) -> {
                    queries.add(query);
                    strategies.add(strategy);
                    return List.of(hit("c" + queries.size(), 0.8));
                }, ignored -> { }, () -> { });

        assertEquals(3, queries.size());
        assertEquals(List.of(RetrievalStrategy.RRF_RERANK, RetrievalStrategy.RRF_RERANK,
                RetrievalStrategy.DENSE), strategies);
    }

    @Test
    void thePlannerIsToldWhetherVectorRetrievalIsAvailable() throws Exception {
        PlanningChat withVectors = new PlanningChat(List.of(RetrievalDecision.answer()));
        PlanningChat withoutVectors = new PlanningChat(List.of(RetrievalDecision.answer()));

        new IterativeRetrieval(withVectors).collect("kb", 1L, "q", List.of(), CONFIG,
                (query, strategy) -> List.of(hit("c1", 0.9)), ignored -> { }, () -> { });
        new IterativeRetrieval(withoutVectors).collect("kb", 1L, "q", List.of(), CONFIG,
                (query, strategy) -> List.of(hit("c1", 0.9)), ignored -> { }, () -> { }, false);

        // Asking for a pseudo-document that can only be matched by vectors we do not have wastes a round.
        assertEquals(List.of(true), withVectors.semanticFlags);
        assertEquals(List.of(false), withoutVectors.semanticFlags);
    }

    private static SearchResult hit(String id, double score) {
        DocumentChunk chunk = new DocumentChunk(id, "docs/" + id + ".md", "docs", id + ".md", ".md",
                1, 3, "content for " + id, 1L, null);
        return new SearchResult(chunk, score, "test");
    }

    private static final class UnreachablePlanner implements ChatModel {
        private final IOException failure;

        private UnreachablePlanner(IOException failure) {
            this.failure = failure;
        }

        @Override public List<String> listModels(ApiConfig config) { return List.of("model"); }
        @Override public RagAnswer answer(ApiConfig config, ChatRequest request) {
            return new RagAnswer("answer", request.citations(), config.model());
        }
        @Override public RagAnswer answerStream(ApiConfig config, ChatRequest request, Consumer<String> onDelta) {
            return answer(config, request);
        }
        @Override public RetrievalDecision planRetrieval(ApiConfig config, RetrievalPlanRequest request)
                throws IOException {
            throw failure;
        }
    }

    private static final class PlanningChat implements ChatModel {
        private final List<RetrievalDecision> decisions;
        private final List<Integer> evidenceSizes = new ArrayList<>();
        private final List<Boolean> semanticFlags = new ArrayList<>();
        private int next;

        private PlanningChat(List<RetrievalDecision> decisions) {
            this.decisions = decisions;
        }

        @Override public List<String> listModels(ApiConfig config) { return List.of("model"); }
        @Override public RagAnswer answer(ApiConfig config, ChatRequest request) {
            return new RagAnswer("answer", request.citations(), config.model());
        }
        @Override public RagAnswer answerStream(ApiConfig config, ChatRequest request, Consumer<String> onDelta) {
            return answer(config, request);
        }
        @Override public RetrievalDecision planRetrieval(ApiConfig config, RetrievalPlanRequest request) {
            evidenceSizes.add(request.citations().size());
            semanticFlags.add(request.semanticRetrievalAvailable());
            return next < decisions.size() ? decisions.get(next++) : RetrievalDecision.answer();
        }
    }
}