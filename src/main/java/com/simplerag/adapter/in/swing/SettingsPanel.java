package com.simplerag.adapter.in.swing;

import com.simplerag.application.dto.LocalModelView;
import com.simplerag.rag.ApiConfig;
import com.simplerag.rag.ModelApiConfig;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Central settings page for the local model and the chat, embedding and reranking APIs. */
public final class SettingsPanel extends JPanel {
    private final ApiFields chat = new ApiFields(false, false);
    private final ApiFields embedding = new ApiFields(true, true);
    private final ApiFields rerank = new ApiFields(true, false);
    private final JLabel status = new JLabel("API Key 会加密保存，不会写入索引或诊断日志");
    private final JLabel modelStatus = new JLabel("正在检查本地语义模型…");
    private final JProgressBar modelProgress = new JProgressBar();
    private final JButton downloadModel = new JButton("下载模型");

    public SettingsPanel(Runnable onSave, BiConsumer<ModelKind, JButton> onFetchModels,
                         Runnable onDownloadModel) {
        super(new BorderLayout());
        Theme.opaque(this, Theme.BACKGROUND);
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        Theme.opaque(body, Theme.BACKGROUND);
        body.setBorder(Theme.padding(24, 28, 28, 28));
        body.add(header());
        body.add(Box.createVerticalStrut(18));
        body.add(localModelSection(onDownloadModel));
        body.add(Box.createVerticalStrut(14));
        body.add(section("对话模型", "用于知识问答、流式生成与追加检索决策", chat,
                ModelKind.CHAT, onFetchModels));
        body.add(Box.createVerticalStrut(14));
        body.add(section("向量模型", "支持 OpenAI 兼容的 /embeddings；关闭时使用本地 ONNX 模型",
                embedding, ModelKind.EMBEDDING, onFetchModels));
        body.add(Box.createVerticalStrut(14));
        body.add(section("重排模型", "支持常见的 /rerank 接口；请求失败时自动回退到本地规则重排",
                rerank, ModelKind.RERANK, onFetchModels));
        body.add(Box.createVerticalStrut(16));
        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setOpaque(false);
        status.setForeground(Theme.MUTED);
        footer.add(status, BorderLayout.CENTER);
        JButton save = new JButton("保存全部设置");
        Theme.styleButton(save, true);
        save.addActionListener(event -> onSave.run());
        footer.add(save, BorderLayout.EAST);
        footer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        body.add(footer);
        body.add(Box.createVerticalGlue());
        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        add(scroll, BorderLayout.CENTER);
    }

    public ApiConfig chatConfig() { return chat.apiConfig(); }
    public ModelApiConfig embeddingConfig() { return embedding.modelConfig(); }
    public ModelApiConfig rerankConfig() { return rerank.modelConfig(); }

    public void configs(ApiConfig chatConfig, ModelApiConfig embeddingConfig,
                        ModelApiConfig rerankConfig) {
        chat.set(chatConfig);
        embedding.set(embeddingConfig);
        rerank.set(rerankConfig);
    }

    public void chatModels(List<String> models, Object previous) {
        models(ModelKind.CHAT, models, previous);
    }

    public void models(ModelKind kind, List<String> models, Object previous) {
        ApiFields fields = fields(kind);
        fields.model.removeAllItems();
        models.forEach(fields.model::addItem);
        if (previous != null && models.contains(previous.toString())) fields.model.setSelectedItem(previous);
        else if (!models.isEmpty()) fields.model.setSelectedIndex(0);
    }

    public Object chatModelEditorValue() { return modelEditorValue(ModelKind.CHAT); }

    public Object modelEditorValue(ModelKind kind) { return fields(kind).model.getEditor().getItem(); }

    public void status(String text, java.awt.Color color) {
        status.setText(text);
        status.setForeground(color);
    }

