package com.simplerag.application.usecase;

import com.simplerag.application.dto.FileContentView;
import com.simplerag.application.dto.FileIndexState;
import com.simplerag.application.dto.FileNodeView;
import com.simplerag.application.port.in.BrowseKnowledgeFiles;
import com.simplerag.application.port.out.KnowledgeSourceRepository;
import com.simplerag.application.runtime.ActiveKnowledgeRuntime;
import com.simplerag.search.DocumentIndexEntry;
import com.simplerag.search.DocumentReaderRegistry;
import com.simplerag.search.DocumentScanner;
import com.simplerag.search.DocumentSection;
import com.simplerag.search.DocumentTextUnit;
import com.simplerag.search.IndexHandle;
import com.simplerag.search.ReadDocument;
import com.simplerag.search.SensitiveFilePolicy;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Answers "what is in my knowledge base directories and did it make it into the index" by comparing
 * the published snapshot against the filesystem.
 *
 * <p>Nothing new is persisted: file states are derived from {@code IndexSnapshot.documentEntries()},
 * which already records size, modification time, content hash, reader and chunk ids per file. The
 * size/modification-time comparison is deliberately the same one
 * {@code FileFingerprint.capture} uses to decide reuse, so a file shown as {@code MODIFIED} is
 * exactly a file the next rebuild will re-read.
 */
public final class FileExplorerUseCase implements BrowseKnowledgeFiles {
    /** Guards the viewer against loading a whole book into a JTextArea. */
    private static final int MAX_PREVIEW_LINES = 20_000;
    private static final int MAX_PREVIEW_CHARS = 400_000;

    private final ActiveKnowledgeRuntime runtime;
    private final KnowledgeSourceRepository sources;
    private final DocumentReaderRegistry readers;
    private final SensitiveFilePolicy sensitiveFiles;
    /** Snapshot-scoped derived view; recomputed only when the runtime publishes a new snapshot. */
    private volatile IndexedFiles cached;

    public FileExplorerUseCase(ActiveKnowledgeRuntime runtime, KnowledgeSourceRepository sources) {
        this(runtime, sources, new DocumentReaderRegistry(), new SensitiveFilePolicy());
    }

    public FileExplorerUseCase(ActiveKnowledgeRuntime runtime, KnowledgeSourceRepository sources,
                               DocumentReaderRegistry readers, SensitiveFilePolicy sensitiveFiles) {
        this.runtime = runtime;
        this.sources = sources;
        this.readers = readers == null ? new DocumentReaderRegistry() : readers;
        this.sensitiveFiles = sensitiveFiles == null ? new SensitiveFilePolicy() : sensitiveFiles;
    }

    @Override
    public List<FileNodeView> rootNodes(String knowledgeBaseId, long expectedRevision) {
        requireIdentity(knowledgeBaseId, expectedRevision);
        IndexedFiles indexed = indexedFiles();
        List<FileNodeView> nodes = new ArrayList<>();
        for (Path root : roots(knowledgeBaseId)) {
            boolean present = Files.isDirectory(root);
            nodes.add(new FileNodeView(root, root.toString(), true,
                    present ? FileIndexState.FOLDER : FileIndexState.DELETED,
                    0, lastModified(root), "", 0, indexed.descendants(root), "", true));
        }
        return List.copyOf(nodes);
    }

