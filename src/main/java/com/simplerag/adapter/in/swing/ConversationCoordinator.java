package com.simplerag.adapter.in.swing;

import com.simplerag.application.conversation.AnswerDelta;
import com.simplerag.application.conversation.StoredMessage;
import com.simplerag.application.dto.AskResultView;
import com.simplerag.application.dto.CitationView;
import com.simplerag.application.dto.ConversationView;
import com.simplerag.application.dto.RemoteSendReview;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.TokenUsage;
import com.simplerag.rag.ApiConfig;

import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The ask page: saved conversations, the streaming turn and the remote-send confirmation.
 *
 * <p>Two things are bound together here and nowhere else. A conversation is durable, so switching to
 * one reads it back from storage; the model's memory of it is not, so a source revision change still
 * restarts the context and the transcript says where that happened.
 */
final class ConversationCoordinator {
    private final AskController ask;
    private final KnowledgeController knowledge;
    private final BackgroundTaskCoordinator tasks;
    private final Supplier<KnowledgeBase> currentBase;
    private final Consumer<String> flashStatus;
    private final Consumer<CitationView> openCitation;
    private final BiConsumer<String, Throwable> showError;
    private final AskPanel panel;
    private BackgroundTaskCoordinator.TaskHandle askTask;
    /** Identity the bubbles on screen were produced under; drives the context-break marker. */
    private KnowledgeController.TaskIdentity conversationIdentity;
    private ConversationView active;

    ConversationCoordinator(AskController ask, KnowledgeController knowledge,
                            BackgroundTaskCoordinator tasks, Supplier<KnowledgeBase> currentBase,
                            Consumer<String> flashStatus, Consumer<CitationView> openCitation,
                            BiConsumer<String, Throwable> showError) {
        this.ask = ask;
        this.knowledge = knowledge;
        this.tasks = tasks;
        this.currentBase = currentBase;
        this.flashStatus = flashStatus;
        this.openCitation = openCitation;
        this.showError = showError;
        this.panel = new AskPanel(this::askQuestion, this::saveLocalPolicy, this::openSelectedCitation,
                this::startConversation, this::selectConversation, this::deleteConversation);
        installSendShortcut();
    }

    AskPanel panel() { return panel; }

    void close() {
        if (askTask != null && !askTask.isDone()) askTask.cancel();
    }

    void cancelInFlight() {
        if (askTask != null && !askTask.isDone()) askTask.cancel();
    }

    /** Whether a turn is still streaming, which is when a context break must not be appended. */
    boolean idle() { return askTask == null || askTask.isDone(); }

    /** Shows the knowledge base's active conversation, its saved turns and its session list. */
    void reload() {
        KnowledgeBase current = currentBase.get();
        if (current == null) return;
        active = ask.activeConversation(current.id());
        conversationIdentity = knowledge.identity();
        refreshList();
        showActiveTranscript();
    }

    /** The per-knowledge-base send policy, re-read whenever the active base changes. */
    void applyPolicy(KnowledgeBase current) {
        panel.localOnly(ask.localOnly(current.id()));
        // The switch is per knowledge base, so a confirmation from the previous one must not linger.
        panel.policyStatus("", Theme.MUTED);
    }

    /**
     * Conversation sessions are keyed by conversation id + sourceRevision, so a source change makes
     * the store hand out a fresh session while the bubbles already on screen stay. The transcript
     * says where the model's memory restarted instead of presenting both halves as one context.
     */
    void noteContextBreak(KnowledgeController.TaskIdentity identity) {
        if (identity == null) return;
        KnowledgeController.TaskIdentity previous = conversationIdentity;
        conversationIdentity = identity;
        if (previous == null || previous.equals(identity)) return;
        // A knowledge-base switch reloads the transcript from that base's own conversation instead.
        if (!previous.knowledgeBaseId().equals(identity.knowledgeBaseId())) return;
        panel.contextBreak("源文件已变化（revision " + identity.sourceRevision()
                + "）· 模型从这里开始新的上下文，不再记得上面的对话");
    }