    /**
     * Reports the local model as it is on disk and puts the page back into an idle state, so the same
     * call ends a download whether it succeeded or failed.
     */
    public void localModel(LocalModelView view) {
        modelProgress.setVisible(false);
        modelProgress.setIndeterminate(false);
        downloadModel.setEnabled(true);
        downloadModel.setText(view.installed() ? "重新下载" : "下载模型");
        modelStatus.setForeground(view.installed() ? Theme.ACCENT : Theme.AMBER);
        modelStatus.setText((view.installed()
                ? "已安装 · " + FileStatusStyle.size(view.bytes()) + " · " + view.modelName()
                : "未安装 · 本地语义检索不可用，只能按关键词匹配")
                + "  ·  " + view.directory());
    }

    /** One step of an in-flight download; {@code percent} below zero means the size is unknown. */
    public void modelDownloading(String text, int percent) {
        downloadModel.setEnabled(false);
        modelProgress.setVisible(true);
        modelProgress.setIndeterminate(percent < 0);
        if (percent >= 0) modelProgress.setValue(percent);
        modelStatus.setForeground(Theme.TEXT);
        modelStatus.setText(text);
    }

    String modelStatusText() { return modelStatus.getText(); }

    boolean downloadEnabled() { return downloadModel.isEnabled(); }

    private ApiFields fields(ModelKind kind) {
        return switch (kind) {
            case CHAT -> chat;
            case EMBEDDING -> embedding;
            case RERANK -> rerank;
        };
    }

