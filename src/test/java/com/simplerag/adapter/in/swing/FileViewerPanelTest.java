package com.simplerag.adapter.in.swing;

import com.simplerag.application.dto.DocumentReference;
import com.simplerag.application.dto.FileContentView;
import com.simplerag.application.dto.FileIndexState;
import com.simplerag.application.dto.FileNodeView;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileViewerPanelTest {
    private static final Path FILE = Path.of("kb", "AuthService.java").toAbsolutePath();
    private static final String TEXT = """
            package auth;
            class AuthService {
                boolean check() { return true; }
            }
            """;

    @Test
    void locatesTheCitedChunkByItsOwnText() throws Exception {
        String chunk = "class AuthService {\n    boolean check() { return true; }";
        AtomicReference<FileViewerPanel> panel = show(citation(chunk, 2, 3));

        int[] range = panel.get().focusRange();
        assertNotNull(range, "the cited chunk must be highlighted where it sits");
        assertEquals(chunk, panel.get().bodyText().substring(range[0], range[1]));
        assertTrue(panel.get().metaText().contains("已定位引用 L2-3"), panel.get().metaText());
    }

    /** Readers that renumber (pages, worksheet rows) still line up through the gutter labels. */
    @Test
    void fallsBackToTheReaderLineLabelsWhenTheChunkTextDoesNotMatchVerbatim() throws Exception {
        AtomicReference<FileViewerPanel> panel =
                show(citation("class AuthService { boolean check() { return true; } }", 2, 3));

        int[] range = panel.get().focusRange();
        assertNotNull(range);
        assertEquals("class AuthService {\n    boolean check() { return true; }",
                panel.get().bodyText().substring(range[0], range[1]));
    }

    @Test
    void reportsAStaleCitationInsteadOfScrollingSomewherePlausible() throws Exception {
        AtomicReference<FileViewerPanel> panel = show(citation("这段正文已经被删掉了", 87, 90));

        assertNull(panel.get().focusRange(), "nothing may be highlighted when the chunk is gone");
        assertTrue(panel.get().metaText().contains("未能定位引用 L87-90"), panel.get().metaText());
        assertTrue(panel.get().metaText().contains("索引后改动"), panel.get().metaText());
    }

    @Test
    void plainPreviewHighlightsNothing() throws Exception {
        AtomicReference<FileViewerPanel> panel = show(null);

        assertNull(panel.get().focusRange());
        assertEquals(TEXT, panel.get().bodyText());
    }

    private static AtomicReference<FileViewerPanel> show(DocumentReference focus) throws Exception {
        AtomicReference<FileViewerPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            FileViewerPanel created = new FileViewerPanel(path -> { }, path -> { }, path -> { });
            created.show(node(), content(), focus);
            panel.set(created);
        });
        return panel;
    }

    private static DocumentReference citation(String chunk, int startLine, int endLine) {
        return new DocumentReference("chunk-1", FILE, "AuthService.java", "java", startLine, endLine,
                chunk, false);
    }

    private static FileNodeView node() {
        return new FileNodeView(FILE, "AuthService.java", false, FileIndexState.INDEXED, 128,
                1_700_000_000_000L, "plain-text", 2, 0, "hash", false);
    }

    private static FileContentView content() {
        return new FileContentView(FILE, "plain-text", TEXT, List.of("1", "2", "3", "4"), false, "");
    }
}
