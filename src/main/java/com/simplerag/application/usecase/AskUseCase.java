package com.simplerag.application.usecase;

import com.simplerag.application.conversation.AnswerDelta;
import com.simplerag.application.conversation.ChatMessage;
import com.simplerag.application.conversation.ChatRequest;
import com.simplerag.application.dto.AskResultView;
import com.simplerag.application.dto.CitationView;
import com.simplerag.application.dto.DocumentReference;
import com.simplerag.application.dto.RemoteSendReview;
import com.simplerag.application.diagnostics.DiagnosticSink;
import com.simplerag.application.port.in.AskKnowledge;
import com.simplerag.application.port.in.RemoteSendAuthorizer;
import com.simplerag.application.freshness.FreshnessGate;
import com.simplerag.application.port.out.ChatModel;
import com.simplerag.application.port.out.KnowledgeBaseRepository;
import com.simplerag.application.port.out.SettingsRepository;
import com.simplerag.application.runtime.ActiveKnowledgeRuntime;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.IndexStatus;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.RagAnswer;
import com.simplerag.model.RagCitation;
import com.simplerag.model.SearchResult;
import com.simplerag.model.TokenUsage;
import com.simplerag.search.IndexHandle;
import com.simplerag.search.QueryDecomposer;
import com.simplerag.search.RetrievalStrategy;
import com.simplerag.rag.ApiConfig;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.Map;

public final class AskUseCase implements AskKnowledge {
    private final ActiveKnowledgeRuntime runtime;
    private final KnowledgeBaseRepository knowledgeBases;
    private final FreshnessGate freshnessGate;
    private final ChatModel chat;
    private final SettingsRepository settings;
    private final DiagnosticSink diagnostics;
    private final QueryDecomposer decomposer = new QueryDecomposer();

    public AskUseCase(ActiveKnowledgeRuntime runtime, KnowledgeBaseRepository knowledgeBases,
                      FreshnessGate freshnessGate, ChatModel chat) {
        this.runtime = runtime;
        this.knowledgeBases = knowledgeBases;
        this.freshnessGate = freshnessGate;
        this.chat = chat;
        this.settings = null;
        this.diagnostics = DiagnosticSink.noop();
    }

    public AskUseCase(ActiveKnowledgeRuntime runtime, KnowledgeBaseRepository knowledgeBases,
                      FreshnessGate freshnessGate, ChatModel chat, SettingsRepository settings,
                      DiagnosticSink diagnostics) {
        this.runtime = runtime;
        this.knowledgeBases = knowledgeBases;
        this.freshnessGate = freshnessGate;
        this.chat = chat;
        this.settings = settings;
        this.diagnostics = diagnostics == null ? DiagnosticSink.noop() : diagnostics;
    }

