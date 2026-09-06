package com.simplerag.adapter.in.swing;

import java.util.ArrayList;
import java.util.List;

/**
 * The markdown subset chat answers actually use, parsed into lines of styled runs.
 *
 * <p>Deliberately Swing-free: the result is what {@link MarkdownPane} writes into a styled
 * document, and what the parser tests assert on without a display.
 *
 * <p>Three rules differ from CommonMark on purpose. A single newline is a line break rather than a
 * soft wrap, because answers use them to separate points. An unclosed fence still renders as code,
 * so a block looks right while it is still streaming in. And {@code _underscore_} is not italic:
 * answers are full of snake_case identifiers and file names, and mangling those costs more than the
 * emphasis is worth.
 */
final class MarkdownText {
    enum Block { PARAGRAPH, HEADING, BULLET, NUMBERED, QUOTE, CODE, RULE }

    /** @param citation the citation number this run marks, or {@code 0} for ordinary text */
    record Run(String text, boolean bold, boolean italic, boolean code, int citation) {
        static Run plain(String text) {
            return new Run(text, false, false, false, 0);
        }
    }

    /**
     * @param level  heading level for {@link Block#HEADING}, nesting depth for list items
     * @param marker bullet or number to draw before the text, empty for other blocks
     */
    record Line(Block block, int level, String marker, List<Run> runs) {
        Line {
            runs = List.copyOf(runs);
        }

        /** The line without its markup, for tests and for anything that wants flat text. */
        String text() {
            StringBuilder result = new StringBuilder();
            runs.forEach(run -> result.append(run.text()));
            return result.toString();
        }
    }

    private static final int MAX_HEADING = 6;
    private static final int MAX_LIST_LEVEL = 3;

    private final List<Line> lines;

    private MarkdownText(List<Line> lines) {
        this.lines = List.copyOf(lines);
    }

    List<Line> lines() {
        return lines;
    }
    static MarkdownText parse(String source) {
        if (source == null || source.isEmpty()) return new MarkdownText(List.of());
        List<Line> lines = new ArrayList<>();
        String[] rows = source.split("\n", -1);
        int last = rows.length - 1;
        // A trailing newline is punctuation, not an empty line the reader needs to see.
        if (last > 0 && rows[last].isEmpty()) last--;
        boolean code = false;
        for (int index = 0; index <= last; index++) {
            String row = rows[index];
            String trimmed = row.strip();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                code = !code;
                continue;
            }
            if (code) {
                lines.add(new Line(Block.CODE, 0, "", List.of(Run.plain(row))));
                continue;
            }
            lines.add(block(row, trimmed));
        }
        return new MarkdownText(lines);
    }

    private static Line block(String row, String trimmed) {
        if (trimmed.isEmpty()) return new Line(Block.PARAGRAPH, 0, "", List.of());
        if (isRule(trimmed)) return new Line(Block.RULE, 0, "", List.of());
        int hashes = 0;
        while (hashes < trimmed.length() && trimmed.charAt(hashes) == '#') hashes++;
        if (hashes > 0 && hashes <= MAX_HEADING && hashes < trimmed.length()
                && trimmed.charAt(hashes) == ' ') {
            return new Line(Block.HEADING, hashes, "", inline(trimmed.substring(hashes + 1).strip()));
        }
        if (trimmed.startsWith(">")) {
            return new Line(Block.QUOTE, 0, "", inline(trimmed.substring(1).strip()));
        }
        int level = Math.min(MAX_LIST_LEVEL, indent(row) / 2);
        if (trimmed.length() > 1 && "-*+".indexOf(trimmed.charAt(0)) >= 0 && trimmed.charAt(1) == ' ') {
            return new Line(Block.BULLET, level, level == 0 ? "•" : "◦",
                    inline(trimmed.substring(2).strip()));
        }
        String number = leadingNumber(trimmed);
        if (number != null) {
            return new Line(Block.NUMBERED, level, number + ".",
                    inline(trimmed.substring(number.length() + 2).strip()));
        }
        return new Line(Block.PARAGRAPH, 0, "", inline(trimmed));
    }
    private static boolean isRule(String trimmed) {
        if (trimmed.length() < 3) return false;
        char first = trimmed.charAt(0);
        if ("-*_".indexOf(first) < 0) return false;
        return trimmed.chars().allMatch(character -> character == first);
    }

    private static int indent(String row) {
        int spaces = 0;
        for (int index = 0; index < row.length(); index++) {
            char current = row.charAt(index);
            if (current == ' ') spaces++;
            else if (current == '\t') spaces += 4;
            else break;
        }
        return spaces;
    }

    /** Digits of an {@code 1. } or {@code 1) } list marker, or {@code null} when there is none. */
    private static String leadingNumber(String trimmed) {
        int digits = 0;
        while (digits < trimmed.length() && Character.isDigit(trimmed.charAt(digits))) digits++;
        if (digits == 0 || digits > 3 || trimmed.length() < digits + 2) return null;
        char delimiter = trimmed.charAt(digits);
        if (delimiter != '.' && delimiter != ')') return null;
        return trimmed.charAt(digits + 1) == ' ' ? trimmed.substring(0, digits) : null;
    }

    private static List<Run> inline(String text) {
        List<Run> runs = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        boolean bold = false;
        boolean italic = false;
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\\' && index + 1 < text.length()
                    && "*`[\\".indexOf(text.charAt(index + 1)) >= 0) {
                plain.append(text.charAt(index + 1));
                index += 2;
                continue;
            }
            if (current == '`') {
                // An unclosed span runs to the end of the line so code looks like code while typing.
                int close = text.indexOf('`', index + 1);
                String code = close < 0 ? text.substring(index + 1) : text.substring(index + 1, close);
                flush(runs, plain, bold, italic);
                if (!code.isEmpty()) runs.add(new Run(code, bold, false, true, 0));
                index = close < 0 ? text.length() : close + 1;
                continue;
            }
            if (text.startsWith("**", index) || text.startsWith("__", index)) {
                flush(runs, plain, bold, italic);
                bold = !bold;
                index += 2;
                continue;
            }
            if (current == '*') {
                flush(runs, plain, bold, italic);
                italic = !italic;
                index++;
                continue;
            }
            int citation = citationAt(text, index);
            if (citation > 0) {
                int close = text.indexOf(']', index);
                flush(runs, plain, bold, italic);
                runs.add(new Run(text.substring(index, close + 1), false, false, false, citation));
                index = close + 1;
                continue;
            }
            plain.append(current);
            index++;
        }
        flush(runs, plain, bold, italic);
        return runs;
    }
    /**
     * The citation number a marker at {@code index} carries, or {@code 0}. The prompt puts these
     * next to every verifiable fact, so they are the one piece of answer syntax worth making
     * interactive; {@code [1](link)} is a markdown link and stays text.
     */
    private static int citationAt(String text, int index) {
        if (text.charAt(index) != '[') return 0;
        int cursor = index + 1;
        int value = 0;
        while (cursor < text.length() && Character.isDigit(text.charAt(cursor))) {
            value = value * 10 + (text.charAt(cursor) - '0');
            cursor++;
        }
        if (value <= 0 || cursor >= text.length() || text.charAt(cursor) != ']') return 0;
        return cursor + 1 < text.length() && text.charAt(cursor + 1) == '(' ? 0 : value;
    }

    private static void flush(List<Run> runs, StringBuilder plain, boolean bold, boolean italic) {
        if (plain.isEmpty()) return;
        runs.add(new Run(plain.toString(), bold, italic, false, 0));
        plain.setLength(0);
    }
}