    @Override
    public List<FileNodeView> children(String knowledgeBaseId, long expectedRevision, Path directory)
            throws IOException {
        requireIdentity(knowledgeBaseId, expectedRevision);
        Path target = normalize(directory);
        requireInsideRoots(knowledgeBaseId, target);
        IndexedFiles indexed = indexedFiles();
        List<FileNodeView> nodes = new ArrayList<>();
        Map<Path, DocumentIndexEntry> missing = new LinkedHashMap<>(indexed.childrenOf(target));
        if (Files.isDirectory(target)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
                for (Path child : stream) {
                    missing.remove(child);
                    nodes.add(node(child, indexed));
                }
            }
        }
        // Whatever the snapshot still cites but the disk no longer has: the index is over-reporting.
        for (Map.Entry<Path, DocumentIndexEntry> deleted : missing.entrySet()) {
            DocumentIndexEntry entry = deleted.getValue();
            nodes.add(new FileNodeView(deleted.getKey(), fileName(deleted.getKey()), false,
                    FileIndexState.DELETED, entry.size(), entry.modifiedAt(), entry.readerId(),
                    entry.chunkIds().size(), 0, entry.contentHash(), false));
        }
        nodes.sort(Comparator.comparing((FileNodeView node) -> !node.directory())
                .thenComparing(FileNodeView::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(nodes);
    }

    @Override
    public FileNodeView describe(String knowledgeBaseId, long expectedRevision, Path file) {
        requireIdentity(knowledgeBaseId, expectedRevision);
        Path target = normalize(file);
        requireInsideRoots(knowledgeBaseId, target);
        IndexedFiles indexed = indexedFiles();
        DocumentIndexEntry entry = indexed.entry(target);
        // Same reading as children(): a path the snapshot still cites but the disk no longer has.
        if (entry != null && !Files.exists(target)) {
            return new FileNodeView(target, fileName(target), false, FileIndexState.DELETED,
                    entry.size(), entry.modifiedAt(), entry.readerId(), entry.chunkIds().size(), 0,
                    entry.contentHash(), false);
        }
        return node(target, indexed);
    }

    @Override
    public FileContentView readFile(String knowledgeBaseId, long expectedRevision, Path file)
            throws IOException {
        requireIdentity(knowledgeBaseId, expectedRevision);
        Path target = normalize(file);
        Path root = requireInsideRoots(knowledgeBaseId, target);
        if (!Files.isRegularFile(target)) {
            return FileContentView.unavailable(target, "文件已不在磁盘上，内容来自上次索引时的记录");
        }
        FileNodeView node = node(target, indexedFiles());
        if (!node.previewable()) {
            return FileContentView.unavailable(target, switch (node.state()) {
                case IGNORED -> "该文件按安全或忽略策略被排除，不会读取内容";
                case UNSUPPORTED -> "没有可处理该格式的 reader，索引与预览都不会读取它";
                case OVERSIZED -> "文件超过该 reader 的大小限制，索引与预览都不会读取它";
                default -> "无法预览该文件";
            });
        }
        ReadDocument document;
        try {
            document = readers.read(target, root);
        } catch (IOException failure) {
            return FileContentView.unavailable(target, "读取失败：" + failure.getMessage());
        }
        if (document == null || document.sections().isEmpty()) {
            return FileContentView.unavailable(target, "该文件没有可提取的文本（空文件或二进制内容）");
        }
        return render(target, document);
    }

    /** Renders reader sections with their own numbering so the gutter matches retrieval citations. */
    private static FileContentView render(Path target, ReadDocument document) {
        List<DocumentSection> sections = document.sections();
        boolean labelled = sections.size() > 1 || !sections.get(0).locationLabel().isBlank()
                || !sections.get(0).title().isBlank();
        StringBuilder text = new StringBuilder();
        List<String> labels = new ArrayList<>();
        boolean truncated = false;
        for (DocumentSection section : sections) {
            if (truncated) break;
            if (labelled) {
                if (!labels.isEmpty()) { text.append('\n'); labels.add(""); }
                String heading = section.locationLabel().isBlank() ? section.title() : section.locationLabel();
                text.append("── ").append(heading.isBlank() ? section.id() : heading).append(" ──\n");
                labels.add("");
            }
            for (DocumentTextUnit unit : section.units()) {
                if (labels.size() >= MAX_PREVIEW_LINES || text.length() >= MAX_PREVIEW_CHARS) {
                    truncated = true;
                    break;
                }
                text.append(unit.text()).append('\n');
                labels.add(Integer.toString(unit.number()));
            }
        }
        String notice = truncated
                ? "内容较长，仅显示前 " + labels.size() + " 行；完整内容请用系统默认程序打开" : "";
        return new FileContentView(target, document.readerId(), text.toString(), labels, truncated, notice);
    }

    private FileNodeView node(Path path, IndexedFiles indexed) {
        String name = fileName(path);
        BasicFileAttributes attributes = attributes(path);
        if (attributes == null) {
            return new FileNodeView(path, name, false, FileIndexState.IGNORED, 0, 0, "", 0, 0, "", false);
        }
        if (attributes.isSymbolicLink()) {
            // The scanner never follows links, so the tree must not claim their targets are indexed.
            return new FileNodeView(path, name, attributes.isDirectory(), FileIndexState.IGNORED,
                    0, attributes.lastModifiedTime().toMillis(), "", 0, 0, "", false);
        }
        if (attributes.isDirectory()) {
            boolean ignored = DocumentScanner.isIgnoredDirectory(name) || sensitiveFiles.deniesDirectory(name);
            return new FileNodeView(path, name, true,
                    ignored ? FileIndexState.IGNORED : FileIndexState.FOLDER,
                    0, attributes.lastModifiedTime().toMillis(), "", 0,
                    ignored ? 0 : indexed.descendants(path), "", false);
        }
        long size = attributes.size();
        long modifiedAt = attributes.lastModifiedTime().toMillis();
        DocumentIndexEntry entry = indexed.entry(path);
        String hash = entry == null ? "" : entry.contentHash();
        int chunks = entry == null ? 0 : entry.chunkIds().size();
        if (sensitiveFiles.deniesFile(name)) {
            return new FileNodeView(path, name, false, FileIndexState.IGNORED, size, modifiedAt,
                    "", 0, 0, "", false);
        }
        Optional<DocumentReaderRegistry.ReaderDescriptor> descriptor = readers.descriptor(path);
        if (descriptor.isEmpty()) {
            return new FileNodeView(path, name, false, FileIndexState.UNSUPPORTED, size, modifiedAt,
                    "", 0, 0, "", false);
        }
        DocumentReaderRegistry.ReaderDescriptor reader = descriptor.get();
        if (size > reader.maxFileSizeBytes()) {
            return new FileNodeView(path, name, false, FileIndexState.OVERSIZED, size, modifiedAt,
                    reader.id(), 0, 0, hash, false);
        }
        FileIndexState state;
        if (entry == null) state = FileIndexState.NOT_INDEXED;
        else if (entry.size() == size && entry.modifiedAt() == modifiedAt) state = FileIndexState.INDEXED;
        else state = FileIndexState.MODIFIED;
        return new FileNodeView(path, name, false, state, size, modifiedAt, reader.id(), chunks, 0,
                hash, false);
    }

    private IndexedFiles indexedFiles() {
        // documentEntries() is a stable list reference per published snapshot; snapshot() would
        // rebuild the entire chunk list on every directory expansion.
        List<DocumentIndexEntry> entries = runtime.current().indexHandle().engine().documentEntries();
        IndexedFiles current = cached;
        if (current != null && current.entries == entries) return current;
        IndexedFiles rebuilt = IndexedFiles.of(entries);
        cached = rebuilt;
        return rebuilt;
    }

    private List<Path> roots(String knowledgeBaseId) {
        return sources.listSources(knowledgeBaseId).stream().map(FileExplorerUseCase::normalize).toList();
    }

    /**
     * Keeps the tree bound to the knowledge base. Without this the panel would be a general-purpose
     * filesystem browser that reads and renders arbitrary files under a knowledge-base label.
     */
    private Path requireInsideRoots(String knowledgeBaseId, Path target) {
        Path owner = null;
        for (Path root : roots(knowledgeBaseId)) {
            if (target.startsWith(root) && (owner == null || root.getNameCount() > owner.getNameCount())) {
                owner = root;
            }
        }
        if (owner == null) {
            throw new IllegalArgumentException("路径不在当前知识库的数据源目录内：" + target);
        }
        return owner;
    }

    private IndexHandle requireIdentity(String knowledgeBaseId, long revision) {
        IndexHandle handle = runtime.current().indexHandle();
        if (!handle.knowledgeBaseId().equals(knowledgeBaseId) || handle.sourceRevision() != revision) {
            throw new StaleTaskException("知识库或数据版本已变化，任务结果已丢弃");
        }
        return handle;
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException unavailable) {
            return 0;
        }
    }

