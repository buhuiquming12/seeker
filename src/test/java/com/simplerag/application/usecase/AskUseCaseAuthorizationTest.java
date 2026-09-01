package com.simplerag.application.usecase;

import com.simplerag.adapter.out.sqlite.AppRepository;
import com.simplerag.adapter.out.sqlite.DatabaseManager;
import com.simplerag.application.conversation.ChatRequest;
import com.simplerag.application.conversation.RetrievalDecision;
import com.simplerag.application.conversation.RetrievalPlanRequest;
import com.simplerag.application.dto.AskResultView;
import com.simplerag.application.dto.RemoteSendReview;
import com.simplerag.application.freshness.FreshnessGate;
import com.simplerag.application.port.out.ChatModel;
import com.simplerag.application.port.out.SourceFreshnessMonitor;
import com.simplerag.application.freshness.SourceFingerprint;
import com.simplerag.application.runtime.ActiveKnowledgeContext;
import com.simplerag.application.runtime.ActiveKnowledgeRuntime;
import com.simplerag.application.runtime.IndexLifecycle;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.RagAnswer;
import com.simplerag.rag.ApiConfig;
import com.simplerag.search.IndexHandle;
import com.simplerag.search.IndexIdentity;
import com.simplerag.search.IndexManifest;
import com.simplerag.search.IndexSnapshot;
import com.simplerag.search.SemanticSearchEngine;
import com.simplerag.application.port.out.TextEmbedder;
import com.simplerag.support.ImmediateFreshnessMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The adaptive retrieval loop republishes its citation scope every time it widens. Authorization must
 * still be asked exactly once per turn: re-prompting blocked the worker thread between remote calls,
 * which let the pooled TLS connection go stale mid-turn.
 */
class AskUseCaseAuthorizationTest {
    private static final ApiConfig CONFIG = new ApiConfig("http://127.0.0.1:1/v1", "key", "model");

    @TempDir
    Path temporaryDirectory;

    @Test
    void authorizationIsRequestedOnceEvenWhenRetrievalWidensTheScope() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Files.writeString(source.resolve("credentials.txt"),
                "database credentials must be stored in environment variables");
        Files.writeString(source.resolve("sidebar.txt"),
                "collapse the sidebar to make room for the editor");

        AppRepository repository = new AppRepository(new DatabaseManager(temporaryDirectory.resolve("ask.db")));
        KnowledgeBase ready = publishReadyKnowledgeBase(repository, source);
        long revision = ready.sourceRevision();

        SemanticSearchEngine engine = new SemanticSearchEngine(new LexicalOnlyEmbedder());
        engine.index(List.of(source), null);
        ActiveKnowledgeRuntime runtime = new ActiveKnowledgeRuntime(new IndexLifecycle());
        ImmediateFreshnessMonitor monitor = verifiedMonitor(ready, source);
        runtime.restore(new ActiveKnowledgeContext(ready, monitor.snapshot(),
                new IndexHandle(ready.id(), revision, ready.indexStatus(), engine)));

        ScriptedPlanner chat = new ScriptedPlanner(List.of(
                RetrievalDecision.search("sidebar editor"), RetrievalDecision.answer()));
        AskUseCase ask = new AskUseCase(runtime, repository, new FreshnessGate(monitor), chat, repository, null);

        List<Integer> publishedScopes = new ArrayList<>();
        AtomicInteger authorizations = new AtomicInteger();
        List<RemoteSendReview> reviews = new ArrayList<>();

        AskResultView result = ask.askStream(ready.id(), revision, "credentials", List.of(), CONFIG,
                views -> publishedScopes.add(views.size()),
                review -> {
                    authorizations.incrementAndGet();
                    reviews.add(review);
                    return true;
                },
                delta -> { });

        assertTrue(publishedScopes.size() > 1,
                "retrieval should republish a widened scope, otherwise this proves nothing: " + publishedScopes);
        assertEquals(1, authorizations.get(), "authorization must be asked once per turn");
        assertEquals(IterativeRetrieval.MAX_SEARCHES, reviews.get(0).maxSearches());
        assertEquals(IterativeRetrieval.MAX_CITATIONS, reviews.get(0).maxCitations());
        assertEquals("answer", result.text());
        assertTrue(result.citations().size() > publishedScopes.get(0),
                "the final answer should cite the widened scope");
    }

    private KnowledgeBase publishReadyKnowledgeBase(AppRepository repository, Path source) {
        KnowledgeBase created = repository.createKnowledgeBase("kb", "");
        repository.addSource(created.id(), source);
        long revision = repository.findKnowledgeBase(created.id()).orElseThrow().sourceRevision();
        assertTrue(repository.beginIndexBuild(created.id(), revision));
        IndexManifest manifest = new IndexManifest(created.id(), revision,
                IndexIdentity.sourceSetHash(List.of(source)), "lexical", 0, IndexIdentity.CHUNKING_VERSION,
                IndexSnapshot.CURRENT_VERSION, System.currentTimeMillis());
        assertTrue(repository.publishIndex(manifest, revision + ".bin"));
        return repository.findKnowledgeBase(created.id()).orElseThrow();
    }

    private static ImmediateFreshnessMonitor verifiedMonitor(KnowledgeBase knowledgeBase, Path source) {
        ImmediateFreshnessMonitor monitor = new ImmediateFreshnessMonitor();
        monitor.start(new SourceFreshnessMonitor.MonitorRequest(knowledgeBase.id(), knowledgeBase.sourceRevision(),
                List.of(source), knowledgeBase.lastVerifiedSourceHash()), new SourceFreshnessMonitor.Listener() {
            @Override public void sourceVerified(SourceFreshnessMonitor.MonitorRequest request,
                                                 SourceFingerprint fingerprint) { }
            @Override public void sourceInvalidated(SourceFreshnessMonitor.MonitorRequest request,
                                                   SourceFingerprint fingerprint, String reason) { }
        });
        return monitor;
    }

    /** Lexical-only index: reporting "not configured" keeps embeddings out of the fixture entirely. */
    private static final class LexicalOnlyEmbedder implements TextEmbedder {
        @Override public boolean isConfigured() { return false; }
        @Override public List<float[]> embed(List<String> texts) { return List.of(); }
        @Override public String modelName() { return "lexical"; }
        @Override public String status() { return "lexical only"; }
        @Override public void close() { }
    }

    private static final class ScriptedPlanner implements ChatModel {
        private final List<RetrievalDecision> decisions;
        private int next;

        private ScriptedPlanner(List<RetrievalDecision> decisions) {
            this.decisions = decisions;
        }

        @Override public List<String> listModels(ApiConfig config) { return List.of("model"); }
        @Override public RagAnswer answer(ApiConfig config, ChatRequest request) {
            return new RagAnswer("answer", request.citations(), config.model());
        }
        @Override public RagAnswer answerStream(ApiConfig config, ChatRequest request,
                                                Consumer<com.simplerag.application.conversation.AnswerDelta> onDelta) {
            return answer(config, request);
        }
        @Override public RetrievalDecision planRetrieval(ApiConfig config, RetrievalPlanRequest request)
                throws IOException {
            return next < decisions.size() ? decisions.get(next++) : RetrievalDecision.answer();
        }
    }
}
