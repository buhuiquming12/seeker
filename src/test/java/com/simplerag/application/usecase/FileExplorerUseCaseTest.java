package com.simplerag.application.usecase;

import com.simplerag.application.dto.FileContentView;
import com.simplerag.application.dto.FileIndexState;
import com.simplerag.application.dto.FileNodeView;
import com.simplerag.application.freshness.FreshnessSnapshot;
import com.simplerag.application.freshness.FreshnessState;
import com.simplerag.application.port.out.KnowledgeSourceRepository;
import com.simplerag.application.runtime.ActiveKnowledgeContext;
import com.simplerag.application.runtime.ActiveKnowledgeRuntime;
import com.simplerag.application.runtime.IndexLifecycle;
import com.simplerag.model.IndexStatus;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.search.DocumentIndexEntry;
import com.simplerag.search.IndexHandle;
import com.simplerag.search.IndexSnapshot;
import com.simplerag.search.SemanticSearchEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileExplorerUseCaseTest {
    private static final String KB = "kb-files";
    private static final long REVISION = 7;

    @TempDir Path workspace;
    private Path root;
    private final SemanticSearchEngine engine = new SemanticSearchEngine(null);
    private final ActiveKnowledgeRuntime runtime = new ActiveKnowledgeRuntime(new IndexLifecycle());
    private FileExplorerUseCase explorer;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(workspace.resolve("kb-root"));
        Path indexed = write(root.resolve("indexed.md"), "# 标题\n\n正文一行\n");
        Path modified = write(root.resolve("modified.md"), "改过的内容\n");
        write(root.resolve("fresh.md"), "还没进索引\n");
        write(root.resolve("picture.png"), "not text");
        write(root.resolve(".env"), "TOKEN=secret");
        Files.createDirectory(root.resolve("node_modules"));
        Path nested = write(Files.createDirectory(root.resolve("sub")).resolve("nested.md"), "子目录文件\n");

        List<DocumentIndexEntry> entries = List.of(
                entry(indexed, Files.size(indexed), modifiedAt(indexed), List.of("c1", "c2")),
                // Stale size and timestamp: the snapshot predates the current file on disk.
                entry(modified, Files.size(modified) + 40, modifiedAt(modified) - 5_000, List.of("c3")),
                entry(nested, Files.size(nested), modifiedAt(nested), List.of("c4")),
                entry(root.resolve("gone.md"), 12, 1_000, List.of("c5")));
        engine.restore(new IndexSnapshot(IndexSnapshot.CURRENT_VERSION, List.of(root.toString()),
                List.of(), 1, "", null, entries));
        runtime.restore(context());
        explorer = new FileExplorerUseCase(runtime, sources(root));
    }

    @Test
    void reportsRootDirectoriesWithIndexedFileCount() {
        List<FileNodeView> roots = explorer.rootNodes(KB, REVISION);

        assertEquals(1, roots.size());
        FileNodeView node = roots.get(0);
        assertTrue(node.root());
        assertTrue(node.directory());
        assertEquals(FileIndexState.FOLDER, node.state());
        assertEquals(4, node.indexedDescendants());
    }

    @Test
    void derivesEveryFileStateFromSnapshotAndDisk() throws IOException {
        Map<String, FileNodeView> children = byName(explorer.children(KB, REVISION, root));

        assertEquals(FileIndexState.INDEXED, children.get("indexed.md").state());
        assertEquals(2, children.get("indexed.md").chunkCount());
        assertEquals(FileIndexState.MODIFIED, children.get("modified.md").state());
        assertEquals(FileIndexState.NOT_INDEXED, children.get("fresh.md").state());
        assertEquals(FileIndexState.UNSUPPORTED, children.get("picture.png").state());
        assertEquals(FileIndexState.IGNORED, children.get(".env").state());
        assertEquals(FileIndexState.IGNORED, children.get("node_modules").state());
        assertEquals(FileIndexState.FOLDER, children.get("sub").state());
        assertEquals(1, children.get("sub").indexedDescendants());
    }

    @Test
    void reportsSnapshotEntriesWhoseFilesAreGone() throws IOException {
        FileNodeView deleted = byName(explorer.children(KB, REVISION, root)).get("gone.md");

        assertEquals(FileIndexState.DELETED, deleted.state());
        assertFalse(deleted.directory());
        assertEquals(1, deleted.chunkCount());
    }

    @Test
    void listsDirectoriesBeforeFiles() throws IOException {
        List<FileNodeView> children = explorer.children(KB, REVISION, root);
        int lastDirectory = -1;
        int firstFile = children.size();
        for (int index = 0; index < children.size(); index++) {
            if (children.get(index).directory()) lastDirectory = index;
            else firstFile = Math.min(firstFile, index);
        }

        assertTrue(lastDirectory < firstFile, "directories must sort before files");
    }

    @Test
    void marksFileModifiedAfterItChangesOnDisk() throws IOException {
        Path target = root.resolve("indexed.md");
        Files.writeString(target, "# 标题\n\n正文一行\n加了一行\n");
        Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(
                modifiedAt(target) + 10_000));

        assertEquals(FileIndexState.MODIFIED,
                byName(explorer.children(KB, REVISION, root)).get("indexed.md").state());
    }

    @Test
    void readsTextThroughTheIndexingReaderWithRealLineNumbers() throws IOException {
        FileContentView content = explorer.readFile(KB, REVISION, root.resolve("indexed.md"));

        assertEquals("plain-text", content.readerId());
        assertTrue(content.text().startsWith("# 标题"));
        assertEquals(List.of("1", "2", "3", "4"), content.lineLabels());
        assertEquals(content.lineLabels().size(), content.text().split("\n", -1).length - 1);
    }

    @Test
    void refusesToReadWhatIndexingWouldNeverRead() throws IOException {
        FileContentView unsupported = explorer.readFile(KB, REVISION, root.resolve("picture.png"));
        FileContentView credential = explorer.readFile(KB, REVISION, root.resolve(".env"));

        assertTrue(unsupported.text().isEmpty());
        assertTrue(unsupported.notice().contains("reader"));
        assertTrue(credential.text().isEmpty());
        assertTrue(credential.notice().contains("排除"));
    }

    @Test
    void describesASingleFileTheSameWayTheTreeDoes() throws IOException {
        Map<String, FileNodeView> children = byName(explorer.children(KB, REVISION, root));

        assertEquals(children.get("indexed.md"), explorer.describe(KB, REVISION, root.resolve("indexed.md")));
        assertEquals(children.get("modified.md"), explorer.describe(KB, REVISION, root.resolve("modified.md")));
        assertEquals(children.get("fresh.md"), explorer.describe(KB, REVISION, root.resolve("fresh.md")));
        // A citation can outlive its file: the snapshot still holds it, the disk does not.
        assertEquals(children.get("gone.md"), explorer.describe(KB, REVISION, root.resolve("gone.md")));
        assertEquals(FileIndexState.DELETED, explorer.describe(KB, REVISION, root.resolve("gone.md")).state());
    }

    @Test
    void refusesPathsOutsideEverySourceRoot() throws IOException {
        Path outside = Files.createDirectory(workspace.resolve("outside"));
        Path secret = write(outside.resolve("secret.md"), "不该被看到");

        assertThrows(IllegalArgumentException.class, () -> explorer.children(KB, REVISION, outside));
        assertThrows(IllegalArgumentException.class, () -> explorer.readFile(KB, REVISION, secret));
        assertThrows(IllegalArgumentException.class, () -> explorer.describe(KB, REVISION, secret));
        assertThrows(IllegalArgumentException.class,
                () -> explorer.children(KB, REVISION, root.resolve("..")));
    }

    @Test
    void discardsResultsForAStaleKnowledgeBaseOrRevision() {
        assertThrows(StaleTaskException.class, () -> explorer.children(KB, REVISION + 1, root));
        assertThrows(StaleTaskException.class, () -> explorer.children("other-kb", REVISION, root));
        assertThrows(StaleTaskException.class, () -> explorer.rootNodes(KB, REVISION + 1));
        assertThrows(StaleTaskException.class,
                () -> explorer.describe(KB, REVISION + 1, root.resolve("indexed.md")));
    }

    private static Map<String, FileNodeView> byName(List<FileNodeView> nodes) {
        return nodes.stream().collect(Collectors.toMap(FileNodeView::name, Function.identity()));
    }

    private DocumentIndexEntry entry(Path file, long size, long modifiedAt, List<String> chunkIds) {
        return new DocumentIndexEntry(root.toString(), root.relativize(file).toString(), size,
                modifiedAt, "hash-" + file.getFileName(), "plain-text", 2, 3, new ArrayList<>(chunkIds));
    }

    private ActiveKnowledgeContext context() {
        KnowledgeBase kb = new KnowledgeBase(KB, "文件树知识库", "", 1, 1, REVISION, REVISION,
                IndexStatus.READY, null, null, null, null);
        FreshnessSnapshot freshness = new FreshnessSnapshot(KB, REVISION, FreshnessState.VERIFIED,
                1, "hash", 1, "verified", 1);
        return new ActiveKnowledgeContext(kb, freshness,
                new IndexHandle(KB, REVISION, IndexStatus.READY, engine));
    }

    private static KnowledgeSourceRepository sources(Path... roots) {
        return new KnowledgeSourceRepository() {
            @Override public List<Path> listSources(String knowledgeBaseId) { return List.of(roots); }
            @Override public void addSource(String knowledgeBaseId, Path path) { }
            @Override public void removeSource(String knowledgeBaseId, Path path) { }
        };
    }

    private static Path write(Path path, String content) throws IOException {
        Files.writeString(path, content);
        return path;
    }

    private static long modifiedAt(Path path) throws IOException {
        return Files.getLastModifiedTime(path).toMillis();
    }
}
