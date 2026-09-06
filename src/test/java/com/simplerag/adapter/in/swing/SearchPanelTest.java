package com.simplerag.adapter.in.swing;

import com.simplerag.application.dto.DocumentReference;
import com.simplerag.application.dto.SearchResultView;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchPanelTest {
    @Test
    void canOwnResultsAndPreviewWithoutMainFrame() throws Exception {
        AtomicReference<SearchPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            SearchPanel created = new SearchPanel(() -> { }, ignored -> { }, () -> { }, () -> { }, () -> { },
                    () -> { });
            created.extensions(Set.of("md"));
            DocumentReference document = new DocumentReference("id", Path.of("notes.md"), "notes.md", "md",
                    1, 2, "line one\nline two", false);
            created.results(List.of(new SearchResultView(document, 0.8, "原文匹配")));
            created.preview(created.selected());
            panel.set(created);
        });

        assertEquals("notes.md", panel.get().selected().document().fileName());
        assertEquals("全部", panel.get().extension());
    }
}
