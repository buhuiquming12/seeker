package com.simplerag.search;

import com.simplerag.model.DocumentChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkerRegistryTest {
    @TempDir Path temp;

    @Test
    void codeChunksFollowDeclarationBoundariesAndKeepSymbolLocations() {
        List<DocumentTextUnit> units = List.of(
                new DocumentTextUnit(1, "package demo;"),
                new DocumentTextUnit(2, "public class UserService {"),
                new DocumentTextUnit(3, "  private final Repo repo;"),
                new DocumentTextUnit(4, "  public User findUser() {"),
                new DocumentTextUnit(5, "    return repo.find();"),
                new DocumentTextUnit(6, "  }"),
                new DocumentTextUnit(7, "  public void updateUser() {"),
                new DocumentTextUnit(8, "    repo.update();"),
                new DocumentTextUnit(9, "  }"),
                new DocumentTextUnit(10, "}"));
        List<DocumentChunk> chunks = new ChunkerRegistry().chunk(document("UserService.java", "java", units));

        assertTrue(chunks.stream().anyMatch(chunk -> chunk.sourceLocation().contains("UserService")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.sourceLocation().contains("findUser")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.sourceLocation().contains("updateUser")));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.sourceLocation().contains("L")));
    }

    @Test
    void proseChunksUseTokenWindowsInsteadOfUnboundedCharacterSlices() {
        List<DocumentTextUnit> units = new ArrayList<>();
        for (int line = 1; line <= 100; line++) {
            units.add(new DocumentTextUnit(line, "retrieval context evidence ranking ".repeat(10)));
        }

        List<DocumentChunk> chunks = new ChunkerRegistry().chunk(document("guide.md", "md", units));

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> ChunkerRegistry.estimateTokens(chunk.content()) <= 400),
                "normal prose units should remain near the 320-token target including overlap");
    }

    @Test
    void oversizedProseUnitCannotPinEveryFollowingWindowToTheSameStart() {
        List<DocumentTextUnit> units = new ArrayList<>();
        units.add(new DocumentTextUnit(1, "oversized ".repeat(500)));
        for (int line = 2; line <= 22; line++) {
            units.add(new DocumentTextUnit(line, "small trailing unit " + line));
        }

        List<DocumentChunk> chunks = new ChunkerRegistry().chunk(document("wide.csv", "csv", units));

        assertEquals(2, chunks.size(), "one oversized row must not be repeated in every later chunk");
        assertEquals(1, chunks.get(0).startLine());
        assertTrue(chunks.get(1).startLine() > chunks.get(0).startLine());
        assertTrue(chunks.get(1).content().length() < chunks.get(0).content().length());
    }

    private ReadDocument document(String name, String extension, List<DocumentTextUnit> units) {
        Path path = temp.resolve(name);
        DocumentSection section = new DocumentSection("main", "Main", "Main", "L", units);
        return new ReadDocument(path, temp, name, extension, List.of(section), 1,
                "test", 1, List.of());
    }
}
