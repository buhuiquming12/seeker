package com.simplerag.adapter.in.swing;

import com.simplerag.application.dto.LocalModelView;
import com.simplerag.application.dto.ModelDownloadProgress;
import com.simplerag.rag.ApiConfig;

import javax.swing.JButton;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The settings page: provider credentials, model lists and the local embedding model.
 *
 * <p>None of it belongs to a knowledge base, which is why it is its own coordinator - the model is
 * installed once for the machine, and a download must survive switching bases or rebuilding an index.
 */
final class ModelSettingsCoordinator {
    private final SettingsPanel panel;
    private final AskController ask;
    private final KnowledgeController knowledge;
    private final BackgroundTaskCoordinator tasks;
    private final Consumer<String> flashStatus;
    private final Runnable onModelChanged;
    private final BiConsumer<String, Throwable> showError;
    private BackgroundTaskCoordinator.TaskHandle modelTask;

    ModelSettingsCoordinator(AskController ask, KnowledgeController knowledge,
                             BackgroundTaskCoordinator tasks, Consumer<String> flashStatus,
                             Runnable onModelChanged, BiConsumer<String, Throwable> showError) {
        this.ask = ask;
        this.knowledge = knowledge;
        this.tasks = tasks;
        this.flashStatus = flashStatus;
        this.onModelChanged = onModelChanged;
        this.showError = showError;
        this.panel = new SettingsPanel(this::saveApiSettings, this::fetchModels, this::downloadLocalModel);
        panel.configs(ask.config(), ask.embeddingConfig(), ask.rerankConfig());
        refreshLocalModel();
    }

    SettingsPanel panel() { return panel; }

    void close() {
        if (modelTask != null && !modelTask.isDone()) modelTask.cancel();
    }

    private void saveApiSettings() {
        try {
            ask.saveConfig(panel.chatConfig());
            ask.saveEmbeddingConfig(panel.embeddingConfig());
            ask.saveRerankConfig(panel.rerankConfig());
            panel.configs(ask.config(), ask.embeddingConfig(), ask.rerankConfig());
            panel.status("全部 API 配置已安全保存；若切换向量模型，请重建索引", Theme.ACCENT);
        } catch (RuntimeException failure) {
            showError.accept("无法保存 API 配置", failure);
        }
    }

    private void refreshLocalModel() { panel.localModel(knowledge.localModel()); }

    /**
     * Installs the local embedding model from the settings page.
     *
     * <p>Deliberately submitted without a task identity: the model belongs to the installation, so a
     * knowledge-base switch must not discard it. Cancellation happens only on close.
     */
    private void downloadLocalModel() {
        if (modelTask != null && !modelTask.isDone()) return;
        panel.modelDownloading("正在连接镜像…", -1);
        flashStatus.accept("正在下载本地语义模型…");
        modelTask = tasks.<LocalModelView, ModelDownloadProgress>submit(null, null,
                knowledge::installLocalModel,
                reports -> {
                    ModelDownloadProgress latest = reports.get(reports.size() - 1);
                    panel.modelDownloading(describe(latest), latest.percent());
                },
                view -> {
                    panel.localModel(view);
                    onModelChanged.run();
                    boolean ready = view.installed();
                    panel.status(ready
                                    ? "本地语义模型已就绪 · 重建索引后语义检索才会生效"
                                    : "下载结束，但模型文件仍不完整，请重试",
                            ready ? Theme.ACCENT : Theme.RED);
                    flashStatus.accept(ready ? "语义模型下载完成，请重建索引" : "语义模型仍不完整");
                },
                failure -> {
                    refreshLocalModel();
                    panel.status("模型下载失败：" + reason(failure), Theme.RED);
                    flashStatus.accept("语义模型下载失败");
                },
                () -> { refreshLocalModel(); flashStatus.accept("语义模型下载已取消"); });
    }

    private void fetchModels(SettingsPanel.ModelKind kind, JButton button) {
        ApiConfig config = switch (kind) {
            case CHAT -> panel.chatConfig();
            case EMBEDDING -> panel.embeddingConfig().asApiConfig();
            case RERANK -> panel.rerankConfig().asApiConfig();
        };
        ApiConfig requestConfig = modelListConfig(config, kind);
        button.setEnabled(false);
        panel.status("正在连接 " + kindLabel(kind) + " API 并获取模型...", Theme.MUTED);
        tasks.<List<String>, Void>submit(null, null, ignored -> ask.fetchModels(requestConfig), null,
                models -> {
                    button.setEnabled(true);
                    Object previous = panel.modelEditorValue(kind);
                    panel.models(kind, models, previous);
                    panel.status(models.isEmpty() ? kindLabel(kind) + " API 未返回可用模型"
                            : kindLabel(kind) + " API 已获取 " + models.size() + " 个模型", Theme.ACCENT);
                },
                failure -> { button.setEnabled(true); panel.status(failure.getMessage(), Theme.RED); },
                () -> button.setEnabled(true));
    }

    private static String describe(ModelDownloadProgress progress) {
        String size = progress.totalBytes() > 0
                ? FileStatusStyle.size(progress.bytes()) + " / " + FileStatusStyle.size(progress.totalBytes())
                : FileStatusStyle.size(progress.bytes());
        return "正在下载 " + progress.file() + "（" + progress.fileIndex() + "/" + progress.fileCount()
                + "） · " + size;
    }

    private static String kindLabel(SettingsPanel.ModelKind kind) {
        return switch (kind) {
            case CHAT -> "对话模型";
            case EMBEDDING -> "向量模型";
            case RERANK -> "重排模型";
        };
    }

    /** A configured endpoint may already be the full path, which the model list must not repeat. */
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

    static String reason(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
