package com.simplerag.adapter.out.openai;

import org.junit.jupiter.api.Test;

import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThinkTagSplitterTest {
    @Test
    void separatesInlineThinkingFromTheAnswer() {
        Collected collected = feed("<think>weighing the evidence</think>The answer is 42.");

        assertEquals("weighing the evidence", collected.thinking);
        assertEquals("The answer is 42.", collected.answer);
    }

    @Test
    void tagsSplitAcrossChunkBoundariesAreStillRecognised() {
        // SSE frames break wherever the provider's tokeniser happens to break, so "<think>" routinely
        // arrives in pieces. Emitting the partial tag as answer text would corrupt every such turn.
        Collected collected = feed("<thi", "nk>step on", "e</thi", "nk>done [1]");

        assertEquals("step one", collected.thinking);
        assertEquals("done [1]", collected.answer);
    }

    @Test
    void aPartialTagLeftAtTheEndOfTheStreamIsEmittedAsText() {
        Collected collected = feed("answer <thi");

        assertEquals("", collected.thinking);
        assertEquals("answer <thi", collected.answer);
    }

    @Test
    void aThinkTagQuotedInsideTheAnswerIsLeftAlone() {
        // Once real answer text has been emitted, a <think> is far likelier to be quoted from an
        // indexed document than to be the model's own reasoning; swallowing it would eat the answer.
        Collected collected = feed("The file contains <think>literal</think> markup.");

        assertEquals("", collected.thinking);
        assertEquals("The file contains <think>literal</think> markup.", collected.answer);
    }

    @Test
    void unterminatedThinkingIsReportedAsThinkingRatherThanLeaking() {
        Collected collected = feed("<think>cut off mid-thought");

        assertEquals("cut off mid-thought", collected.thinking);
        assertEquals("", collected.answer);
    }

    @Test
    void contentWithNoTagsPassesThroughUnchanged() {
        Collected collected = feed("plain ", "answer ", "text");

        assertEquals("", collected.thinking);
        assertEquals("plain answer text", collected.answer);
    }

    private static Collected feed(String... chunks) {
        Collected collected = new Collected();
        ThinkTagSplitter splitter = new ThinkTagSplitter();
        BiConsumer<Boolean, String> sink = (isThinking, text) -> {
            if (isThinking) collected.thinking += text; else collected.answer += text;
        };
        for (String chunk : chunks) splitter.accept(chunk, sink);
        splitter.flush(sink);
        return collected;
    }

    private static final class Collected {
        private String thinking = "";
        private String answer = "";
    }
}
