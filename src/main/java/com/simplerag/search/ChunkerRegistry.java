package com.simplerag.search;

import com.simplerag.model.DocumentChunk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Token-aware prose chunking and declaration-aware code chunking with stable source locations. */
public final class ChunkerRegistry {
    public static final int CHUNKING_VERSION = 3;

    private static final int PROSE_TARGET_TOKENS = 320;
    private static final int PROSE_MIN_TOKENS = 80;
    private static final int PROSE_OVERLAP_TOKENS = 40;
    private static final int CODE_TARGET_TOKENS = 420;
    private static final int CODE_OVERLAP_TOKENS = 64;

    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "java", "kt", "kts", "py", "js", "jsx", "ts", "tsx", "go", "rs", "c", "h",
            "cpp", "hpp", "cs", "php", "rb", "swift", "scala", "sql", "sh", "bash", "ps1",
            "bat", "cmd", "css", "scss", "vue", "svelte",
            "lua", "r", "pl", "pm", "ex", "exs", "erl", "dart", "groovy",
            "proto", "graphql", "gql", "tf", "tfvars", "hcl");

    private static final Pattern DECLARATION = Pattern.compile(
            "^\\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|override|open|internal|export|default|async)\\s+)*"
                    + "(?:class|interface|record|enum|struct|trait|object|def|function|fun|fn|func|type)\\s+([A-Za-z_$][\\w$]*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern METHOD = Pattern.compile(
            "^\\s*(?:(?:public|protected|private|static|final|abstract|synchronized|override|open|internal|async)\\s+)*"
                    + "(?:[A-Za-z_$][\\w$<>?,.\\[\\] ]+\\s+)?([A-Za-z_$][\\w$]*)\\s*\\([^;]*\\)\\s*(?:\\{|throws\\b.*|:)?\\s*$");
    private static final Pattern ARROW = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=.*=>");

    public List<DocumentChunk> chunk(ReadDocument document) {
        List<DocumentChunk> chunks = new ArrayList<>();
        for (DocumentSection section : document.sections()) {
            if (CODE_EXTENSIONS.contains(document.extension().toLowerCase(java.util.Locale.ROOT))) {
                chunkCode(chunks, document, section);
            } else {
                chunkProse(chunks, document, section);
            }
        }
        return List.copyOf(chunks);
    }

    private static void chunkCode(List<DocumentChunk> chunks, ReadDocument document, DocumentSection section) {
        if (section.units().isEmpty()) return;
        List<Boundary> declarations = declarationBoundaries(section.units());
        if (declarations.isEmpty()) {
            chunkTokenWindows(chunks, document, section, 0, section.units().size(),
                    CODE_TARGET_TOKENS, CODE_OVERLAP_TOKENS, "");
            return;
        }
        List<Boundary> boundaries = new ArrayList<>();
        if (declarations.get(0).index() > 0) boundaries.add(new Boundary(0, "文件头"));
        boundaries.addAll(declarations);
        for (int index = 0; index < boundaries.size(); index++) {
            Boundary boundary = boundaries.get(index);
            int end = index + 1 < boundaries.size() ? boundaries.get(index + 1).index() : section.units().size();
            if (end <= boundary.index()) continue;
            String label = "符号：" + boundary.symbol();
            chunkTokenWindows(chunks, document, section, boundary.index(), end,
                    CODE_TARGET_TOKENS, CODE_OVERLAP_TOKENS, label);
        }
    }

    private static List<Boundary> declarationBoundaries(List<DocumentTextUnit> units) {
        List<Boundary> boundaries = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (int index = 0; index < units.size(); index++) {
            String line = units.get(index).text();
            String symbol = declarationSymbol(line);
            if (symbol == null) continue;
            int start = includeLeadingMetadata(units, index);
            if (seen.add(start)) boundaries.add(new Boundary(start, symbol));
        }
        return boundaries;
    }

    private static String declarationSymbol(String line) {
        Matcher declaration = DECLARATION.matcher(line);
        if (declaration.find()) return declaration.group(1);
        Matcher arrow = ARROW.matcher(line);
        if (arrow.find()) return arrow.group(1);
        Matcher method = METHOD.matcher(line);
        if (method.find()) {
            String symbol = method.group(1);
            if (!Set.of("if", "for", "while", "switch", "catch", "return", "new").contains(symbol)) {
                return symbol;
            }
        }
        return null;
    }

    private static int includeLeadingMetadata(List<DocumentTextUnit> units, int declaration) {
        int start = declaration;
        while (start > 0) {
            String previous = units.get(start - 1).text().strip();
            if (previous.startsWith("@") || previous.startsWith("//") || previous.startsWith("#")
                    || previous.startsWith("*") || previous.startsWith("/*") || previous.startsWith("\"")
                    || previous.isBlank()) {
                start--;
                if (declaration - start >= 4) break;
            } else break;
        }
        return start;
    }

    private static void chunkProse(List<DocumentChunk> chunks, ReadDocument document, DocumentSection section) {
        int start = 0;
        int tokens = 0;
        for (int index = 0; index < section.units().size(); index++) {
            DocumentTextUnit unit = section.units().get(index);
            tokens += estimateTokens(unit.text());
            boolean paragraphBoundary = unit.text().isBlank() && tokens >= PROSE_MIN_TOKENS;
            boolean full = tokens >= PROSE_TARGET_TOKENS;
            if (paragraphBoundary || full) {
                addChunk(chunks, document, section, start, index + 1, "");
                int next = full
                        ? overlapStart(section.units(), start, index + 1, PROSE_OVERLAP_TOKENS)
                        : index + 1;
                // One oversized unit can itself exceed the target. In that case overlapStart returns
                // the old start; accepting it makes every following chunk repeat the same prefix.
                start = next <= start ? index + 1 : next;
                tokens = tokenCount(section.units(), start, index + 1);
            }
        }
        if (start < section.units().size()) addChunk(chunks, document, section, start, section.units().size(), "");
    }

    private static void chunkTokenWindows(List<DocumentChunk> chunks, ReadDocument document,
                                          DocumentSection section, int rangeStart, int rangeEnd,
                                          int targetTokens, int overlapTokens, String parentLabel) {
        int start = rangeStart;
        while (start < rangeEnd) {
            int end = start;
            int tokens = 0;
            while (end < rangeEnd && (tokens < targetTokens || end == start)) {
                tokens += estimateTokens(section.units().get(end).text());
                end++;
            }
            addChunk(chunks, document, section, start, end, parentLabel);
            if (end >= rangeEnd) break;
            int next = overlapStart(section.units(), start, end, overlapTokens);
            start = next <= start ? end : next;
        }
    }

    private static int overlapStart(List<DocumentTextUnit> units, int floor, int endExclusive, int overlapTokens) {
        int tokens = 0;
        int start = endExclusive;
        while (start > floor && tokens < overlapTokens) {
            start--;
            tokens += estimateTokens(units.get(start).text());
        }
        return start;
    }

    private static int tokenCount(List<DocumentTextUnit> units, int start, int end) {
        int tokens = 0;
        for (int index = start; index < end; index++) tokens += estimateTokens(units.get(index).text());
        return tokens;
    }

    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 1;
        int wide = 0;
        int narrow = 0;
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL) wide++;
            else narrow++;
        }
        return Math.max(1, wide + (int) Math.ceil(narrow / 4.0));
    }

    private static void addChunk(List<DocumentChunk> chunks, ReadDocument document, DocumentSection section,
                                 int start, int end, String parentLabel) {
        while (start < end && section.units().get(start).text().isBlank()) start++;
        while (end > start && section.units().get(end - 1).text().isBlank()) end--;
        if (start >= end) return;
        String content = String.join("\n", section.units().subList(start, end).stream()
                .map(DocumentTextUnit::text).toList()).strip();
        if (content.length() < 12) return;
        int startUnit = section.units().get(start).number();
        int endUnit = section.units().get(end - 1).number();
        String sourceLocation = section.sourceLocation(start, end);
        if (parentLabel != null && !parentLabel.isBlank()) sourceLocation = parentLabel + " · " + sourceLocation;
        String id = sha1(document.documentId() + ":" + section.id() + ":" + sourceLocation + ":" + content);
        chunks.add(new DocumentChunk(id, document.path().toString(), document.root().toString(),
                document.path().getFileName().toString(), document.extension(), startUnit, endUnit,
                sourceLocation, content, document.modifiedAt(), null));
    }

    private static String sha1(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private record Boundary(int index, String symbol) { }
}
