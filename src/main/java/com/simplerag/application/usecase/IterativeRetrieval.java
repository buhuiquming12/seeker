package com.simplerag.application.usecase;

import com.simplerag.application.conversation.AnswerDelta;
import com.simplerag.application.conversation.ChatMessage;
import com.simplerag.application.conversation.ConversationQueryResolver;
import com.simplerag.application.conversation.RetrievalAttempt;
import com.simplerag.application.conversation.RetrievalDecision;
import com.simplerag.application.conversation.RetrievalPlanRequest;
import com.simplerag.application.port.out.ChatModel;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.RagCitation;
import com.simplerag.model.SearchResult;
import com.simplerag.model.TokenUsage;
import com.simplerag.rag.ApiConfig;
import com.simplerag.search.RetrievalStrategy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Runs a bounded, adaptive retrieve-evaluate-retrieve loop before answer generation. */
final class IterativeRetrieval {
    static final int MAX_SEARCHES = 4;
    static final int MAX_CITATIONS = 12;
    private static final int INITIAL_CITATIONS = 6;
    private static final int FOLLOW_UP_CITATIONS = 3;

    private final ChatModel chat;

    IterativeRetrieval(ChatModel chat) {
        this.chat = chat;
    }

    /**
     * The local retrieval seam. Takes a strategy as well as a query because a HyDE pseudo-document
     * has to reach the vector branch alone, while an ordinary generated query goes through the full
     * hybrid pipeline.
     */
    @FunctionalInterface
    interface RetrievalFunction {
        List<SearchResult> search(String query, RetrievalStrategy strategy);
    }

    /**
     * Citations gathered for the turn, what the planning rounds cost, and whether planning actually
     * ran. {@code plannerUnavailable} lets the caller tell "the model was satisfied" apart from
     * "we never got a decision", which matters when the evidence set is thin or empty.
     */
    record Result(List<RagCitation> citations, TokenUsage usage, boolean plannerUnavailable) {
        Result(List<RagCitation> citations, TokenUsage usage) {
            this(citations, usage, false);
        }
    }

    Result collect(String knowledgeBaseId, long sourceRevision, String question,
                   List<ChatMessage> history, ApiConfig config,
                   RetrievalFunction search,
                   Consumer<List<RagCitation>> onCitationScopeChanged,
                   Runnable beforeRemoteCall)
            throws IOException, InterruptedException {
        return collect(knowledgeBaseId, sourceRevision, question, history, config, search,
                onCitationScopeChanged, beforeRemoteCall, true);
    }

    Result collect(String knowledgeBaseId, long sourceRevision, String question,
                   List<ChatMessage> history, ApiConfig config,
                   RetrievalFunction search,
                   Consumer<List<RagCitation>> onCitationScopeChanged,
                   Runnable beforeRemoteCall,
                   boolean semanticRetrievalAvailable)
            throws IOException, InterruptedException {
        return collect(knowledgeBaseId, sourceRevision, question, history, config, search,
                onCitationScopeChanged, beforeRemoteCall, semanticRetrievalAvailable, delta -> { });
    }