    private static BasicFileAttributes attributes(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** Snapshot document entries indexed by absolute path, by parent directory and by subtree count. */
    private record IndexedFiles(List<DocumentIndexEntry> entries, Map<Path, DocumentIndexEntry> byPath,
                                Map<Path, Map<Path, DocumentIndexEntry>> byParent,
                                Map<Path, Integer> descendantCounts) {
        static IndexedFiles of(List<DocumentIndexEntry> entries) {
            Map<Path, DocumentIndexEntry> byPath = new HashMap<>();
            Map<Path, Map<Path, DocumentIndexEntry>> byParent = new HashMap<>();
            Map<Path, Integer> counts = new HashMap<>();
            for (DocumentIndexEntry entry : entries) {
                if (entry.root().isBlank() || entry.relativePath().isBlank()) continue;
                Path root = Path.of(entry.root()).toAbsolutePath().normalize();
                Path path = root.resolve(entry.relativePath()).normalize();
                byPath.put(path, entry);
                Path parent = path.getParent();
                if (parent != null) {
                    byParent.computeIfAbsent(parent, ignored -> new LinkedHashMap<>()).put(path, entry);
                }
                for (Path ancestor = parent; ancestor != null && ancestor.startsWith(root);
                     ancestor = ancestor.getParent()) {
                    counts.merge(ancestor, 1, Integer::sum);
                }
            }
            return new IndexedFiles(entries, Map.copyOf(byPath), Map.copyOf(byParent), Map.copyOf(counts));
        }

        DocumentIndexEntry entry(Path path) { return byPath.get(path); }
        Map<Path, DocumentIndexEntry> childrenOf(Path directory) {
            return byParent.getOrDefault(directory, Map.of());
        }
        int descendants(Path directory) { return descendantCounts.getOrDefault(directory, 0); }
    }
}
