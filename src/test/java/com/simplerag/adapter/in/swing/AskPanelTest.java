package com.simplerag.adapter.in.swing;

import com.simplerag.application.conversation.AnswerDelta;
import com.simplerag.application.dto.CitationView;
import com.simplerag.application.dto.DocumentReference;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AskPanelTest {
    @Test
    void localOnlySwitchSavesThroughTheCallbackAndReportsItWhereTheSwitchIs() throws Exception {
        AtomicInteger saves = new AtomicInteger();
        AtomicReference<AskPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AskPanel created = new AskPanel(() -> { }, saves::incrementAndGet, () -> { }, () -> { });
            created.localOnly(true);
            // What the workspace controller does once the policy is persisted.
            created.policyStatus("已保存：本知识库只做本地检索，不会发送到远程模型", Theme.ACCENT);
            panel.set(created);
        });

        assertTrue(panel.get().localOnly());
        assertEquals("已保存：本知识库只做本地检索，不会发送到远程模型", panel.get().policyStatusText());
        assertEquals(0, saves.get(), "setting the switch programmatically must not write back");
    }

    @Test
    void contextBreakMarksTheRestartWithoutTouchingTheCopyableTranscript() throws Exception {
        AtomicReference<AskPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AskPanel created = new AskPanel(() -> { }, () -> { }, () -> { }, () -> { });
            created.beginTurn("索引怎么构建？");
            created.finishAssistant("分三步。", "test-model");
            created.contextBreak("源文件已变化（revision 8）· 模型从这里开始新的上下文");
            panel.set(created);
        });

        assertEquals("源文件已变化（revision 8）· 模型从这里开始新的上下文",
                panel.get().contextBreakMessage());
        assertFalse(panel.get().conversationText().contains("revision 8"),
                "the marker is page state, not part of the conversation");

        // A second change before the next question replaces the marker instead of stacking one more.
        SwingUtilities.invokeAndWait(() ->
                panel.get().contextBreak("源文件已变化（revision 9）· 模型从这里开始新的上下文"));
        assertEquals("源文件已变化（revision 9）· 模型从这里开始新的上下文",
                panel.get().contextBreakMessage());

        SwingUtilities.invokeAndWait(() -> panel.get().resetConversation("对话已清空", "已重置"));
        assertEquals("", panel.get().contextBreakMessage());
    }

    @Test
    void contextBreakIsSkippedWhenThereIsNoConversationToBreak() throws Exception {
        AtomicReference<AskPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AskPanel created = new AskPanel(() -> { }, () -> { }, () -> { }, () -> { });
            created.contextBreak("源文件已变化（revision 3）");
            panel.set(created);
        });

        assertEquals("", panel.get().contextBreakMessage());
    }

    @Test
    void answersRenderAsMarkdownWhileTheCopyableTextStaysWhatTheModelWrote() throws Exception {
        String answer = """
                **三步**：扫描、分块、发布 [1]。

                ```java
                IndexStore.publish(manifest);
                ```
                """;
        AtomicReference<AskPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AskPanel created = new AskPanel(() -> { }, () -> { }, () -> { }, () -> { });
            created.beginTurn("索引怎么构建？");
            created.appendAssistantDelta(AnswerDelta.answer(answer));
            created.finishAssistant(answer, "test-model");
            panel.set(created);
        });

        String rendered = rendered(panel.get());
        assertFalse(rendered.contains("**"), "emphasis is styling, not text");
        assertFalse(rendered.contains("```"), "a fence is styling, not text");
        assertTrue(rendered.contains("三步：扫描、分块、发布 [1]。"), rendered);
        assertTrue(rendered.contains("IndexStore.publish(manifest);"), rendered);
        // Copying gives back the source, so it can be pasted somewhere that understands markdown.
        assertEquals(answer, panel.get().latestAnswerWithCitations());
    }

    @Test
    void citationMarkersInTheAnswerCarryTheirNumber() throws Exception {
        AtomicReference<AskPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AskPanel created = new AskPanel(() -> { }, () -> { }, () -> { }, () -> { });
            created.beginTurn("登录校验在哪？");
            created.finishAssistant("校验在 AuthService [2]，数组下标 items[0] 不是引用。", "test-model");
            panel.set(created);
        });

        String rendered = rendered(panel.get());
        MarkdownPane pane = panel.get().latestAnswerPane();
        assertEquals(2, pane.citationAtOffset(rendered.indexOf("[2]") + 1));
        assertEquals(0, pane.citationAtOffset(rendered.indexOf("items[0]") + 6));
    }

    @Test
    void clickingACitationSelectsItAndOpensTheSource() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        AtomicReference<AskPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AskPanel created = new AskPanel(() -> { }, () -> { }, opened::incrementAndGet, () -> { });
            created.beginTurn("登录校验在哪？");
            created.citations(List.of(citation(1, "IndexStore.java"), citation(2, "AuthService.java")));
            created.finishAssistant("校验在 AuthService [2]。", "test-model");
            created.activateCitation(2);
            panel.set(created);
        });

        assertEquals(1, opened.get());
        assertEquals("AuthService.java", panel.get().selectedCitation().document().fileName());
    }

    /** History keeps the answer text but not its chunks, so an old marker has nothing to open. */
    @Test
    void aCitationNumberThatIsNoLongerListedDoesNothing() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        AtomicReference<AskPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AskPanel created = new AskPanel(() -> { }, () -> { }, opened::incrementAndGet, () -> { });
            created.beginTurn("登录校验在哪？");
            created.finishAssistant("校验在 AuthService [7]。", "test-model");
            created.activateCitation(7);
            panel.set(created);
        });

        assertEquals(0, opened.get());
        assertNull(panel.get().selectedCitation());
    }

    /**
     * The bubble is created empty and filled later, so the row must be re-measured after the answer
     * lands. It used to keep the cap it got while empty, which left the answer invisible.
     */
    @Test
    void theTranscriptRowGrowsToFitAnAnswerThatArrivedAfterTheBubble() throws Exception {
        AtomicReference<AskPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AskPanel created = new AskPanel(() -> { }, () -> { }, () -> { }, () -> { });
            created.beginTurn("索引怎么构建？");
            created.appendAssistantDelta(AnswerDelta.answer("""
                    ## 构建与发布
                    - 扫描：`DocumentScanner` 遍历数据源 [1]
                    - 分块：`ChunkerRegistry` 按 section 切片
                    - 发布：先写临时文件，再原子移动 [2]

                    ```java
                    Files.move(staged, target, ATOMIC_MOVE, REPLACE_EXISTING);
                    ```
                    """));
            created.finishAssistant("", "test-model");
            panel.set(created);
        });

        int rendered = panel.get().latestAnswerPane().getPreferredSize().height;
        assertTrue(rendered > 60, "a multi-block answer is taller than one line: " + rendered);
        assertTrue(panel.get().latestAnswerRowHeight() >= rendered,
                "row allows " + panel.get().latestAnswerRowHeight() + " for " + rendered + " of text");
    }

    private static String rendered(AskPanel panel) throws Exception {
        MarkdownPane pane = panel.latestAnswerPane();
        return pane.getDocument().getText(0, pane.getDocument().getLength());
    }

    private static CitationView citation(int number, String fileName) {
        return new CitationView(number, new DocumentReference("chunk-" + number,
                Path.of("kb", fileName), fileName, "java", 1, 9, "内容", false), 0.9);
    }

    @Test
    void thinkingIsShownWhileStreamingThenFoldsAwayAndStaysOutOfTheAnswer() throws Exception {
        AtomicReference<AskPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AskPanel created = new AskPanel(() -> { }, () -> { }, () -> { }, () -> { });
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
            AskPanel created = new AskPanel(() -> { }, () -> { }, () -> { }, () -> { });
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
