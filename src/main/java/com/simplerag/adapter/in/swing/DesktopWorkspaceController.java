package com.simplerag.adapter.in.swing;

import com.simplerag.application.conversation.AnswerDelta;
import com.simplerag.application.dto.AskResultView;
import com.simplerag.application.dto.CitationView;
import com.simplerag.application.dto.DocumentReference;
import com.simplerag.application.dto.FileContentView;
import com.simplerag.application.dto.FileNodeView;
import com.simplerag.application.dto.IndexBuildProgress;
import com.simplerag.application.dto.IndexBuildResult;
import com.simplerag.application.dto.SearchResultView;
import com.simplerag.application.dto.RemoteSendReview;
import com.simplerag.application.diagnostics.DiagnosticReportService;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.KnowledgeStats;
import com.simplerag.model.TokenUsage;
import com.simplerag.rag.ApiConfig;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

/** Coordinates desktop page workflows while MainFrame only composes and navigates the window. */
public final class DesktopWorkspaceController {
    private final KnowledgeController knowledge;
    private final SearchController search;
    private final AskController ask;
    private final FileBrowserController browser;
    private final BackgroundTaskCoordinator tasks;
    private final DesktopFileGateway files;
    private final Consumer<KnowledgeBase> activeKnowledgeChanged;
    private final Runnable showFilePage;
    private final StatusBar statusBar = new StatusBar();
    private final AskPanel askPanel;
    private final SearchPanel searchPanel;
    private final KnowledgePanel knowledgePanel;
    private final FileExplorerPanel explorerPanel;
    private final FileViewerPanel viewerPanel;
    private final DiagnosticPanel diagnosticPanel;
    private final SettingsPanel settingsPanel;
    private final Timer searchTimer;
    private final Timer statusResetTimer;
    private final Timer freshnessTimer;
    private BackgroundTaskCoordinator.TaskHandle searchTask;
    private BackgroundTaskCoordinator.TaskHandle highlightTask;
    private BackgroundTaskCoordinator.TaskHandle askTask;
    private BackgroundTaskCoordinator.TaskHandle previewTask;
    /** Identity the bubbles on screen were produced under; drives the context-break marker. */
    private KnowledgeController.TaskIdentity conversationIdentity;

    public DesktopWorkspaceController(KnowledgeController knowledge, SearchController search, AskController ask,
                                      FileBrowserController browser, BackgroundTaskCoordinator tasks,
                                      DesktopFileGateway files, Consumer<KnowledgeBase> activeKnowledgeChanged,
                                      Runnable showFilePage, DiagnosticReportService diagnostics) {
        this.knowledge = knowledge;
        this.search = search;
        this.ask = ask;
        this.browser = browser;
        this.tasks = tasks;
        this.files = files;
        this.activeKnowledgeChanged = activeKnowledgeChanged;
        this.showFilePage = showFilePage;
        this.askPanel = new AskPanel(this::askQuestion, this::saveLocalPolicy,
                this::openCitation, this::clearConversation);
        this.settingsPanel = new SettingsPanel(this::saveApiSettings, this::fetchModels);
        this.searchPanel = new SearchPanel(this::scheduleSearch, this::setPreview,
                this::openSelectedFile, this::openSelectedDirectory, this::copySelectedChunk,
                this::openSelectedResultInApp);
        this.explorerPanel = new FileExplorerPanel(new ExplorerLoader(), new ExplorerActions(),
                this::previewFileNode);
        this.viewerPanel = new FileViewerPanel(this::openFile, this::openFile, this::copyPath);
        this.knowledgePanel = new KnowledgePanel(this::createKnowledgeBase, this::editKnowledgeBase,
                this::deleteKnowledgeBase, this::switchKnowledgeBase, this::rebuildIndex, explorerPanel);
        this.diagnosticPanel = new DiagnosticPanel(diagnostics);
        this.searchTimer = new Timer(180, event -> performSearch());
        searchTimer.setRepeats(false);
        this.statusResetTimer = new Timer(4000, event -> statusBar.status("就绪"));
        statusResetTimer.setRepeats(false);
        this.freshnessTimer = new Timer(1000, event -> refreshFreshnessStatus());
        freshnessTimer.setCoalesce(true);
        freshnessTimer.start();
        installQuestionShortcut();
        settingsPanel.configs(ask.config(), ask.embeddingConfig(), ask.rerankConfig());
        refreshAll();
        setPreview(null);
    }