    @Override
    public AskResultView askStream(String knowledgeBaseId, long expectedRevision, String question,
                                   List<ChatMessage> history, ApiConfig config,
                                   Consumer<List<CitationView>> onCitations, RemoteSendAuthorizer authorizer,
                                   Consumer<AnswerDelta> onDelta)
            throws IOException, InterruptedException {
        IndexHandle handle = requireReady(knowledgeBaseId, expectedRevision);
        KnowledgeBase knowledgeBase = knowledgeBases.findKnowledgeBase(knowledgeBaseId).orElseThrow();
        if (localOnly(knowledgeBaseId)) {
            diagnostics.record("RAG blocked reason", "security", "knowledge base is local-only",
                    Map.of("knowledgeBaseId", knowledgeBaseId, "revision", Long.toString(expectedRevision)));
            throw new IllegalStateException("当前知识库启用了“仅本地 RAG”，已禁止远程发送");
        }
        // History only resolves conversational references. Every turn builds a fresh, bounded evidence set.
        List<ChatMessage> safeHistory = history == null ? List.of() : List.copyOf(history);
        IterativeRetrieval retrieval = new IterativeRetrieval(chat);
        // One authorization per turn: the user approves the target host and the ceiling the adaptive
        // loop may reach, then follow-up evidence only refreshes the citation panel. Re-prompting on
        // every scope change blocked the worker thread between remote calls, which let the pooled TLS
        // connection go stale mid-turn.
        AtomicBoolean authorized = new AtomicBoolean();
        // Retrieval planning is part of the turn's chain of thought, so it accumulates alongside the
        // answer thinking and is reported as one body of reasoning.
        StringBuilder reasoning = new StringBuilder();
        Consumer<AnswerDelta> relay = delta -> {
            if (delta.reasoning()) reasoning.append(delta.text());
            if (onDelta != null) onDelta.accept(delta);
        };
        IterativeRetrieval.Result retrieved = retrieval.collect(handle.knowledgeBaseId(), handle.sourceRevision(),
                question, safeHistory, config,
                (query, strategy) -> retrieveContext(handle, query, strategy),
                scope -> publishScope(knowledgeBase, expectedRevision, config, scope,
                        onCitations, authorizer, authorized),
                () -> freshnessGate.requireFresh(handle.knowledgeBaseId(), handle.sourceRevision()),
                handle.engine().semanticEnabled(), relay);
        List<RagCitation> citations = retrieved.citations();

        freshnessGate.requireFresh(handle.knowledgeBaseId(), handle.sourceRevision());
        // Retrieval found nothing. Calling the model anyway would send a question with no evidence and
        // fail inside the adapter, surfacing a raw argument error instead of an honest abstention.
        if (citations.isEmpty()) {
            return abstain(knowledgeBaseId, expectedRevision, retrieved, onDelta, reasoning.toString());
        }
        List<CitationView> views = citations.stream().map(AskUseCase::toView).toList();
        ChatRequest request = new ChatRequest(handle.knowledgeBaseId(), handle.sourceRevision(),
                question, safeHistory, citations);
        int streamedReasoning = reasoning.length();
        RagAnswer answer = chat.answerStream(config, request, relay);
        // The non-streaming fallback inside the adapter returns its thinking on the answer instead of
        // emitting it as deltas, so nothing streamed means nothing was relayed — take it from there.
        if (reasoning.length() == streamedReasoning && !answer.reasoning().isBlank()) {
            reasoning.append(answer.reasoning());
        }
        // One turn bills several calls: every planning round plus the answer itself.
        TokenUsage turnUsage = retrieved.usage().plus(answer.usage());
        diagnostics.record("turn token usage", "remote-api", "ask",
                Map.of("knowledgeBaseId", knowledgeBaseId,
                        "promptTokens", Integer.toString(turnUsage.promptTokens()),
                        "completionTokens", Integer.toString(turnUsage.completionTokens()),
                        "totalTokens", Integer.toString(turnUsage.totalTokens())));
        return new AskResultView(answer.text(), views, answer.model(), turnUsage, reasoning.toString());
    }

    /**
     * Retrieves the evidence for one generated query. Splits a multi-facet question into its facets
     * first, so the dense branch is not asked to embed two unrelated topics as one point.
     */
    private List<SearchResult> retrieveContext(IndexHandle handle, String query, RetrievalStrategy requested) {
        RetrievalStrategy effective = requested;
        // A HyDE pseudo-document retrieved with DENSE against an index that has no vectors returns an
        // empty list and nothing else — no error, no log. Degrade to the hybrid pipeline instead, so
        // the pseudo-document's terminology still reaches BM25, and say so in the diagnostics.
        if (requested == RetrievalStrategy.DENSE && !handle.engine().semanticEnabled()) {
            effective = RetrievalStrategy.RRF_RERANK;
            diagnostics.record("HyDE downgraded", "retrieval", "semantic retrieval unavailable",
                    Map.of("knowledgeBaseId", handle.knowledgeBaseId(),
                            "revision", Long.toString(handle.sourceRevision())));
        }
        List<String> facets = decomposer.decompose(query);
        return handle.engine().searchContext(facets, 8, "全部", effective);
    }

