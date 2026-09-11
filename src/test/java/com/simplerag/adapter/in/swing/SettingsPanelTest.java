package com.simplerag.adapter.in.swing;

import com.simplerag.application.dto.LocalModelView;
import com.simplerag.rag.ApiConfig;
import com.simplerag.rag.ModelApiConfig;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsPanelTest {
    @Test
    void storesAllThreeConfigsAndRoutesModelFetchByKind() throws Exception {
        AtomicReference<SettingsPanel> reference = new AtomicReference<>();
        AtomicReference<SettingsPanel.ModelKind> requested = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            SettingsPanel panel = new SettingsPanel(() -> { }, (kind, button) -> requested.set(kind),
                    () -> { });
            panel.configs(new ApiConfig("https://chat/v1", "c", "chat"),
                    new ModelApiConfig(true, "https://embed/v1", "e", "embed", 768),
                    new ModelApiConfig(true, "https://rerank/v1", "r", "rerank", 0));
            panel.models(SettingsPanel.ModelKind.EMBEDDING, List.of("embed-a", "embed-b"), "embed-b");
            java.util.List<javax.swing.JButton> fetchButtons = new java.util.ArrayList<>();
            collectFetchButtons(panel, fetchButtons);
            fetchButtons.get(1).doClick();
            reference.set(panel);
        });

        SettingsPanel panel = reference.get();
        assertEquals("chat", panel.chatConfig().model());
        assertEquals("embed-b", panel.embeddingConfig().model());
        assertEquals(768, panel.embeddingConfig().dimensions());
        assertTrue(panel.rerankConfig().enabled());

        // The callback contract carries the model kind, so each button can use its own endpoint.
        assertEquals(SettingsPanel.ModelKind.EMBEDDING, requested.get());
    }

    @Test
    void normalizesFullEmbeddingAndRerankEndpointsBeforeListingModels() {
        ApiConfig embedding = DesktopWorkspaceController.modelListConfig(
                new ApiConfig("https://provider.example/v1/embeddings", "key", "embed"),
                SettingsPanel.ModelKind.EMBEDDING);
        ApiConfig rerank = DesktopWorkspaceController.modelListConfig(
                new ApiConfig("https://provider.example/v1/rerank", "key", "rerank"),
                SettingsPanel.ModelKind.RERANK);

        assertEquals("https://provider.example/v1", embedding.baseUrl());
        assertEquals("https://provider.example/v1", rerank.baseUrl());
    }

    /**
     * The whole point of the section is that a missing model is actionable from inside the
     * application, so what it says and whether the button is usable is the contract.
     */
    @Test
    void theLocalModelSectionReportsInstallStateAndHoldsTheButtonWhileDownloading() throws Exception {
        AtomicReference<SettingsPanel> reference = new AtomicReference<>();
        AtomicInteger downloads = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            SettingsPanel panel = new SettingsPanel(() -> { }, (kind, button) -> { },
                    downloads::incrementAndGet);
            panel.localModel(new LocalModelView(false, Path.of("models", "multilingual-minilm"), 0,
                    "paraphrase-multilingual-MiniLM-L12-v2-int8"));
            reference.set(panel);
        });
        SettingsPanel panel = reference.get();
        assertTrue(panel.modelStatusText().contains("未安装"), panel.modelStatusText());
        assertTrue(panel.modelStatusText().contains("multilingual-minilm"), "the target path is the fix");
        assertTrue(panel.downloadEnabled());

        SwingUtilities.invokeAndWait(() -> {
            downloadButton(panel).doClick();
            panel.modelDownloading("正在下载 tokenizer.json（1/2） · 1.0 MB / 8.7 MB", 12);
        });
        assertEquals(1, downloads.get());
        assertFalse(panel.downloadEnabled(), "a second click would start a competing download");

        SwingUtilities.invokeAndWait(() -> panel.localModel(new LocalModelView(true,
                Path.of("models", "multilingual-minilm"), 127_535_388L, "minilm")));
        assertTrue(panel.modelStatusText().contains("已安装"), panel.modelStatusText());
        assertTrue(panel.downloadEnabled(), "a finished download has to hand the button back");
    }

    private static javax.swing.JButton downloadButton(SettingsPanel panel) {
        List<javax.swing.JButton> found = new java.util.ArrayList<>();
        collectButtons(panel, found);
        return found.stream().filter(button -> button.getText().contains("下载")).findFirst()
                .orElseThrow(() -> new AssertionError("没有找到下载按钮"));
    }

    private static void collectButtons(Component component, List<javax.swing.JButton> result) {
        if (component instanceof javax.swing.JButton button) result.add(button);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collectButtons(child, result);
        }
    }

    private static void collectFetchButtons(Component component, List<javax.swing.JButton> result) {
        if (component instanceof javax.swing.JButton button && "获取模型".equals(button.getText())) result.add(button);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collectFetchButtons(child, result);
        }
    }
}
