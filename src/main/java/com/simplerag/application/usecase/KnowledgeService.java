package com.simplerag.application.usecase;

import com.simplerag.application.port.in.AskKnowledge;
import com.simplerag.application.port.in.DesktopReadModel;
import com.simplerag.application.port.in.ManageApiSettings;
import com.simplerag.application.port.in.ManageKnowledgeBases;
import com.simplerag.application.port.in.ManageKnowledgeSources;
import com.simplerag.application.port.in.RebuildKnowledgeIndex;
import com.simplerag.application.port.in.SearchKnowledge;
import com.simplerag.application.port.in.RemoteSendAuthorizer;
import com.simplerag.application.port.out.ChatModel;
import com.simplerag.application.port.out.IndexRepository;
import com.simplerag.application.port.out.KnowledgeBaseRepository;
import com.simplerag.application.port.out.KnowledgeSourceRepository;
import com.simplerag.application.port.out.IndexPublicationRepository;
import com.simplerag.application.port.out.FreshnessRepository;
import com.simplerag.application.port.out.SecretStore;
import com.simplerag.application.port.out.SettingsRepository;
import com.simplerag.application.port.out.SourceFreshnessMonitor;
import com.simplerag.application.port.out.TextEmbedder;
import com.simplerag.application.freshness.FreshnessGate;
import com.simplerag.application.freshness.FreshnessSnapshot;
import com.simplerag.application.freshness.FreshnessState;
import com.simplerag.application.freshness.SourceFingerprint;
import com.simplerag.application.runtime.ActiveKnowledgeContext;
import com.simplerag.application.runtime.ActiveKnowledgeRuntime;
import com.simplerag.application.runtime.IndexLifecycle;
import com.simplerag.application.conversation.AnswerDelta;
import com.simplerag.application.conversation.ChatMessage;
import com.simplerag.application.conversation.ChatRequest;
import com.simplerag.application.dto.AskResultView;
import com.simplerag.application.dto.CitationView;
import com.simplerag.application.dto.DocumentReference;
import com.simplerag.application.dto.IndexBuildProgress;
import com.simplerag.application.dto.IndexBuildResult;
import com.simplerag.application.dto.IndexBuildWarningView;
import com.simplerag.application.dto.RemoteSendReview;
import com.simplerag.application.diagnostics.DiagnosticSink;
import com.simplerag.application.dto.SearchResultView;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.KnowledgeStats;
import com.simplerag.model.IndexStatus;
import com.simplerag.model.RagAnswer;
import com.simplerag.model.RagCitation;
import com.simplerag.model.SearchResult;
import com.simplerag.model.SemanticHighlight;
import com.simplerag.rag.ApiConfig;
import com.simplerag.rag.ModelApiConfig;
import com.simplerag.search.IndexSnapshot;
import com.simplerag.search.IndexBuildRequest;
import com.simplerag.search.IndexHandle;
import com.simplerag.search.IndexIdentity;
import com.simplerag.search.IndexManifest;
import com.simplerag.search.SemanticSearchEngine;
import com.simplerag.search.SecondStageReranker;
import com.simplerag.search.FeatureReranker;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Application-layer facade for knowledge-base, retrieval and RAG use cases. */
public final class KnowledgeService implements ManageKnowledgeBases, ManageKnowledgeSources,
        RebuildKnowledgeIndex, SearchKnowledge, AskKnowledge, ManageApiSettings, DesktopReadModel, AutoCloseable {
    private static final String ACTIVE_KB = "active_knowledge_base";

    private final TextEmbedder embeddingProvider;
    private final SecondStageReranker reranker;
    private final KnowledgeBaseRepository knowledgeBases;
    private final KnowledgeSourceRepository sources;
    private final IndexPublicationRepository publications;
    private final FreshnessRepository freshnessRecords;
    private final SettingsRepository settings;
    private final ApiSettingsUseCase apiSettings;
    private final ChatModel apiClient;
    private final IndexRepository indexRepository;
    private final SourceFreshnessMonitor freshnessMonitor;
    private final FreshnessGate freshnessGate;
    private final java.util.concurrent.atomic.AtomicLong freshnessEpoch = new java.util.concurrent.atomic.AtomicLong();
    private final ActiveKnowledgeRuntime runtime;
    private final DiagnosticSink diagnostics;

    public KnowledgeService(TextEmbedder embeddingProvider, KnowledgeBaseRepository repository,
                            SettingsRepository settings, SecretStore secretCodec, ChatModel apiClient,
                            IndexRepository indexRepository, SourceFreshnessMonitor freshnessMonitor) {
        this(embeddingProvider, repository, settings, secretCodec, apiClient, indexRepository,
                freshnessMonitor, new ActiveKnowledgeRuntime(new IndexLifecycle()), DiagnosticSink.noop());
    }

    public KnowledgeService(TextEmbedder embeddingProvider, KnowledgeBaseRepository repository,
                            SettingsRepository settings, SecretStore secretCodec, ChatModel apiClient,
                            IndexRepository indexRepository, SourceFreshnessMonitor freshnessMonitor,
                            ActiveKnowledgeRuntime runtime) {
        this(embeddingProvider, repository, settings, secretCodec, apiClient, indexRepository,
                freshnessMonitor, runtime, DiagnosticSink.noop());
    }

    public KnowledgeService(TextEmbedder embeddingProvider, KnowledgeBaseRepository repository,
                            SettingsRepository settings, SecretStore secretCodec, ChatModel apiClient,
                            IndexRepository indexRepository, SourceFreshnessMonitor freshnessMonitor,
                            ActiveKnowledgeRuntime runtime, DiagnosticSink diagnostics) {
        this(embeddingProvider, repository, settings, secretCodec, apiClient, indexRepository,
                freshnessMonitor, runtime, diagnostics, new FeatureReranker());
    }

    public KnowledgeService(TextEmbedder embeddingProvider, KnowledgeBaseRepository repository,
                            SettingsRepository settings, SecretStore secretCodec, ChatModel apiClient,
                            IndexRepository indexRepository, SourceFreshnessMonitor freshnessMonitor,
                            ActiveKnowledgeRuntime runtime, DiagnosticSink diagnostics,
                            SecondStageReranker reranker) {
        this.embeddingProvider = embeddingProvider;
        this.reranker = reranker == null ? new FeatureReranker() : reranker;
        this.knowledgeBases = repository;
        this.sources = repository;
        this.publications = repository;
        this.freshnessRecords = repository;
        this.settings = settings;
        this.apiClient = apiClient;
        this.apiSettings = new ApiSettingsUseCase(settings, secretCodec, apiClient);
        this.indexRepository = indexRepository;
        this.freshnessMonitor = freshnessMonitor;
        this.freshnessGate = new FreshnessGate(freshnessMonitor);
        this.runtime = runtime;
        this.diagnostics = diagnostics == null ? DiagnosticSink.noop() : diagnostics;
    }

    public synchronized boolean restore() {
        List<KnowledgeBase> available = knowledgeBases.listKnowledgeBases();
        if (available.isEmpty()) {
            KnowledgeBase created = knowledgeBases.createKnowledgeBase("我的知识库", "从本地目录建立的个人知识库");
            if (indexRepository.usesDefaultLocation()) migrateLegacyIndex(created);
            available = List.of(created);
        }
        String preferred = settings.getSetting(ACTIVE_KB).orElse(available.get(0).id());
        KnowledgeBase selected = knowledgeBases.findKnowledgeBase(preferred).orElse(available.get(0));
        return loadKnowledgeBase(selected);
    }

    public List<KnowledgeBase> knowledgeBases() {
        return knowledgeBases.listKnowledgeBases();
    }

    public KnowledgeBase currentKnowledgeBase() {
        return runtime.current().knowledgeBase();
    }

    public synchronized KnowledgeBase createKnowledgeBase(String name, String description) {
        KnowledgeBase created = knowledgeBases.createKnowledgeBase(name, description);
        loadKnowledgeBase(created);
        return created;
    }

    public synchronized KnowledgeBase updateCurrentKnowledgeBase(String name, String description) {
        requireActive();
        ActiveKnowledgeContext current = runtime.current();
        KnowledgeBase updated = knowledgeBases.updateKnowledgeBase(current.knowledgeBaseId(), name, description);
        runtime.refresh(context(updated, current.indexHandle().engine()));
        return updated;
    }

    public synchronized void deleteKnowledgeBase(String id) throws IOException {
        if (knowledgeBases.listKnowledgeBases().size() <= 1) {
            throw new IllegalStateException("至少需要保留一个知识库");
        }
        knowledgeBases.deleteKnowledgeBase(id);
        indexRepository.deleteIndex(id);
        ActiveKnowledgeContext current = runtime.currentOrNull();
        if (current != null && current.knowledgeBaseId().equals(id)) {
            loadKnowledgeBase(knowledgeBases.listKnowledgeBases().get(0));
        }
    }

    public synchronized boolean selectKnowledgeBase(String id) {
        KnowledgeBase selected = knowledgeBases.findKnowledgeBase(id)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在"));
        return loadKnowledgeBase(selected);
    }

    public void addSource(Path path) {
        requireActive();
        sources.addSource(runtime.current().knowledgeBaseId(), path);
        refreshAfterSourceChange();
        startFreshnessMonitoring();
    }

    public void removeSource(Path path) {
        requireActive();
        sources.removeSource(runtime.current().knowledgeBaseId(), path);
        refreshAfterSourceChange();
        startFreshnessMonitoring();
    }

    public IndexBuildResult rebuildCurrent(Consumer<IndexBuildProgress> progress) throws IOException {
        SemanticSearchEngine.IndexReport report = rebuild(roots(), progress == null ? null : value ->
                progress.accept(new IndexBuildProgress(value.processed(), value.total(),
                        value.currentFile(), value.stage())));
        return new IndexBuildResult(report.files(), report.chunks(), report.skipped(), report.warnings().stream()
                .map(warning -> new IndexBuildWarningView(warning.path(), warning.readerId(),
                        warning.message(), warning.skipped())).toList());
    }

    public SemanticSearchEngine.IndexReport rebuild(List<Path> roots,
                                                     Consumer<SemanticSearchEngine.IndexProgress> progress)
            throws IOException {
        requireActive();
        syncSources(roots);
        KnowledgeBase target = knowledgeBases.findKnowledgeBase(runtime.current().knowledgeBaseId()).orElseThrow();
        List<Path> capturedSources = sources.listSources(target.id());
        String capturedSourceHash = IndexIdentity.sourceSetHash(capturedSources);
        IndexSnapshot previousSnapshot = target.publishedIndexRevision() == null ? null
                : indexRepository.loadRevision(target.id(), target.publishedIndexRevision()).orElse(null);
        Long buildRevision = publications.beginIndexBuildRevision(target.id(), target.sourceRevision());
        if (buildRevision == null) {
            throw new StaleTaskException("数据源已变化，请重新开始索引构建");
        }
        KnowledgeBase buildingTarget = knowledgeBases.findKnowledgeBase(target.id()).orElseThrow();
        IndexBuildRequest request = new IndexBuildRequest(target.id(), buildRevision, capturedSources,
                capturedSourceHash, embeddingProvider.signature());
        startFreshnessMonitoring(buildingTarget, capturedSources, request.sourceSetHash());
        refreshActiveKnowledgeBase();
        SemanticSearchEngine builder = new SemanticSearchEngine(embeddingProvider, reranker, diagnostics);
        try {
            SemanticSearchEngine.IndexReport report = builder.index(request.sources(), previousSnapshot, progress);
            IndexSnapshot raw = builder.snapshot();
            int dimension = raw.chunks().stream().filter(DocumentChunk::hasEmbedding)
                    .mapToInt(chunk -> chunk.embedding().length).findFirst().orElse(0);
            String signature = dimension > 0 ? request.modelSignature().value() : "";
            long builtAt = System.currentTimeMillis();
            IndexManifest manifest = new IndexManifest(request.knowledgeBaseId(), request.sourceRevision(),
                    request.sourceSetHash(), signature, dimension, IndexIdentity.CHUNKING_VERSION,
                    IndexSnapshot.CURRENT_VERSION, builtAt);
            IndexSnapshot snapshot = builder.snapshot(manifest);
            String currentSourceHash = IndexIdentity.sourceSetHash(
                    sources.listSources(request.knowledgeBaseId()));
            if (!request.sourceSetHash().equals(currentSourceHash)) {
                throw new StaleTaskException("构建期间源文件已变化，结果已丢弃");
            }
            String fileName = indexRepository.saveRevision(request.knowledgeBaseId(), snapshot);
            if (!publications.publishIndex(manifest, fileName)) {
                indexRepository.deleteRevision(request.knowledgeBaseId(), request.sourceRevision());
                throw new StaleTaskException("构建期间数据源已变化，旧结果已丢弃");
            }
            synchronized (this) {
                ActiveKnowledgeContext current = runtime.currentOrNull();
                if (current != null && current.knowledgeBaseId().equals(request.knowledgeBaseId())) {
                    KnowledgeBase published = knowledgeBases.findKnowledgeBase(request.knowledgeBaseId()).orElseThrow();
                    runtime.publish(context(published, builder));
                }
            }
            startFreshnessMonitoringAfterPublish(manifest);
            diagnostics.record("index build completed", "index", "published", java.util.Map.of(
                    "knowledgeBaseId", request.knowledgeBaseId(),
                    "revision", Long.toString(request.sourceRevision()),
                    "files", Integer.toString(report.files()),
                    "chunks", Integer.toString(report.chunks()),
                    "stageMillis", report.stageMillis().toString()));
            return report;
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof StaleTaskException || failure instanceof InterruptedIOException) {
                publications.markIndexDirty(request.knowledgeBaseId(), failure.getMessage());
                diagnostics.record(failure instanceof InterruptedIOException
                                ? "index build cancelled" : "stale task discarded",
                        "index", failure.getClass().getSimpleName(), java.util.Map.of(
                                "knowledgeBaseId", request.knowledgeBaseId(),
                                "revision", Long.toString(request.sourceRevision())));
            } else {
                publications.markIndexBuildFailed(request.knowledgeBaseId(), request.sourceRevision(), failure.getMessage());
                diagnostics.record("index build failed", "index", failure.getClass().getSimpleName(),
                        java.util.Map.of("knowledgeBaseId", request.knowledgeBaseId(),
                                "revision", Long.toString(request.sourceRevision())));
            }
            refreshActiveKnowledgeBaseIf(request.knowledgeBaseId());
            throw failure;
        }
    }

    public List<SearchResult> search(String query, int limit, String extension) {
        IndexHandle handle = requireHandle();
        return handle.engine().search(query, limit, extension);
    }

    public List<SearchResultView> search(String knowledgeBaseId, long expectedRevision, String query,
                                         int limit, String extension) {
        IndexHandle handle = requireHandle();
        requireIdentity(handle, knowledgeBaseId, expectedRevision);
        return handle.engine().search(query, limit, extension).stream().map(KnowledgeService::toView).toList();
    }

    public List<SemanticHighlight> semanticHighlights(String query, DocumentChunk chunk, int limit)
            throws IOException {
        IndexHandle handle = requireHandle();
        return handle.engine().semanticHighlights(query, chunk, limit);
    }

    public List<SemanticHighlight> semanticHighlights(String knowledgeBaseId, long expectedRevision,
                                                       String query, DocumentReference document, int limit)
            throws IOException {
        IndexHandle handle = requireHandle();
        requireIdentity(handle, knowledgeBaseId, expectedRevision);
        DocumentChunk chunk = handle.engine().snapshot().chunks().stream()
                .filter(candidate -> candidate.id().equals(document.id()))
                .findFirst().orElseThrow(() -> new StaleTaskException("检索片段已失效，请重新搜索"));
        return handle.engine().semanticHighlights(query, chunk, limit);
    }

    public RagAnswer ask(String question, ApiConfig config) throws IOException, InterruptedException {
        IndexHandle handle = requireReadyHandle();
        List<RagCitation> citations = retrieveCitations(handle, question);
        freshnessGate.requireFresh(handle.knowledgeBaseId(), handle.sourceRevision());
        ChatRequest request = new ChatRequest(handle.knowledgeBaseId(), handle.sourceRevision(),
                question, List.of(), citations);
        return apiClient.answer(config, request);
    }

    /**
     * Streams a RAG answer. The retrieved citations are handed to {@code onCitations} before generation
     * starts so the UI can render sources immediately, then {@code onDelta} receives each incremental
     * chunk of the generated text.
     */
    public RagAnswer askStream(String question, ApiConfig config, Consumer<List<RagCitation>> onCitations,
                               Consumer<AnswerDelta> onDelta) throws IOException, InterruptedException {
        IndexHandle handle = requireReadyHandle();
        List<RagCitation> citations = retrieveCitations(handle, question);
        if (onCitations != null) onCitations.accept(citations);
        freshnessGate.requireFresh(handle.knowledgeBaseId(), handle.sourceRevision());
        ChatRequest request = new ChatRequest(handle.knowledgeBaseId(), handle.sourceRevision(),
                question, List.of(), citations);
        return apiClient.answerStream(config, request, onDelta);
    }

    @Override
    public AskResultView askStream(String knowledgeBaseId, long expectedRevision, String question,
                                   List<ChatMessage> history, ApiConfig config,
                                   Consumer<List<CitationView>> onCitations, RemoteSendAuthorizer authorizer,
                                   Consumer<AnswerDelta> onDelta)
            throws IOException, InterruptedException {
        IndexHandle handle = requireReadyHandle(knowledgeBaseId, expectedRevision);
        List<RagCitation> citations = retrieveCitations(handle, question);
        List<CitationView> citationViews = citations.stream().map(KnowledgeService::toView).toList();
        if (onCitations != null) onCitations.accept(citationViews);
        freshnessGate.requireFresh(handle.knowledgeBaseId(), handle.sourceRevision());
        KnowledgeBase knowledgeBase = knowledgeBases.findKnowledgeBase(knowledgeBaseId).orElseThrow();
        // Single-shot path: one search, one send. The ceiling equals what is on screen right now.
        RemoteSendReview review = new RemoteSendReview(knowledgeBaseId, knowledgeBase.name(), expectedRevision,
                config.targetHost(), false, citationViews, 1, citationViews.size());
        if (authorizer == null || !authorizer.authorize(review)) throw new IllegalStateException("用户取消了远程发送");
        List<ChatMessage> safeHistory = history == null ? List.of() : List.copyOf(history);
        ChatRequest request = new ChatRequest(handle.knowledgeBaseId(), handle.sourceRevision(),
                question, safeHistory, citations);
        RagAnswer answer = apiClient.answerStream(config, request, onDelta);
        return new AskResultView(answer.text(), citationViews, answer.model());
    }
    private List<RagCitation> retrieveCitations(IndexHandle handle, String question) {
        List<SearchResult> results = handle.engine().search(question, 8, "全部");
        return java.util.stream.IntStream.range(0, Math.min(6, results.size()))
                .mapToObj(index -> new RagCitation(index + 1, results.get(index).chunk(), results.get(index).score()))
                .toList();
    }

    public List<String> fetchModels(ApiConfig config) throws IOException, InterruptedException {
        return apiSettings.fetchModels(config);
    }

    public ApiConfig apiConfig() {
        return apiSettings.apiConfig();
    }

    public void saveApiConfig(ApiConfig config) {
        apiSettings.saveApiConfig(config);
    }

    @Override public ModelApiConfig embeddingApiConfig() { return apiSettings.embeddingApiConfig(); }

    @Override public synchronized void saveEmbeddingApiConfig(ModelApiConfig config) {
        String previousSignature = embeddingProvider.signature().value();
        apiSettings.saveEmbeddingApiConfig(config);
        if (!previousSignature.equals(embeddingProvider.signature().value())
                && runtime.currentOrNull() != null && requireHandle().engine().chunkCount() > 0) {
            ActiveKnowledgeContext current = runtime.current();
            publications.markIndexIncompatible(current.knowledgeBaseId(), "向量模型配置已变化，请重建索引");
            KnowledgeBase refreshed = knowledgeBases.findKnowledgeBase(current.knowledgeBaseId()).orElseThrow();
            current.indexHandle().engine().markStale();
            runtime.invalidate(context(refreshed, current.indexHandle().engine()));
        }
    }

    @Override public ModelApiConfig rerankApiConfig() { return apiSettings.rerankApiConfig(); }

    @Override public void saveRerankApiConfig(ModelApiConfig config) {
        apiSettings.saveRerankApiConfig(config);
    }

    @Override public boolean localOnly(String knowledgeBaseId) { return apiSettings.localOnly(knowledgeBaseId); }
    @Override public void saveLocalOnly(String knowledgeBaseId, boolean localOnly) {
        apiSettings.saveLocalOnly(knowledgeBaseId, localOnly);
    }
    @Override public Set<String> trustedHosts() { return apiSettings.trustedHosts(); }
    @Override public void trustHost(String host) { apiSettings.trustHost(host); }

    public List<Path> roots() {
        requireActive();
        return sources.listSources(runtime.current().knowledgeBaseId());
    }

    public Set<String> extensions() {
        return requireHandle().engine().extensions();
    }

    public KnowledgeStats stats() {
        IndexHandle handle = requireHandle();
        IndexSnapshot snapshot = handle.engine().snapshot();
        return new KnowledgeStats(handle.engine().fileCount(), handle.engine().chunkCount(), snapshot.indexedAt(),
                indexRepository.location(handle.knowledgeBaseId()));
    }

    public boolean semanticEnabled() {
        return requireHandle().status() == IndexStatus.READY && requireHandle().engine().semanticEnabled();
    }

    public boolean semanticModelConfigured() {
        return runtime.current().indexHandle().engine().semanticModelConfigured();
    }

    public String semanticStatus() {
        IndexHandle handle = requireHandle();
        if (handle.status() != IndexStatus.READY) return indexStatusText(handle.status());
        return handle.engine().semanticStatus();
    }

    public String freshnessStatus() {
        FreshnessSnapshot freshness = freshnessMonitor.snapshot();
        KnowledgeBase knowledgeBase = runtime.currentOrNull() == null ? null : runtime.current().knowledgeBase();
        if (knowledgeBase != null && knowledgeBase.freshnessReason() != null
                && !knowledgeBase.freshnessReason().isBlank()) {
            return knowledgeBase.freshnessReason() + verifiedAtSuffix(knowledgeBase.lastVerifiedAt());
        }
        if (freshness.state() == FreshnessState.VERIFIED && freshness.verifiedAt() > 0) {
            return "源文件已核对 · " + java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(java.time.Instant.ofEpochMilli(freshness.verifiedAt()));
        }
        return freshness.reason();
    }

    private static String verifiedAtSuffix(Long verifiedAt) {
        if (verifiedAt == null || verifiedAt <= 0) return "";
        String time = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(verifiedAt));
        return " · 上次核对 " + time;
    }

    public IndexStatus indexStatus() {
        return requireHandle().status();
    }

    public long sourceRevision() {
        return requireHandle().sourceRevision();
    }

    public Path databasePath() {
        return knowledgeBases.databasePath();
    }

    private boolean loadKnowledgeBase(KnowledgeBase selected) {
        if (selected.indexStatus() == IndexStatus.BUILDING) {
            publications.markIndexBuildFailed(selected.id(), selected.sourceRevision(), "应用退出导致上次构建中断");
            selected = knowledgeBases.findKnowledgeBase(selected.id()).orElseThrow();
        }
        settings.putSetting(ACTIVE_KB, selected.id());
        SemanticSearchEngine engine = new SemanticSearchEngine(embeddingProvider, reranker, diagnostics);
        try {
            indexRepository.cleanTemporaryFiles(selected.id());
            indexRepository.cleanUnreferenced(selected.id(), selected.publishedIndexRevision());
        } catch (IOException ignored) {
        }
        Optional<IndexSnapshot> snapshot = Optional.empty();
        if (selected.publishedIndexRevision() != null) {
            snapshot = indexRepository.loadRevision(selected.id(), selected.publishedIndexRevision());
        }
        if (snapshot.isEmpty()) {
            snapshot = indexRepository.loadLegacy(selected.id());
            if (snapshot.isPresent()) publications.markIndexIncompatible(selected.id(), "旧索引缺少完整 manifest，请重建");
        }
        if (snapshot.isPresent()) {
            engine.restore(snapshot.get());
            validateLoadedIndex(selected, snapshot.get());
        } else if (selected.publishedIndexRevision() != null) {
            publications.markIndexIncompatible(selected.id(), "已发布索引文件不存在或损坏，请重建");
        }
        KnowledgeBase activeKnowledgeBase = knowledgeBases.findKnowledgeBase(selected.id()).orElseThrow();
        if (activeKnowledgeBase.indexStatus() != IndexStatus.READY) engine.markStale();
        runtime.restore(context(activeKnowledgeBase, engine));
        startFreshnessMonitoring();
        return snapshot.isPresent();
    }

    private void migrateLegacyIndex(KnowledgeBase target) {
        indexRepository.loadGlobalLegacy().ifPresent(snapshot -> {
            snapshot.roots().stream().map(Path::of).forEach(path -> sources.addSource(target.id(), path));
            publications.markIndexIncompatible(target.id(), "旧索引需要按新格式重建");
        });
    }

    private void syncSources(List<Path> requested) {
        LinkedHashSet<Path> normalized = requested.stream().map(Path::toAbsolutePath).map(Path::normalize)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Path> existing = new LinkedHashSet<>(roots());
        existing.stream().filter(path -> !normalized.contains(path)).forEach(this::removeSource);
        normalized.stream().filter(path -> !existing.contains(path)).forEach(this::addSource);
    }

    private void validateLoadedIndex(KnowledgeBase knowledgeBase, IndexSnapshot snapshot) {
        IndexManifest manifest = snapshot.manifest();
        if (manifest == null || !knowledgeBase.id().equals(manifest.knowledgeBaseId())) {
            publications.markIndexIncompatible(knowledgeBase.id(), "索引缺少有效身份信息，请重建");
            return;
        }
        String currentSourceHash = IndexIdentity.sourceSetHash(sources.listSources(knowledgeBase.id()));
        if (manifest.sourceRevision() != knowledgeBase.sourceRevision()) {
            publications.markIndexDirty(knowledgeBase.id(), "索引对应的数据源版本已过期");
            return;
        }
        if (!manifest.sourceSetHash().equals(currentSourceHash)) {
            freshnessRecords.markIndexDirtyIfCurrent(knowledgeBase.id(), knowledgeBase.sourceRevision(),
                    "源文件已变化，需要重建索引", currentSourceHash, System.currentTimeMillis());
            return;
        }
        if (manifest.indexFormatVersion() != IndexSnapshot.CURRENT_VERSION
                || manifest.chunkingVersion() != IndexIdentity.CHUNKING_VERSION
                || (manifest.embeddingDimension() > 0
                && !manifest.embeddingModelSignature().equals(embeddingProvider.signature().value()))) {
            publications.markIndexIncompatible(knowledgeBase.id(), "索引与当前模型或格式不兼容，请重建");
        }
    }

    private void refreshAfterSourceChange() {
        ActiveKnowledgeContext current = runtime.current();
        KnowledgeBase refreshed = knowledgeBases.findKnowledgeBase(current.knowledgeBaseId()).orElseThrow();
        current.indexHandle().engine().markStale();
        runtime.invalidate(context(refreshed, current.indexHandle().engine()));
    }

    private synchronized void refreshActiveKnowledgeBase() {
        requireActive();
        ActiveKnowledgeContext current = runtime.current();
        KnowledgeBase refreshed = knowledgeBases.findKnowledgeBase(current.knowledgeBaseId()).orElseThrow();
        ActiveKnowledgeContext next = context(refreshed, current.indexHandle().engine());
        if (refreshed.indexStatus() == IndexStatus.BUILDING) runtime.beginBuild(next);
        else if (refreshed.indexStatus() == IndexStatus.DIRTY || refreshed.indexStatus() == IndexStatus.INCOMPATIBLE) {
            current.indexHandle().engine().markStale();
            runtime.invalidate(next);
        } else if (refreshed.indexStatus() == IndexStatus.FAILED) runtime.fail(next);
        else runtime.refresh(next);
    }

    private synchronized void refreshActiveKnowledgeBaseIf(String knowledgeBaseId) {
        ActiveKnowledgeContext current = runtime.currentOrNull();
        if (current != null && current.knowledgeBaseId().equals(knowledgeBaseId)) refreshActiveKnowledgeBase();
    }

    private IndexHandle requireHandle() {
        return runtime.current().indexHandle();
    }

    private IndexHandle requireReadyHandle() {
        IndexHandle handle = requireHandle();
        return requireReadyHandle(handle.knowledgeBaseId(), handle.sourceRevision());
    }

    private IndexHandle requireReadyHandle(String knowledgeBaseId, long expectedRevision) {
        IndexHandle handle = requireHandle();
        requireIdentity(handle, knowledgeBaseId, expectedRevision);
        KnowledgeBase latest = knowledgeBases.findKnowledgeBase(handle.knowledgeBaseId()).orElseThrow();
        if (latest.indexStatus() != IndexStatus.READY || latest.publishedIndexRevision() == null
                || latest.sourceRevision() != handle.sourceRevision()
                || latest.publishedIndexRevision() != handle.sourceRevision()) {
            throw new IllegalStateException("当前索引不是最新 READY 状态，已禁止发送远程 RAG 请求");
        }
        freshnessGate.requireFresh(handle.knowledgeBaseId(), handle.sourceRevision());
        return handle;
    }

    private void startFreshnessMonitoring() {
        ActiveKnowledgeContext current = runtime.currentOrNull();
        if (current == null) return;
        KnowledgeBase selected = current.knowledgeBase();
        List<Path> monitoredSources = sources.listSources(selected.id());
        String currentHash = IndexIdentity.sourceSetHash(monitoredSources);
        startFreshnessMonitoring(selected, monitoredSources, currentHash);
    }

    private void startFreshnessMonitoring(KnowledgeBase selected, List<Path> sources, String expectedHash) {
        long epoch = freshnessEpoch.incrementAndGet();
        SourceFreshnessMonitor.MonitorRequest request = new SourceFreshnessMonitor.MonitorRequest(
                selected.id(), selected.sourceRevision(), sources, expectedHash);
        freshnessMonitor.start(request, new SourceFreshnessMonitor.Listener() {
            @Override
            public void sourceVerified(SourceFreshnessMonitor.MonitorRequest verifiedRequest,
                                       SourceFingerprint fingerprint) {
                if (freshnessEpoch.get() != epoch) return;
                freshnessRecords.recordSourceVerification(verifiedRequest.knowledgeBaseId(),
                        verifiedRequest.sourceRevision(), fingerprint.hash(), fingerprint.verifiedAt());
                refreshActiveKnowledgeBaseIf(verifiedRequest.knowledgeBaseId());
            }

            @Override
            public void sourceInvalidated(SourceFreshnessMonitor.MonitorRequest invalidRequest,
                                          SourceFingerprint fingerprint, String reason) {
                if (freshnessEpoch.get() != epoch) return;
                String observedHash = fingerprint == null ? null : fingerprint.hash();
                Long verifiedAt = fingerprint == null ? null : fingerprint.verifiedAt();
                if (freshnessRecords.markIndexDirtyIfCurrent(invalidRequest.knowledgeBaseId(),
                        invalidRequest.sourceRevision(), reason, observedHash, verifiedAt)) {
                    synchronized (KnowledgeService.this) {
                        ActiveKnowledgeContext current = runtime.currentOrNull();
                        if (current != null && current.knowledgeBaseId().equals(invalidRequest.knowledgeBaseId())) {
                            KnowledgeBase invalidated = knowledgeBases.findKnowledgeBase(invalidRequest.knowledgeBaseId())
                                    .orElseThrow();
                            current.indexHandle().engine().markStale();
                            runtime.invalidate(context(invalidated, current.indexHandle().engine()));
                        }
                    }
                }
            }
        });
    }

    private void startFreshnessMonitoringAfterPublish(IndexManifest manifest) {
        KnowledgeBase selected;
        synchronized (this) {
            ActiveKnowledgeContext current = runtime.currentOrNull();
            if (current == null
                    || !current.knowledgeBaseId().equals(manifest.knowledgeBaseId())
                    || current.sourceRevision() != manifest.sourceRevision()) return;
            selected = current.knowledgeBase();
        }
        startFreshnessMonitoring(selected, sources.listSources(selected.id()), manifest.sourceSetHash());
    }

    @Override
    public void close() {
        freshnessEpoch.incrementAndGet();
        freshnessMonitor.close();
        embeddingProvider.close();
    }

    private static void requireIdentity(IndexHandle handle, String knowledgeBaseId, long revision) {
        if (!handle.knowledgeBaseId().equals(knowledgeBaseId) || handle.sourceRevision() != revision) {
            throw new StaleTaskException("知识库或数据版本已变化，任务结果已丢弃");
        }
    }

    private static String indexStatusText(IndexStatus status) {
        return switch (status) {
            case EMPTY -> "尚未建立索引";
            case READY -> "索引已就绪";
            case DIRTY -> "数据源已变化，索引待重建";
            case BUILDING -> "正在构建索引";
            case FAILED -> "最近一次索引构建失败";
            case INCOMPATIBLE -> "索引与当前版本不兼容，需重建";
        };
    }

    private void requireActive() {
        runtime.current();
    }

    private ActiveKnowledgeContext context(KnowledgeBase knowledgeBase, SemanticSearchEngine engine) {
        return new ActiveKnowledgeContext(knowledgeBase, freshnessMonitor.snapshot(),
                new IndexHandle(knowledgeBase.id(), knowledgeBase.sourceRevision(), knowledgeBase.indexStatus(), engine));
    }

    private static SearchResultView toView(SearchResult result) {
        return new SearchResultView(toView(result.chunk()), result.score(), result.reason());
    }

    private static CitationView toView(RagCitation citation) {
        return new CitationView(citation.number(), toView(citation.chunk()), citation.score());
    }

    private static DocumentReference toView(DocumentChunk chunk) {
        return new DocumentReference(chunk.id(), chunk.filePath(), chunk.fileName(), chunk.extension(),
                chunk.startLine(), chunk.endLine(), chunk.sourceLocation(), chunk.content(), chunk.hasEmbedding());
    }

}