    /**
     * Reports "nothing was found" as a normal answer with no citations. The message distinguishes a
     * genuinely empty result from one where retrieval planning never ran, because the remedy differs:
     * rephrase the question, versus check the chat API configuration.
     */
    private AskResultView abstain(String knowledgeBaseId, long revision, IterativeRetrieval.Result retrieved,
                                  Consumer<AnswerDelta> onDelta, String reasoning) {
        String message = retrieved.plannerUnavailable()
                ? "当前知识库中没有检索到相关内容，且自动检索规划不可用（远程模型未响应或返回了无法解析的结果），"
                        + "因此没有尝试改写检索词。请检查对话模型配置，或换用更具体的关键词重新提问。"
                : "当前知识库中没有检索到与该问题相关的内容。已尝试自动改写检索词但仍无命中，"
                        + "请换用文档中可能出现的术语（例如具体的类名、方法名、配置键或文件名）重新提问。";
        diagnostics.record("RAG abstained", "retrieval", "no citations",
                Map.of("knowledgeBaseId", knowledgeBaseId, "revision", Long.toString(revision),
                        "plannerUnavailable", Boolean.toString(retrieved.plannerUnavailable())));
        if (onDelta != null) onDelta.accept(AnswerDelta.answer(message));
        return new AskResultView(message, List.of(), null, retrieved.usage(), reasoning);
    }

    private boolean localOnly(String knowledgeBaseId) {
        return settings != null && Boolean.parseBoolean(settings.getSetting("rag.local_only." + knowledgeBaseId)
                .orElse("false"));
    }

    private boolean trusted(String host) {
        if (settings == null) return false;
        return java.util.Arrays.stream(settings.getSetting("api.trusted_hosts").orElse("").split(","))
                .map(String::strip).anyMatch(host::equalsIgnoreCase);
    }

    private IndexHandle requireReady(String knowledgeBaseId, long revision) {
        IndexHandle handle = runtime.current().indexHandle();
        if (!handle.knowledgeBaseId().equals(knowledgeBaseId) || handle.sourceRevision() != revision) {
            throw new StaleTaskException("知识库或数据版本已变化，任务结果已丢弃");
        }
        KnowledgeBase latest = knowledgeBases.findKnowledgeBase(knowledgeBaseId).orElseThrow();
        if (latest.indexStatus() != IndexStatus.READY || latest.publishedIndexRevision() == null
                || latest.sourceRevision() != revision || latest.publishedIndexRevision() != revision) {
            throw new IllegalStateException("当前索引不是最新 READY 状态，已禁止发送远程 RAG 请求");
        }
        freshnessGate.requireFresh(knowledgeBaseId, revision);
        return handle;
    }

    private void publishScope(KnowledgeBase knowledgeBase, long expectedRevision, ApiConfig config,
                              List<RagCitation> citations,
                              Consumer<List<CitationView>> onCitations,
                              RemoteSendAuthorizer authorizer, AtomicBoolean authorized) {
        List<CitationView> views = citations.stream().map(AskUseCase::toView).toList();
        if (onCitations != null) onCitations.accept(views);
        freshnessGate.requireFresh(knowledgeBase.id(), expectedRevision);
        if (!authorized.compareAndSet(false, true)) return;
        String host = config.targetHost();
        RemoteSendReview review = new RemoteSendReview(knowledgeBase.id(), knowledgeBase.name(), expectedRevision,
                host, trusted(host), views, IterativeRetrieval.MAX_SEARCHES, IterativeRetrieval.MAX_CITATIONS);
        if (authorizer == null || !authorizer.authorize(review)) {
            diagnostics.record("RAG blocked reason", "security", "remote send was not authorized",
                    Map.of("knowledgeBaseId", knowledgeBase.id(), "targetHost", host,
                            "chunkCount", Integer.toString(views.size())));
            throw new IllegalStateException("用户取消了远程发送");
        }
    }

    private static CitationView toView(RagCitation citation) {
        DocumentChunk chunk = citation.chunk();
        DocumentReference document = new DocumentReference(chunk.id(), chunk.filePath(), chunk.fileName(),
                chunk.extension(), chunk.startLine(), chunk.endLine(), chunk.sourceLocation(),
                chunk.content(), chunk.hasEmbedding());
        return new CitationView(citation.number(), document, citation.score());
    }
}
