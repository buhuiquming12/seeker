package com.simplerag.adapter.in.swing;

import com.simplerag.application.dto.DocumentReference;
import com.simplerag.application.dto.FileContentView;
import com.simplerag.application.dto.FileNodeView;
import com.simplerag.application.dto.IndexBuildProgress;
import com.simplerag.application.dto.IndexBuildResult;
import com.simplerag.application.dto.SearchResultView;
import com.simplerag.application.diagnostics.DiagnosticReportService;
import com.simplerag.application.port.in.ManageWorkspaceLayout;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.KnowledgeStats;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Coordinates desktop page workflows while MainFrame only composes and navigates the window. */
public final class DesktopWorkspaceController {
    private final KnowledgeController knowledge;
    private final SearchController search;
    private final FileBrowserController browser;
    private final BackgroundTaskCoordinator tasks;
    private final DesktopFileGateway files;
    private final Consumer<KnowledgeBase> activeKnowledgeChanged;
    private final Runnable showFilePage;
    private final StatusBar statusBar = new StatusBar();
    private final ConversationCoordinator conversations;
    private final ModelSettingsCoordinator settings;
    private final WorkspaceLayoutCoordinator layout;
    private final SearchPanel searchPanel;
    private final KnowledgePanel knowledgePanel;
    private final FileExplorerPanel explorerPanel;
    private final FileViewerPanel viewerPanel;
    private final DiagnosticPanel diagnosticPanel;
    private final Timer searchTimer;
    private final Timer statusResetTimer;
    private final Timer freshnessTimer;
    private BackgroundTaskCoordinator.TaskHandle searchTask;
    private BackgroundTaskCoordinator.TaskHandle highlightTask;
    private BackgroundTaskCoordinator.TaskHandle previewTask;

    public DesktopWorkspaceController(KnowledgeController knowledge, SearchController search, AskController ask,
                                      FileBrowserController browser, BackgroundTaskCoordinator tasks,
                                      DesktopFileGateway files, ManageWorkspaceLayout layout,
                                      Consumer<KnowledgeBase> activeKnowledgeChanged,
                                      Runnable showFilePage, DiagnosticReportService diagnostics) {
        this.knowledge = knowledge;
        this.search = search;
        this.browser = browser;
        this.tasks = tasks;
        this.files = files;
        this.activeKnowledgeChanged = activeKnowledgeChanged;
        this.showFilePage = showFilePage;
        this.conversations = new ConversationCoordinator(ask, knowledge, tasks, knowledge::current,
                this::flashStatus, this::openInApp, this::showError);
        this.settings = new ModelSettingsCoordinator(ask, knowledge, tasks, this::flashStatus,
                this::refreshSourcesAndStats, this::showError);
        this.layout = new WorkspaceLayoutCoordinator(layout, this::applyContentScale, this::flashStatus);
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
        refreshAll();
        conversations.reload();
        setPreview(null);
    }

    public KnowledgePanel knowledgePanel() { return knowledgePanel; }
    public SearchPanel searchPanel() { return searchPanel; }
    public AskPanel askPanel() { return conversations.panel(); }
    public FileViewerPanel fileViewerPanel() { return viewerPanel; }
    public DiagnosticPanel diagnosticPanel() { return diagnosticPanel; }
    public SettingsPanel settingsPanel() { return settings.panel(); }
    public StatusBar statusBar() { return statusBar; }
    public void focusSearch() { searchPanel.focusQuery(); }
    public void restoreWindow(JFrame frame, JSplitPane split) { layout.restore(frame, split); }
    public void zoomContent(int deltaPercent) { layout.zoomContent(deltaPercent); }

    public void initializeKnowledge(Path demoRoot) {
        if (knowledge.sources().isEmpty() && knowledge.stats().chunks() == 0 && Files.isDirectory(demoRoot)) {
            knowledge.addSource(demoRoot.toAbsolutePath().normalize());
            refreshSourcesAndStats();
        }
        if (knowledge.stats().chunks() == 0 || (knowledge.semanticModelConfigured()
                && !knowledge.semanticEnabled() && !knowledge.sources().isEmpty())) rebuildIndex();
    }

    public void close() {
        freshnessTimer.stop(); searchTimer.stop(); statusResetTimer.stop();
        layout.close();
        clearTasks();
        settings.close();
    }

    private void applyContentScale() {
        askPanel().applyContentScale();
        searchPanel.applyContentScale();
        viewerPanel.applyContentScale();
    }

    private void refreshAll() { refreshKnowledgeBases(); refreshSourcesAndStats(); }
    private void refreshKnowledgeBases() {
        KnowledgeBase current = knowledge.current();
        knowledgePanel.knowledgeBases(knowledge.knowledgeBases(), current);
        if (current != null) {
            activeKnowledgeChanged.accept(current);
            conversations.applyPolicy(current);
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
            // flight keeps its identity; the next question inserts the marker before it starts.
            if (conversations.idle()) conversations.noteContextBreak(knowledge.identity());
        } catch (RuntimeException ignored) { }
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
                "删除知识库“" + selected.name() + "”？\n源文件不会被删除，但该知识库的索引与对话记录会被清理。",
                "删除知识库", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        try { knowledge.delete(selected.id()); clearWorkspace(); refreshAll(); statusBar.status("知识库已删除"); }
        catch (Exception failure) { showError("无法删除知识库", failure); }
    }
    private void switchKnowledgeBase() {
        KnowledgeBase selected = knowledgePanel.selectedKnowledgeBase(); KnowledgeBase current = knowledge.current();
        if (selected == null || current != null && selected.id().equals(current.id())) return;
        try { knowledge.select(selected.id()); clearWorkspace(); refreshSourcesAndStats(); activeKnowledgeChanged.accept(selected); conversations.applyPolicy(selected); statusBar.status("已切换到 “" + selected.name() + "”"); }
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

    private void clearWorkspace() {
        clearTasks();
        searchPanel.clear();
        explorerPanel.clear();
        viewerPanel.empty();
        conversations.reload();
        setPreview(null);
    }

    private void clearTasks() {
        if (searchTask != null && !searchTask.isDone()) searchTask.cancel();
        if (highlightTask != null && !highlightTask.isDone()) highlightTask.cancel();
        if (previewTask != null && !previewTask.isDone()) previewTask.cancel();
        conversations.close();
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
                    failure -> onFailed.accept(ModelSettingsCoordinator.reason(failure)), () -> { });
        }

        @Override
        public void loadChildren(Path directory, Consumer<List<FileNodeView>> onLoaded,
                                 Consumer<String> onFailed) {
            KnowledgeController.TaskIdentity identity = knowledge.identity();
            tasks.<List<FileNodeView>, Void>submit(identity, knowledge::identity,
                    ignored -> browser.children(identity, directory), null, onLoaded,
                    failure -> onFailed.accept(ModelSettingsCoordinator.reason(failure)), () -> { });
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

    private void openSelectedFile() { SearchResultView selected = searchPanel.selected(); if (selected != null) openFile(selected.document().path()); }
    private void openSelectedDirectory() { SearchResultView selected = searchPanel.selected(); if (selected != null) openFile(selected.document().path().getParent()); }
    private void openSelectedResultInApp() {
        SearchResultView selected = searchPanel.selected();
        if (selected != null) openInApp(selected.document());
    }
    private void openInApp(com.simplerag.application.dto.CitationView citation) { openInApp(citation.document()); }

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