    private void startConversation() {
        KnowledgeBase current = currentBase.get();
        if (current == null) return;
        cancelInFlight();
        active = ask.startConversation(current.id());
        conversationIdentity = knowledge.identity();
        panel.asking(false);
        refreshList();
        panel.showTranscript(List.of());
        panel.clearCitations();
        panel.conversationTitle("新对话");
        panel.conversationMeta("提问后会自动保存，并按知识库归档");
        flashStatus.accept("已开始新对话");
    }

    private void selectConversation(ConversationView chosen) {
        KnowledgeBase current = currentBase.get();
        if (current == null || active != null && active.id().equals(chosen.id())) return;
        cancelInFlight();
        active = ask.selectConversation(current.id(), chosen.id());
        conversationIdentity = knowledge.identity();
        panel.asking(false);
        showActiveTranscript();
    }

    private void deleteConversation(ConversationView target) {
        KnowledgeBase current = currentBase.get();
        if (current == null) return;
        int answer = JOptionPane.showConfirmDialog(panel,
                "删除对话“" + target.label() + "”？\n该对话的全部问答会从本地数据库中移除，无法恢复。",
                "删除对话", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        try {
            cancelInFlight();
            active = ask.deleteConversation(current.id(), target.id());
            conversationIdentity = knowledge.identity();
            panel.asking(false);
            refreshList();
            showActiveTranscript();
            flashStatus.accept("对话已删除");
        } catch (RuntimeException failure) {
            showError.accept("无法删除对话", failure);
        }
    }

    private void refreshList() {
        KnowledgeBase current = currentBase.get();
        if (current == null) return;
        panel.conversations(ask.conversations(current.id()), active == null ? "" : active.id());
    }

    private void showActiveTranscript() {
        if (active == null) return;
        List<StoredMessage> stored = ask.transcript(active.id());
        panel.showTranscript(stored);
        panel.clearCitations();
        panel.conversationTitle(active.label());
        panel.conversationMeta(stored.isEmpty()
                ? "多轮上下文已启用 · 提问后会自动保存"
                : stored.size() + " 条消息 · 当前 revision " + knowledge.identity().sourceRevision());
    }

    private void installSendShortcut() {
        javax.swing.JTextArea area = panel.questionArea();
        area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ask-send");
        area.getActionMap().put("ask-send", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { askQuestion(); }
        });
        // Keep Shift+Enter as newline (default insert-break behavior).
        area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
                "insert-break");
    }

    private void saveLocalPolicy() {
        try {
            KnowledgeBase current = currentBase.get();
            if (current != null) ask.saveLocalOnly(current.id(), panel.localOnly());
            String message = panel.localOnly()
                    ? "已保存：本知识库只做本地检索，不会发送到远程模型"
                    : "已保存：本知识库允许远程发送，每轮仍会先确认";
            panel.policyStatus(message, Theme.ACCENT);
            flashStatus.accept(message);
        } catch (RuntimeException failure) {
            panel.policyStatus("保存发送策略失败", Theme.RED);
            showError.accept("无法保存发送策略", failure);
        }
    }

    private void openSelectedCitation() {
        CitationView selected = panel.selectedCitation();
        if (selected != null) openCitation.accept(selected);
    }

    private void askQuestion() {
        if (askTask != null && !askTask.isDone()) { askTask.cancel(); return; }
        String question = panel.question();
        if (question.isEmpty()) return;
        if (active == null) reload();
        if (active == null) return;
        ApiConfig config = ask.config();
        try { config.validateForChat(); ask.saveConfig(config); }
        catch (RuntimeException failure) { showError.accept("API 配置不完整", failure); return; }
        panel.asking(true);
        panel.clearQuestion();
        KnowledgeController.TaskIdentity identity = knowledge.identity();
        String conversationId = active.id();
        // Bind the session to conversation + sourceRevision; the store replaces it on a revision change.
        noteContextBreak(identity);
        ask.sessionFor(conversationId, identity);
        panel.beginTurn(question);
        askTask = tasks.<AskResultView, AnswerDelta>submit(identity, knowledge::identity, publish ->
                ask.ask(conversationId, identity, question, config,
                        citations -> { if (isCurrentIdentity(identity)) publishCitations(citations); },
                        this::authorizeRemoteSend,
                        delta -> { if (isCurrentIdentity(identity)) publish.accept(delta); }),
                chunks -> { for (AnswerDelta chunk : chunks) panel.appendAssistantDelta(chunk); },
                answer -> {
                    panel.asking(false);
                    panel.finishAssistant(answer.text(), answer.model(), answer.reasoning());
                    panel.conversationMeta("多轮上下文与 AI 自主检索已启用 · 本轮 " + tokenSummary(answer.usage()));
                    // The turn is saved by now, so the list can show the new title and count.
                    refreshActive(conversationId);
                    flashStatus.accept("问答完成，引用 " + answer.citations().size() + " 个片段 · "
                            + tokenSummary(answer.usage()));
                },
                failure -> {
                    panel.asking(false);
                    panel.failAssistant(failure.getMessage());
                    flashStatus.accept("问答失败");
                },
                () -> {
                    panel.asking(false);
                    panel.stopAssistant();
                    flashStatus.accept("问答已停止");
                });
    }

    /** Re-reads the conversation that just gained a turn, unless the user has moved on to another. */
    private void refreshActive(String conversationId) {
        if (active == null || !active.id().equals(conversationId)) return;
        KnowledgeBase current = currentBase.get();
        if (current == null) return;
        active = ask.selectConversation(current.id(), conversationId);
        panel.conversationTitle(active.label());
        refreshList();
    }

    /** Real consumption reported by the provider, summed over every call this turn made. */
    private static String tokenSummary(TokenUsage usage) {
        if (usage == null || !usage.known()) return "本轮 token 消耗未由 API 返回";
        return "消耗 " + usage.totalTokens() + " tokens（输入 " + usage.promptTokens()
                + " · 输出 " + usage.completionTokens() + "）";
    }

    private boolean isCurrentIdentity(KnowledgeController.TaskIdentity identity) {
        return identity.equals(knowledge.identity());
    }

    private void publishCitations(List<CitationView> citations) {
        SwingUtilities.invokeLater(() -> {
            panel.citations(citations);
            panel.conversationTitle("AI 正在检索相关文件…");
            panel.conversationMeta(citations.isEmpty()
                    ? "当前检索未命中，AI 将尝试调整检索词"
                    : "已汇总 " + citations.size() + " 个片段 · AI 将判断是否继续检索");
        });
    }

    private boolean authorizeRemoteSend(RemoteSendReview review) {
        AtomicInteger choice = new AtomicInteger(2);
        Runnable prompt = () -> {
            StringBuilder scope = new StringBuilder();
            review.citations().stream().limit(8).forEach(citation -> scope.append("\n• ")
                    .append(citation.document().fileName()).append(" · ")
                    .append(citation.document().sourceLocation()));
            if (review.citations().isEmpty()) scope.append("\n（首轮检索未命中，AI 将尝试调整检索词）");
            Object[] options = {"仅本次发送", "信任此 Host 并发送", "取消"};
            choice.set(JOptionPane.showOptionDialog(panel,
                    "即将发送远程 RAG 请求（本轮自动检索）\n\n知识库：" + review.knowledgeBaseName()
                            + "\nRevision：" + review.sourceRevision()
                            + "\n目标 Host：" + review.targetHost()
                            + (review.trustedHost() ? "（已信任）" : "（未信任）")
                            + "\n本轮最多检索：" + review.maxSearches() + " 次 · 最多发送："
                            + review.maxCitations() + " 个片段"
                            + "\n\n首批发送范围（文件与位置，共 " + review.chunkCount() + " 个）：" + scope
                            + "\n\n后续追加检索的新增片段会在引用面板实时显示，不再重复弹窗。",
                    "确认远程发送", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, options, options[review.trustedHost() ? 0 : 2]));
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) prompt.run();
            else SwingUtilities.invokeAndWait(prompt);
        } catch (Exception failure) {
            return false;
        }
        if (choice.get() == 1) ask.trustHost(review.targetHost());
        return choice.get() == 0 || choice.get() == 1;
    }
}
