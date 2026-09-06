package com.simplerag.adapter.in.swing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownTextTest {
    @Test
    void readsHeadingsListsAndQuotesWithTheirOwnMarkers() {
        List<MarkdownText.Line> lines = MarkdownText.parse("""
                ## 索引怎么构建
                - 扫描用 DocumentScanner
                  - 忽略 node_modules
                1. 先建临时索引
                2) 原子发布
                > freshness 会拦住过期索引
                ---
                """).lines();

        assertEquals(MarkdownText.Block.HEADING, lines.get(0).block());
        assertEquals(2, lines.get(0).level());
        assertEquals("索引怎么构建", lines.get(0).text());
        assertEquals(MarkdownText.Block.BULLET, lines.get(1).block());
        assertEquals("•", lines.get(1).marker());
        assertEquals(0, lines.get(1).level());
        assertEquals("扫描用 DocumentScanner", lines.get(1).text());
        assertEquals(1, lines.get(2).level(), "two spaces of indent is one nesting level");
        assertEquals("◦", lines.get(2).marker());
        assertEquals(MarkdownText.Block.NUMBERED, lines.get(3).block());
        assertEquals("1.", lines.get(3).marker());
        assertEquals("先建临时索引", lines.get(3).text());
        assertEquals("2.", lines.get(4).marker(), "1) and 1. are the same list");
        assertEquals(MarkdownText.Block.QUOTE, lines.get(5).block());
        assertEquals(MarkdownText.Block.RULE, lines.get(6).block());
        assertEquals(7, lines.size(), "a trailing newline is not an empty line");
    }
    @Test
    void keepsFencedCodeVerbatimAndDropsTheFences() {
        List<MarkdownText.Line> lines = MarkdownText.parse("""
                看这里：
                ```java
                    IndexStore.publish(manifest);   // **not** bold in code
                ```
                完成。
                """).lines();

        assertEquals(MarkdownText.Block.CODE, lines.get(1).block());
        assertEquals("    IndexStore.publish(manifest);   // **not** bold in code",
                lines.get(1).text(), "indentation and markup characters survive as written");
        assertEquals(1, lines.get(1).runs().size());
        assertEquals(MarkdownText.Block.PARAGRAPH, lines.get(2).block());
        assertEquals("完成。", lines.get(2).text());
    }

    /** A block still streaming in has no closing fence yet, and should already look like code. */
    @Test
    void anUnclosedFenceStillRendersAsCode() {
        List<MarkdownText.Line> lines = MarkdownText.parse("```\nfirst();\nsecond();").lines();

        assertEquals(2, lines.size());
        assertTrue(lines.stream().allMatch(line -> line.block() == MarkdownText.Block.CODE));
    }

    @Test
    void marksBoldItalicAndInlineCodeRuns() {
        List<MarkdownText.Run> runs = MarkdownText.parse("**三步**：先 *扫描*，再调 `publish()`").lines()
                .get(0).runs();

        assertEquals("三步", runs.get(0).text());
        assertTrue(runs.get(0).bold());
        assertEquals("：先 ", runs.get(1).text());
        assertFalse(runs.get(1).bold());
        assertTrue(runs.get(2).italic());
        assertEquals("扫描", runs.get(2).text());
        assertTrue(runs.get(4).code());
        assertEquals("publish()", runs.get(4).text());
    }

    @Test
    void leavesSnakeCaseAndEscapesAlone() {
        assertEquals("忽略 node_modules 和 source_revision",
                MarkdownText.parse("忽略 node_modules 和 source_revision").lines().get(0).text());
        assertEquals("字面量 *星号* 和 [1]",
                MarkdownText.parse("字面量 \\*星号\\* 和 \\[1]").lines().get(0).text());
    }
    @Test
    void marksCitationNumbersButNotMarkdownLinks() {
        List<MarkdownText.Run> runs = MarkdownText.parse(
                "校验在 AuthService [12]，链接 [3](https://example.com)，数组 items[0]").lines()
                .get(0).runs();

        MarkdownText.Run citation = runs.stream().filter(run -> run.citation() > 0).findFirst()
                .orElseThrow();
        assertEquals(12, citation.citation());
        assertEquals("[12]", citation.text());
        assertEquals(1, runs.stream().filter(run -> run.citation() > 0).count(),
                "a link target and an array index are not citations");
        assertTrue(runs.stream().map(MarkdownText.Run::text).reduce("", String::concat)
                .contains("[3](https://example.com)"));
    }

    @Test
    void blankSourceLinesSurviveAsEmptyParagraphs() {
        List<MarkdownText.Line> lines = MarkdownText.parse("第一段\n\n第二段").lines();

        assertEquals(3, lines.size());
        assertTrue(lines.get(1).runs().isEmpty());
        assertEquals(MarkdownText.Block.PARAGRAPH, lines.get(1).block());
    }

    @Test
    void emptyInputHasNoLines() {
        assertTrue(MarkdownText.parse("").lines().isEmpty());
        assertTrue(MarkdownText.parse(null).lines().isEmpty());
    }
}