    public KnowledgePanel knowledgePanel() { return knowledgePanel; }
    public SearchPanel searchPanel() { return searchPanel; }
    public AskPanel askPanel() { return askPanel; }
    public FileViewerPanel fileViewerPanel() { return viewerPanel; }
    public DiagnosticPanel diagnosticPanel() { return diagnosticPanel; }
    public SettingsPanel settingsPanel() { return settingsPanel; }
    public StatusBar statusBar() { return statusBar; }
    public void focusSearch() { searchPanel.focusQuery(); }

    public void initializeKnowledge(Path demoRoot) {
        if (knowledge.sources().isEmpty() && knowledge.stats().chunks() == 0 && Files.isDirectory(demoRoot)) {
            knowledge.addSource(demoRoot.toAbsolutePath().normalize());
            refreshSourcesAndStats();
        }
        if (knowledge.stats().chunks() == 0 || (knowledge.semanticModelConfigured()
                && !knowledge.semanticEnabled() && !knowledge.sources().isEmpty())) rebuildIndex();
    }

    public void close() {
        freshnessTimer.stop(); searchTimer.stop(); statusResetTimer.stop(); clearTasks();
    }

    private void installQuestionShortcut() {
        javax.swing.JTextArea area = askPanel.questionArea();
        area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ask-send");
        area.getActionMap().put("ask-send", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { askQuestion(); }
        });
        // Keep Shift+Enter as newline (default insert-break behavior).
        area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK), "insert-break");
    }
    private void refreshAll() { refreshKnowledgeBases(); refreshSourcesAndStats(); }
    private void refreshKnowledgeBases() {
        KnowledgeBase current = knowledge.current();
        knowledgePanel.knowledgeBases(knowledge.knowledgeBases(), current);
        if (current != null) {
            activeKnowledgeChanged.accept(current);
            askPanel.localOnly(ask.localOnly(current.id()));
            // The switch is per knowledge base, so a confirmation from the previous one must not linger.
            askPanel.policyStatus("", Theme.MUTED);
        }
    }
    private void refreshSourcesAndStats() {
        explorerPanel.reload();
        KnowledgeStats stats = knowledge.stats();
        knowledgePanel.stats(stats);
        statusBar.semantic(knowledge.semanticStatus(), knowledge.semanticEnabled());
        KnowledgeBase current = knowledge.current();
        statusBar.freshness(knowledge.freshnessStatus(), current != null && !current.freshnessReason().isBlank());
        searchPanel.extensions(knowledge.extensions());
    }
    private void refreshFreshnessStatus() {
        try {
            statusBar.semantic(knowledge.semanticStatus(), knowledge.semanticEnabled());
            KnowledgeBase current = knowledge.current();
            statusBar.freshness(knowledge.freshnessStatus(), current != null && !current.freshnessReason().isBlank());
            // A marker appended mid-answer would land under the streaming bubble, so a turn in
            // flight keeps its identity; askQuestion() inserts the marker before the next turn.
            if (askTask == null || askTask.isDone()) noteContextBreak(knowledge.identity());
        } catch (RuntimeException ignored) { }
    }

    /**
     * Conversation sessions are keyed by knowledgeBaseId + sourceRevision, so a source change makes
     * the store hand out a fresh session while the bubbles already on screen stay. The transcript
     * says where the model's memory restarted instead of presenting both halves as one context.
     */
    private void noteContextBreak(KnowledgeController.TaskIdentity identity) {
        if (identity == null) return;
        KnowledgeController.TaskIdentity previous = conversationIdentity;
        conversationIdentity = identity;
        if (previous == null || previous.equals(identity)) return;
        // A knowledge-base switch reloads the transcript from that base's own session instead.
        if (!previous.knowledgeBaseId().equals(identity.knowledgeBaseId())) return;
        askPanel.contextBreak("源文件已变化（revision " + identity.sourceRevision()
                + "）· 模型从这里开始新的上下文，不再记得上面的对话");
    }

    private void createKnowledgeBase() {
        KnowledgeBaseInput input = showKnowledgeBaseDialog("新建知识库", "", "");
        if (input == null) return;
        try { knowledge.create(input.name(), input.description()); clearWorkspace(); refreshAll(); statusBar.status("知识库已创建"); }
        catch (RuntimeException failure) { showError("无法创建知识库", failure); }
    }
    private void editKnowledgeBase() {
        KnowledgeBase selected = knowledgePanel.selectedKnowledgeBase(); if (selected == null) return;
        KnowledgeBaseInput input = showKnowledgeBaseDialog("编辑知识库", selected.name(), selected.description());
        if (input == null) return;
        try { knowledge.updateCurrent(input.name(), input.description()); refreshKnowledgeBases(); statusBar.status("知识库信息已更新"); }
        catch (RuntimeException failure) { showError("无法更新知识库", failure); }
    }
    private void deleteKnowledgeBase() {
        KnowledgeBase selected = knowledgePanel.selectedKnowledgeBase(); if (selected == null) return;
        int answer = JOptionPane.showConfirmDialog(knowledgePanel,
                "删除知识库“" + selected.name() + "”？\n源文件不会被删除，但该知识库的索引会被清理。",
                "删除知识库", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        try { knowledge.delete(selected.id()); clearWorkspace(); refreshAll(); statusBar.status("知识库已删除"); }
        catch (Exception failure) { showError("无法删除知识库", failure); }
    }
    private void switchKnowledgeBase() {
        KnowledgeBase selected = knowledgePanel.selectedKnowledgeBase(); KnowledgeBase current = knowledge.current();
        if (selected == null || current != null && selected.id().equals(current.id())) return;
        try { knowledge.select(selected.id()); clearWorkspace(); refreshSourcesAndStats(); activeKnowledgeChanged.accept(selected); statusBar.status("已切换到 “" + selected.name() + "”"); }
        catch (RuntimeException failure) { showError("无法切换知识库", failure); }
    }
    private void chooseSource() {
        JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle("选择数据源目录");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY); chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(knowledgePanel) == JFileChooser.APPROVE_OPTION) {
            for (java.io.File selected : chooser.getSelectedFiles()) knowledge.addSource(selected.toPath());
            refreshSourcesAndStats(); rebuildIndex();
        }
    }
    private void removeSource(Path root) {
        int answer = JOptionPane.showConfirmDialog(knowledgePanel,
                "从当前知识库移除该数据源目录？\n" + root + "\n\n源文件不会被删除，但该目录的内容会退出索引。",
                "移除数据源", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        knowledge.removeSource(root); viewerPanel.empty(); refreshSourcesAndStats(); rebuildIndex();
    }

    private void rebuildIndex() {
        setIndexing(true, "正在扫描当前知识库...");
        KnowledgeController.TaskIdentity identity = knowledge.identity();
        tasks.<IndexBuildResult, IndexBuildProgress>submit(identity, knowledge::identity,
                publish -> knowledge.rebuild(publish), values -> {
                    IndexBuildProgress latest = values.get(values.size() - 1);
                    knowledgePanel.progress(latest.processed(), latest.total());
                    statusBar.status("正在" + latest.stage() + " " + latest.currentFile().getFileName()
                            + "  ·  " + latest.processed() + "/" + latest.total());
                }, report -> { refreshSourcesAndStats(); setIndexing(false,
                        "索引完成：" + report.files() + " 个文件，" + report.chunks() + " 个片段"
                                + (report.skipped() == 0 ? "" : "，跳过 " + report.skipped() + " 个"));
                    showIndexWarnings(report); performSearch(); },
                failure -> { refreshSourcesAndStats(); setIndexing(false, "索引失败"); showError("无法建立索引", failure); },
                () -> { refreshSourcesAndStats(); setIndexing(false, "索引结果已因知识库版本变化而丢弃"); });
    }

    private void performSearch() {
        String query = searchPanel.query(); if (searchTask != null) searchTask.cancel();
        if (query.isEmpty()) { searchPanel.clear(); return; }
        String extension = searchPanel.extension(); KnowledgeController.TaskIdentity identity = knowledge.identity();
        searchPanel.searching();
        searchTask = tasks.submit(identity, knowledge::identity,
                ignored -> search.search(identity, query, 80, extension), null, results -> {
                    if (!query.equals(searchPanel.query())) return;
                    searchPanel.results(results); statusBar.status(results.isEmpty() ? "当前知识库没有相关结果" : "检索完成");
                }, failure -> { searchPanel.searchFailed(); statusBar.status(failure.getMessage()); }, () -> { });
    }
    private void setPreview(SearchResultView result) {
        if (highlightTask != null) highlightTask.cancel(); searchPanel.preview(result);
        if (result != null) locateSemanticMatches(result);
    }
    private void locateSemanticMatches(SearchResultView result) {
        String query = searchPanel.query(); DocumentReference document = result.document();
        if (query.isEmpty() || !document.semanticAvailable() || !knowledge.semanticEnabled()) return;
        KnowledgeController.TaskIdentity identity = knowledge.identity(); searchPanel.semanticLoading(document);
        highlightTask = tasks.submit(identity, knowledge::identity,
                ignored -> search.highlights(identity, query, document, 2), null, highlights -> {
                    SearchResultView selected = searchPanel.selected();
                    if (selected == null || !selected.document().id().equals(document.id()) || !query.equals(searchPanel.query())) return;
                    searchPanel.semanticHighlights(document, highlights);
                }, failure -> searchPanel.semanticReset(document), () -> searchPanel.semanticReset(document));
    }

    private void saveLocalPolicy() {
        try {
            KnowledgeBase current = knowledge.current();
            if (current != null) ask.saveLocalOnly(current.id(), askPanel.localOnly());
            String message = askPanel.localOnly()
                    ? "已保存：本知识库只做本地检索，不会发送到远程模型"
                    : "已保存：本知识库允许远程发送，每轮仍会先确认";
            askPanel.policyStatus(message, Theme.ACCENT);
            flashStatus(message);
        } catch (RuntimeException failure) {
            askPanel.policyStatus("保存发送策略失败", Theme.RED);
            showError("无法保存发送策略", failure);
        }
    }

    private void saveApiSettings() {
        try {
            ask.saveConfig(settingsPanel.chatConfig());
            ask.saveEmbeddingConfig(settingsPanel.embeddingConfig());
            ask.saveRerankConfig(settingsPanel.rerankConfig());
            settingsPanel.configs(ask.config(), ask.embeddingConfig(), ask.rerankConfig());
            settingsPanel.status("全部 API 配置已安全保存；若切换向量模型，请重建索引", Theme.ACCENT);
        }
        catch (RuntimeException failure) { showError("无法保存 API 配置", failure); }
    }
    private void fetchModels(SettingsPanel.ModelKind kind, JButton button) {
        ApiConfig config = switch (kind) {
            case CHAT -> settingsPanel.chatConfig();
            case EMBEDDING -> settingsPanel.embeddingConfig().asApiConfig();
            case RERANK -> settingsPanel.rerankConfig().asApiConfig();
        };
        ApiConfig requestConfig = modelListConfig(config, kind);
        button.setEnabled(false); settingsPanel.status("正在连接 " + kindLabel(kind) + " API 并获取模型...", Theme.MUTED);
        tasks.<List<String>, Void>submit(null, null, ignored -> ask.fetchModels(requestConfig), null, models -> {
            button.setEnabled(true);
            Object previous = settingsPanel.modelEditorValue(kind);
            settingsPanel.models(kind, models, previous);
            settingsPanel.status(models.isEmpty() ? kindLabel(kind) + " API 未返回可用模型"
                    : kindLabel(kind) + " API 已获取 " + models.size() + " 个模型", Theme.ACCENT);
        }, failure -> { button.setEnabled(true); settingsPanel.status(failure.getMessage(), Theme.RED); }, () -> button.setEnabled(true));
    }

    private static String kindLabel(SettingsPanel.ModelKind kind) {
        return switch (kind) {
            case CHAT -> "对话模型";
            case EMBEDDING -> "向量模型";
            case RERANK -> "重排模型";
        };
    }

    static ApiConfig modelListConfig(ApiConfig config, SettingsPanel.ModelKind kind) {
        String base = config.normalizedBaseUrl();
        String suffix = switch (kind) {
            case CHAT -> "/chat/completions";
            case EMBEDDING -> "/embeddings";
            case RERANK -> "/rerank";
        };
        if (base.endsWith(suffix)) base = base.substring(0, base.length() - suffix.length());
        return new ApiConfig(base, config.apiKey(), config.model());
    }
    private void askQuestion() {
        if (askTask != null && !askTask.isDone()) { askTask.cancel(); return; }
        String question = askPanel.question(); if (question.isEmpty()) return; ApiConfig config = ask.config();
        try { config.validateForChat(); ask.saveConfig(config); }
        catch (RuntimeException failure) { showError("API 配置不完整", failure); return; }
        askPanel.asking(true);
        askPanel.clearQuestion();
        KnowledgeController.TaskIdentity identity = knowledge.identity();
        // Bind UI session to knowledgeBaseId + sourceRevision; store replaces session on revision change.
        noteContextBreak(identity);
        ask.sessionFor(identity);
        askPanel.beginTurn(question);
        askTask = tasks.<AskResultView, AnswerDelta>submit(identity, knowledge::identity, publish ->
                ask.ask(identity, question, config,
                        citations -> { if (isCurrentIdentity(identity)) publishCitations(citations); },
                        this::authorizeRemoteSend,
                        delta -> { if (isCurrentIdentity(identity)) publish.accept(delta); }), chunks -> {
                    for (AnswerDelta chunk : chunks) askPanel.appendAssistantDelta(chunk);
                }, answer -> {
                    askPanel.asking(false);
                    askPanel.finishAssistant(answer.text(), answer.model(), answer.reasoning());
                    askPanel.conversationMeta("多轮上下文与 AI 自主检索已启用 · 本轮 " + tokenSummary(answer.usage()));
                    flashStatus("问答完成，引用 " + answer.citations().size() + " 个片段 · " + tokenSummary(answer.usage()));
                }, failure -> {
                    askPanel.asking(false);
                    askPanel.failAssistant(failure.getMessage());
                    flashStatus("问答失败");
                },
                () -> {
                    askPanel.asking(false);
                    askPanel.stopAssistant();
                    flashStatus("问答已停止");
                });
    }

    /** Real consumption reported by the provider, summed over every call this turn made. */
    private static String tokenSummary(TokenUsage usage) {
        if (usage == null || !usage.known()) return "本轮 token 消耗未由 API 返回";
        return "消耗 " + usage.totalTokens() + " tokens（输入 " + usage.promptTokens()
                + " · 输出 " + usage.completionTokens() + "）";
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
            choice.set(JOptionPane.showOptionDialog(askPanel,
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
            if (javax.swing.SwingUtilities.isEventDispatchThread()) prompt.run();
            else javax.swing.SwingUtilities.invokeAndWait(prompt);
        } catch (Exception failure) {
            return false;
        }
        if (choice.get() == 1) ask.trustHost(review.targetHost());
        return choice.get() == 0 || choice.get() == 1;
    }
    private void publishCitations(List<CitationView> citations) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            askPanel.citations(citations);
            askPanel.conversationTitle("AI 正在检索相关文件…");
            askPanel.conversationMeta(citations.isEmpty()
                    ? "当前检索未命中，AI 将尝试调整检索词"
                    : "已汇总 " + citations.size() + " 个片段 · AI 将判断是否继续检索");
        });
    }

    private void clearConversation() {
        if (askTask != null && !askTask.isDone()) askTask.cancel();
        KnowledgeController.TaskIdentity identity = knowledge.identity();
        ask.clearSession(identity);
        conversationIdentity = identity;
        askPanel.asking(false);
        askPanel.resetConversation("对话已清空", "多轮上下文已重置 · 仍绑定当前知识库版本");
        flashStatus("对话已清空");
    }

    private void clearWorkspace() {
        clearTasks();
        askPanel.asking(false);
        searchPanel.clear();
        explorerPanel.clear();
        viewerPanel.empty();
        reloadConversationUi();
        setPreview(null);
    }

    /** Sync chat transcript with in-memory session for current knowledgeBaseId + sourceRevision. */
    private void reloadConversationUi() {
        KnowledgeController.TaskIdentity identity = knowledge.identity();
        var session = ask.sessionFor(identity);
        conversationIdentity = identity;
        if (session.isEmpty()) {
            askPanel.resetConversation("对话", "多轮上下文已启用 · 历史绑定 knowledgeBaseId + sourceRevision");
        } else {
            askPanel.showMessages(session.messages());
            askPanel.conversationTitle("对话");
            askPanel.conversationMeta(session.size() + " 条消息 · revision " + identity.sourceRevision());
        }
    }
    private void clearTasks() {
        if (searchTask != null && !searchTask.isDone()) searchTask.cancel();
        if (highlightTask != null && !highlightTask.isDone()) highlightTask.cancel();
        if (askTask != null && !askTask.isDone()) askTask.cancel();
        if (previewTask != null && !previewTask.isDone()) previewTask.cancel();
    }

    /**
     * Single click previews, double click opens the file page - the same split editors use, so
     * browsing the tree never blocks on reading a large document.
     */
    private void previewFileNode(FileNodeView node) {
        if (previewTask != null) previewTask.cancel();
        if (node == null) { viewerPanel.empty(); return; }
        if (node.directory()) { viewerPanel.folder(node); return; }
        viewerPanel.loading(node);
        KnowledgeController.TaskIdentity identity = knowledge.identity();
        previewTask = tasks.<FileContentView, Void>submit(identity, knowledge::identity,
                ignored -> browser.read(identity, node.path()), null,
                content -> viewerPanel.show(node, content),
                failure -> viewerPanel.failed(node, failure.getMessage()), () -> { });
    }

    private void copyPath(Path path) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new StringSelection(path.toString()), null);
        flashStatus("路径已复制");
    }

    private final class ExplorerLoader implements FileExplorerPanel.Loader {
        @Override
        public void loadRoots(Consumer<List<FileNodeView>> onLoaded, Consumer<String> onFailed) {
            KnowledgeController.TaskIdentity identity = knowledge.identity();
            tasks.<List<FileNodeView>, Void>submit(identity, knowledge::identity,
                    ignored -> browser.roots(identity), null, onLoaded,
                    failure -> onFailed.accept(reason(failure)), () -> { });
        }

        @Override
        public void loadChildren(Path directory, Consumer<List<FileNodeView>> onLoaded,
                                 Consumer<String> onFailed) {
            KnowledgeController.TaskIdentity identity = knowledge.identity();
            tasks.<List<FileNodeView>, Void>submit(identity, knowledge::identity,
                    ignored -> browser.children(identity, directory), null, onLoaded,
                    failure -> onFailed.accept(reason(failure)), () -> { });
        }

        private String reason(Throwable failure) {
            String message = failure.getMessage();
            return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
        }
    }

    private final class ExplorerActions implements FileExplorerPanel.Actions {
        @Override public void addFolder() { chooseSource(); }
        @Override public void removeRoot(Path root) { removeSource(root); }
        @Override public void openInApp(FileNodeView node) { showFilePage.run(); previewFileNode(node); }
        @Override public void openWithSystem(Path path) { openFile(path); }
        @Override public void revealInSystem(Path path) { openFile(path); }
        @Override public void copyPath(Path path) { DesktopWorkspaceController.this.copyPath(path); }
    }
    private boolean isCurrentIdentity(KnowledgeController.TaskIdentity identity) { return identity.equals(knowledge.identity()); }
    private void openSelectedFile() { SearchResultView selected = searchPanel.selected(); if (selected != null) openFile(selected.document().path()); }
    private void openSelectedDirectory() { SearchResultView selected = searchPanel.selected(); if (selected != null) openFile(selected.document().path().getParent()); }
    private void openSelectedResultInApp() {
        SearchResultView selected = searchPanel.selected();
        if (selected != null) openInApp(selected.document());
    }
    private void openCitation() {
        CitationView selected = askPanel.selectedCitation();
        if (selected != null) openInApp(selected.document());
    }

    /**
     * Answer or search hit to source without leaving the application: the file page renders the same
     * extracted text the index holds, so the cited chunk can be highlighted where it actually sits.
     */
    private void openInApp(DocumentReference document) {
        if (previewTask != null) previewTask.cancel();
        showFilePage.run();
        viewerPanel.loading(document.path());
        KnowledgeController.TaskIdentity identity = knowledge.identity();
        previewTask = tasks.<FileBrowserController.FileOpen, Void>submit(identity, knowledge::identity,
                ignored -> browser.open(identity, document.path()), null,
                opened -> {
                    viewerPanel.show(opened.node(), opened.content(), document);
                    flashStatus("已定位 " + document.fileName() + "  ·  " + document.sourceLocation());
                },
                failure -> viewerPanel.failed(document.path(), failure.getMessage()), () -> { });
    }

    private void openFile(Path path) { try { files.open(path); } catch (IOException failure) { showError("无法打开文件", failure); } }
    private void showIndexWarnings(IndexBuildResult report) {
        if (report.warnings().isEmpty()) return;
        StringBuilder message = new StringBuilder("以下文档未完整进入索引：\n\n");
        report.warnings().stream().limit(12).forEach(warning -> message.append("• ")
                .append(warning.path().getFileName()).append(" [").append(warning.readerId()).append("]\n  ")
                .append(warning.message()).append('\n'));
        if (report.warnings().size() > 12) {
            message.append("\n另有 ").append(report.warnings().size() - 12).append(" 条警告。");
        }
        JOptionPane.showMessageDialog(statusBar, message.toString(), "索引完成（含警告）",
                JOptionPane.WARNING_MESSAGE);
    }
    private void copySelectedChunk() { SearchResultView selected = searchPanel.selected(); if (selected != null) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(selected.document().content()), null); flashStatus("片段已复制"); } }
    private void scheduleSearch() { searchTimer.restart(); }
    private void flashStatus(String message) { statusBar.status(message); statusResetTimer.restart(); }
    private void setIndexing(boolean indexing, String status) { knowledgePanel.indexing(indexing); statusBar.status(status); }

    private KnowledgeBaseInput showKnowledgeBaseDialog(String title, String name, String description) {
        JTextField nameField = new JTextField(name, 28); JTextArea descriptionField = new JTextArea(description, 4, 28);
        descriptionField.setLineWrap(true); descriptionField.setWrapStyleWord(true); JPanel panel = new JPanel(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel nameLabel = new JLabel("名称"); nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT); nameField.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel descriptionLabel = new JLabel("描述"); descriptionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JScrollPane descriptionScroll = new JScrollPane(descriptionField); descriptionScroll.setPreferredSize(new Dimension(380, 90)); descriptionScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(nameLabel); panel.add(Box.createVerticalStrut(5)); panel.add(nameField); panel.add(Box.createVerticalStrut(12)); panel.add(descriptionLabel); panel.add(Box.createVerticalStrut(5)); panel.add(descriptionScroll);
        int result = JOptionPane.showConfirmDialog(knowledgePanel, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        return result == JOptionPane.OK_OPTION ? new KnowledgeBaseInput(nameField.getText(), descriptionField.getText()) : null;
    }
    private void showError(String title, Throwable failure) {
        Throwable cause = failure; while (cause.getCause() != null) cause = cause.getCause(); String message = cause.getMessage();
        JOptionPane.showMessageDialog(knowledgePanel, message == null ? failure.toString() : message, title, JOptionPane.ERROR_MESSAGE);
    }
    private record KnowledgeBaseInput(String name, String description) { }
}
