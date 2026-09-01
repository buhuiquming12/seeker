package com.simplerag.adapter.out.openai;

import java.util.function.BiConsumer;

/**
 * Splits inline {@code <think>…</think>} reasoning out of a streamed content channel.
 *
 * <p>Providers disagree on where a reasoning model's chain of thought goes. DeepSeek, Qwen and vLLM
 * put it in a separate {@code reasoning_content} field; Ollama and LM Studio inline it in {@code
 * content} wrapped in {@code <think>} tags. Without this, the inline form lands in the answer text and
 * from there into conversation history and the clipboard.
 *
 * <p>The splitter is stateful because SSE chunk boundaries fall anywhere — {@code "<thi"} and {@code
 * "nk>"} routinely arrive as separate frames — so a partial tag is held back rather than emitted as
 * text.
 *
 * <p>An opening tag is only honoured before any real answer text has been emitted. A reasoning model
 * always leads with its thinking, whereas a {@code <think>} appearing later is far more likely to be
 * a literal quoted from an indexed document, and swallowing that would corrupt the answer.
 */
final class ThinkTagSplitter {
    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";

    private final StringBuilder pending = new StringBuilder();
    private boolean inside;
    private boolean answerStarted;

    /** Feeds one content chunk. The sink is called with {@code true} for thinking, {@code false} for answer. */
    void accept(String chunk, BiConsumer<Boolean, String> sink) {
        if (chunk == null || chunk.isEmpty()) return;
        pending.append(chunk);
        drain(sink, false);
    }

    /** Emits whatever is still buffered. Call once the stream ends, or a partial tag would be lost. */
    void flush(BiConsumer<Boolean, String> sink) {
        drain(sink, true);
    }

    private void drain(BiConsumer<Boolean, String> sink, boolean atEnd) {
        while (pending.length() > 0) {
            String tag = inside ? CLOSE : OPEN;
            int match = pending.indexOf(tag);
            if (match >= 0 && (inside || opensTheStream(match))) {
                emit(sink, pending.substring(0, match));
                pending.delete(0, match + tag.length());
                inside = !inside;
                continue;
            }
            // No usable tag. Emit everything that cannot still turn into one, and keep the rest until
            // the next chunk arrives — unless the stream is over, in which case a partial tag is just text.
            int safe = atEnd ? pending.length() : pending.length() - danglingTagLength(tag);
            if (safe <= 0) return;
            emit(sink, pending.substring(0, safe));
            pending.delete(0, safe);
            if (!atEnd) return;
        }
    }

    /**
     * True when an opening tag at {@code match} is the model's own preamble rather than a literal.
     * Nothing but whitespace may precede it, in this chunk or in any earlier one.
     */
    private boolean opensTheStream(int match) {
        return !answerStarted && pending.substring(0, match).isBlank();
    }

    private void emit(BiConsumer<Boolean, String> sink, String text) {
        if (text.isEmpty()) return;
        if (!inside && !text.isBlank()) answerStarted = true;
        sink.accept(inside, text);
    }

    /** Length of the longest suffix of the buffer that is a proper prefix of {@code tag}. */
    private int danglingTagLength(String tag) {
        int max = Math.min(pending.length(), tag.length() - 1);
        for (int length = max; length > 0; length--) {
            if (regionMatches(pending.length() - length, tag, length)) return length;
        }
        return 0;
    }

    private boolean regionMatches(int start, String tag, int length) {
        for (int offset = 0; offset < length; offset++) {
            if (pending.charAt(start + offset) != tag.charAt(offset)) return false;
        }
        return true;
    }
}
