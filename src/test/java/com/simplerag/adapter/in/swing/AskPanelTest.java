package com.simplerag.adapter.in.swing;

import com.simplerag.application.conversation.AnswerDelta;
import com.simplerag.rag.ApiConfig;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AskPanelTest {
    @Test
    void canBeConstructedAndConfiguredWithoutMainFrame() throws Exception {
        AtomicReference<AskPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AskPanel created = new AskPanel(() -> { }, () -> { }, button -> { }, () -> { }, () -> { });
            created.config(new ApiConfig("http://localhost:11434/v1", "secret", "local-model"));
            panel.set(created);
        });

        assertEquals("local-model", panel.get().config().model());
        assertEquals("http://localhost:11434/v1", panel.get().config().baseUrl());
    }

    @Test
    void thinkingIsShownWhileStreamingThenFoldsAwayAndStaysOutOfTheAnswer() throws Exception {
        AtomicReference<AskPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AskPanel created = new AskPanel(() -> { }, () -> { }, button -> { }, () -> { }, () -> { });
            created.beginTurn("索引怎么构建？");
            created.appendAssistantDelta(AnswerDelta.planning("〔第 2 轮检索〕关键词：ChunkerRegistry"));
            created.appendAssistantDelta(AnswerDelta.thinking("证据里缺少发布环节"));
            panel.set(created);
        });

        assertTrue(panel.get().assistantThinkingExpanded(), "thinking stays open while it is all there is");

        SwingUtilities.invokeAndWait(() -> {
            panel.get().appendAssistantDelta(AnswerDelta.answer("索引构建分为三步 [1]。"));
            panel.get().finishAssistant("索引构建分为三步 [1]。", "test-model");
        });

        // The reasoning is still readable, but it is neither in the answer nor in the copyable text.
        assertTrue(panel.get().latestThinking().contains("ChunkerRegistry"));
        assertEquals("索引构建分为三步 [1]。", panel.get().latestAnswerWithCitations());
        assertFalse(panel.get().conversationText().contains("缺少发布环节"));
    }

    @Test
    void transcriptAndLatestAnswerAreAvailableAsCopyablePlainText() throws Exception {
        AtomicReference<AskPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AskPanel created = new AskPanel(() -> { }, () -> { }, button -> { }, () -> { }, () -> { });
            created.beginTurn("登录校验代码在哪？");
            created.appendAssistantDelta(AnswerDelta.answer("实现位于 AuthService.java [1]。"));
            created.finishAssistant("实现位于 AuthService.java [1]。", "test-model");
            panel.set(created);
        });

        assertTrue(panel.get().conversationText().contains("你：\n登录校验代码在哪？"));
        assertTrue(panel.get().conversationText().contains("助手：\n实现位于 AuthService.java [1]。"));
        assertEquals("实现位于 AuthService.java [1]。", panel.get().latestAnswerWithCitations());
    }
}