    /**
     * @param onPlanning receives the planner's own reasoning and the queries it generated, so the user
     *                   can see the loop working instead of inferring it from a citation count.
     */
    Result collect(String knowledgeBaseId, long sourceRevision, String question,
                   List<ChatMessage> history, ApiConfig config,
                   RetrievalFunction search,
                   Consumer<List<RagCitation>> onCitationScopeChanged,
                   Runnable beforeRemoteCall,
                   boolean semanticRetrievalAvailable,
                   Consumer<AnswerDelta> onPlanning)
            throws IOException, InterruptedException {
        Map<String, Candidate> collected = new LinkedHashMap<>();
        List<RetrievalAttempt> attempts = new ArrayList<>();
        TokenUsage usage = TokenUsage.UNKNOWN;
        boolean plannerUnavailable = false;
        // A pronoun-only follow-up ("它在哪？") carries nothing to retrieve on, and the planner cannot
        // help yet: it only runs after the user authorises the send. Resolve references locally first.
        String initialQuery = ConversationQueryResolver.resolve(question, history);
        int initialAdded = addResults(collected,
                search.search(initialQuery, RetrievalStrategy.RRF_RERANK), INITIAL_CITATIONS);
        attempts.add(new RetrievalAttempt(initialQuery, initialAdded));
        List<RagCitation> citations = numbered(collected);
        onCitationScopeChanged.accept(citations);

        for (int searchNumber = 1; searchNumber < MAX_SEARCHES; searchNumber++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Question answering was cancelled");
            if (collected.size() >= MAX_CITATIONS) break;
            beforeRemoteCall.run();
            RetrievalPlanRequest request = new RetrievalPlanRequest(knowledgeBaseId, sourceRevision,
                    question, history, citations, attempts, MAX_SEARCHES - searchNumber,
                    semanticRetrievalAvailable);
            RetrievalDecision decision;
            try {
                decision = chat.planRetrieval(config, request);
            } catch (IOException plannerUnreachable) {
                // Planning is an optimisation. Losing it costs recall; letting it abort the turn would
                // cost the answer entirely, so fall back to the evidence already collected.
                plannerUnavailable = true;
                announce(onPlanning, "〔检索规划不可用：" + plannerUnreachable.getMessage() + "〕");
                break;
            }
            if (decision == null) {
                plannerUnavailable = true;
                announce(onPlanning, "〔检索规划不可用：模型未返回决策〕");
                break;
            }
            // A planning round is billed even when it decides to stop searching.
            usage = usage.plus(decision.usage());
            if (!decision.reasoning().isBlank()) {
                announce(onPlanning, "〔第 " + (searchNumber + 1) + " 轮检索规划〕\n" + decision.reasoning().strip());
            }
            if (decision.plannerUnavailable()) {
                plannerUnavailable = true;
                // Naming this is the whole point: it used to look identical to "the evidence sufficed".
                announce(onPlanning, "〔检索规划不可用：模型返回了无法解析的结果，已停止追加检索〕");
                break;
            }
            if (!decision.shouldSearch()) {
                announce(onPlanning, "〔模型判断现有证据已足够，停止检索〕");
                break;
            }
            announce(onPlanning, plannedQueries(searchNumber + 1, decision));

            // The model may generate several queries for one round. Running them all before the next
            // planning call turns a purely sequential loop into a bounded fan-out, so a question with
            // two independent facets no longer needs two round trips to cover both.
            int addedThisRound = 0;
            boolean searchedThisRound = false;
            for (RetrievalDecision.TypedQuery planned : decision.queries()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Question answering was cancelled");
                }
                String query = planned.text();
                if (alreadyTried(attempts, query)) continue;
                searchedThisRound = true;
                // A HyDE pseudo-document is prose the planner invented. Its value is entirely in the
                // embedding, so it goes to the vector branch alone; fed to BM25 it would just match
                // its own filler words.
                RetrievalStrategy strategy = planned.hypothetical()
                        ? RetrievalStrategy.DENSE : RetrievalStrategy.RRF_RERANK;
                int added = addResults(collected, search.search(query, strategy), FOLLOW_UP_CITATIONS);
                attempts.add(new RetrievalAttempt(query, added));
                addedThisRound += added;
                if (collected.size() >= MAX_CITATIONS) break;
            }
            // Every generated query was a repeat: the model has stopped making progress.
            if (!searchedThisRound) break;
            if (addedThisRound > 0) {
                citations = numbered(collected);
                onCitationScopeChanged.accept(citations);
            }
            if (collected.size() >= MAX_CITATIONS) break;
        }
        return new Result(citations, usage, plannerUnavailable);
    }

    private static void announce(Consumer<AnswerDelta> onPlanning, String note) {
        if (onPlanning == null || note == null || note.isBlank()) return;
        onPlanning.accept(AnswerDelta.planning(note.strip() + "\n"));
    }

    /**
     * The queries the model wrote for itself, rendered for the thinking panel. This is the most direct
     * evidence that self-directed retrieval ran at all, and it needs no streaming support from the
     * provider — the decision already carries it.
     */
    private static String plannedQueries(int round, RetrievalDecision decision) {
        StringBuilder note = new StringBuilder("〔第 ").append(round).append(" 轮检索〕");
        for (RetrievalDecision.TypedQuery query : decision.queries()) {
            note.append("\n  ").append(query.hypothetical() ? "假设文档：" : "关键词：").append(query.text());
        }
        return note.toString();
    }

    private static int addResults(Map<String, Candidate> collected, List<SearchResult> results, int limit) {
        int added = 0;
        if (results == null) return 0;
        for (SearchResult result : results) {
            if (result == null || result.chunk() == null) continue;
            DocumentChunk chunk = result.chunk();
            String key = chunk.id() + "\u0000" + chunk.path() + "\u0000" + chunk.sourceLocation();
            if (collected.containsKey(key) || overlapsCollected(collected, chunk)) continue;
            long documentCitations = collected.values().stream()
                    .filter(candidate -> candidate.chunk().path().equals(chunk.path())).count();
            if (documentCitations >= 3) continue;
            collected.put(key, new Candidate(chunk, result.score()));
            added++;
            if (added >= limit || collected.size() >= MAX_CITATIONS) break;
        }
        return added;
    }


    private static boolean overlapsCollected(Map<String, Candidate> collected, DocumentChunk chunk) {
        for (Candidate candidate : collected.values()) {
            DocumentChunk existing = candidate.chunk();
            if (!existing.path().equals(chunk.path())) continue;
            int overlapStart = Math.max(existing.startLine(), chunk.startLine());
            int overlapEnd = Math.min(existing.endLine(), chunk.endLine());
            if (overlapEnd < overlapStart) continue;
            int overlap = overlapEnd - overlapStart + 1;
            int shorter = Math.max(1, Math.min(existing.endLine() - existing.startLine() + 1,
                    chunk.endLine() - chunk.startLine() + 1));
            if (overlap / (double) shorter >= 0.5) return true;
        }
        return false;
    }

    private static List<RagCitation> numbered(Map<String, Candidate> collected) {
        List<RagCitation> citations = new ArrayList<>(collected.size());
        int number = 1;
        for (Candidate candidate : collected.values()) {
            citations.add(new RagCitation(number++, candidate.chunk(), candidate.score()));
        }
        return List.copyOf(citations);
    }

    private static boolean alreadyTried(List<RetrievalAttempt> attempts, String query) {
        String normalized = query.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return attempts.stream().map(RetrievalAttempt::query)
                .map(value -> value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }

    private record Candidate(DocumentChunk chunk, double score) { }
}