    private JPanel header() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel title = new JLabel("模型与 API 设置");
        title.setForeground(Theme.TEXT);
        title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 22f));
        JLabel hint = new JLabel("分别配置生成、向量化和二阶段重排；URL 可填写 /v1 根地址或完整接口地址");
        hint.setForeground(Theme.MUTED);
        hint.setFont(Theme.UI_FONT.deriveFont(12f));
        panel.add(title);
        panel.add(Box.createVerticalStrut(5));
        panel.add(hint);
        return panel;
    }

    /**
     * The local ONNX model, installable from here.
     *
     * <p>It used to be reachable only by finding and running {@code setup-semantic-model.cmd}, which
     * left the application saying semantic search was unavailable without saying what to do about it.
     */
    private JPanel localModelSection(Runnable onDownload) {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        Theme.opaque(panel, Theme.PANEL);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), Theme.padding(16, 18, 17, 18)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 132));

        JPanel labels = new JPanel();
        labels.setOpaque(false);
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("本地语义模型");
        title.setForeground(Theme.TEXT);
        title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 15f));
        JLabel hint = new JLabel("关闭远程向量 API 时由它生成向量；约 120 MB，下载后需要重建索引");
        hint.setForeground(Theme.MUTED);
        hint.setFont(Theme.UI_FONT.deriveFont(11f));
        labels.add(title);
        labels.add(Box.createVerticalStrut(3));
        labels.add(hint);

        JPanel heading = new JPanel(new BorderLayout(12, 0));
        heading.setOpaque(false);
        heading.add(labels, BorderLayout.CENTER);
        Theme.styleButton(downloadModel, true);
        downloadModel.setToolTipText("从 HF_ENDPOINT 指定的镜像下载；再次点击可覆盖已有文件");
        downloadModel.addActionListener(event -> onDownload.run());
        heading.add(downloadModel, BorderLayout.EAST);
        panel.add(heading, BorderLayout.NORTH);

        JPanel state = new JPanel();
        state.setOpaque(false);
        state.setLayout(new BoxLayout(state, BoxLayout.Y_AXIS));
        modelStatus.setFont(Theme.UI_FONT.deriveFont(11f));
        modelStatus.setForeground(Theme.MUTED);
        modelStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        modelProgress.setMaximum(100);
        modelProgress.setVisible(false);
        modelProgress.setForeground(Theme.ACCENT);
        modelProgress.setBackground(Theme.BORDER);
        modelProgress.setAlignmentX(Component.LEFT_ALIGNMENT);
        modelProgress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        modelProgress.setPreferredSize(new Dimension(240, 6));
        state.add(modelStatus);
        state.add(Box.createVerticalStrut(8));
        state.add(modelProgress);
        panel.add(state, BorderLayout.CENTER);
        return panel;
    }

    private JPanel section(String titleText, String hintText, ApiFields fields, ModelKind kind,
                           BiConsumer<ModelKind, JButton> onFetch) {        JPanel panel = new JPanel(new BorderLayout(0, 12));
        Theme.opaque(panel, Theme.PANEL);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), Theme.padding(16, 18, 17, 18)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, fields.withDimensions ? 190 : 170));
        JPanel heading = new JPanel(new BorderLayout(12, 0));
        heading.setOpaque(false);
        JPanel labels = new JPanel();
        labels.setOpaque(false);
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(titleText);
        title.setForeground(Theme.TEXT);
        title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 15f));
        JLabel hint = new JLabel(hintText);
        hint.setForeground(Theme.MUTED);
        hint.setFont(Theme.UI_FONT.deriveFont(11f));
        labels.add(title);
        labels.add(Box.createVerticalStrut(3));
        labels.add(hint);
        heading.add(labels, BorderLayout.CENTER);
        if (fields.remote != null) {
            fields.remote.setOpaque(false);
            fields.remote.setForeground(Theme.TEXT);
            heading.add(fields.remote, BorderLayout.EAST);
        }
        panel.add(heading, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0; c.fill = GridBagConstraints.HORIZONTAL; c.weighty = 0;
        c.insets = new Insets(0, 0, 0, 10);
        c.gridx = 0; c.weightx = 0.46; form.add(labeled("API URL", fields.url), c);
        c.gridx = 1; c.weightx = 0.28; form.add(labeled("API Key", fields.key), c);
        c.gridx = 2; c.weightx = 0.22; form.add(labeled("模型", fields.model), c);
        if (fields.withDimensions) {
            c.gridx = 3; c.weightx = 0.10; form.add(labeled("维度（0=默认）", fields.dimensions), c);
        }
        if (onFetch != null) {
            c.gridx = fields.withDimensions ? 4 : 3; c.weightx = 0; c.fill = GridBagConstraints.NONE;
            c.insets = new Insets(18, 0, 0, 0);
            JButton fetch = new JButton("获取模型");
            Theme.styleButton(fetch, false);
            fetch.addActionListener(event -> onFetch.accept(kind, fetch));
            form.add(fetch, c);
        }
        panel.add(form, BorderLayout.CENTER);
        return panel;
    }

    private static JPanel labeled(String label, Component field) {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.setOpaque(false);
        JLabel title = new JLabel(label);
        title.setForeground(Theme.MUTED);
        title.setFont(Theme.UI_FONT.deriveFont(10f));
        panel.add(title, BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    private static final class ApiFields {
        private final JTextField url = new JTextField();
        private final JPasswordField key = new JPasswordField();
        private final JComboBox<String> model = new JComboBox<>();
        private final JTextField dimensions = new JTextField("0");
        private final JCheckBox remote;
        private final boolean withDimensions;

        private ApiFields(boolean optionalRemote, boolean withDimensions) {
            this.withDimensions = withDimensions;
            this.remote = optionalRemote ? new JCheckBox("启用远程 API") : null;
            model.setEditable(true);
        }

        private ApiConfig apiConfig() {
            return new ApiConfig(url.getText(), new String(key.getPassword()), modelValue());
        }

        private ModelApiConfig modelConfig() {
            int value;
            try { value = Integer.parseInt(dimensions.getText().strip()); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("向量维度必须是整数"); }
            return new ModelApiConfig(remote != null && remote.isSelected(), url.getText(),
                    new String(key.getPassword()), modelValue(), value);
        }

        private String modelValue() {
            Object value = model.getEditor().getItem();
            return value == null ? "" : value.toString();
        }

        private void set(ApiConfig config) {
            url.setText(config.baseUrl());
            key.setText(config.apiKey());
            selectModel(config.model());
        }

        private void set(ModelApiConfig config) {
            remote.setSelected(config.enabled());
            url.setText(config.baseUrl());
            key.setText(config.apiKey());
            dimensions.setText(Integer.toString(config.dimensions()));
            selectModel(config.model());
        }

        private void selectModel(String value) {
            model.removeAllItems();
            if (value != null && !value.isBlank()) model.addItem(value);
            model.setSelectedItem(value == null ? "" : value);
        }
    }

    public enum ModelKind { CHAT, EMBEDDING, RERANK }
}
