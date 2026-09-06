package com.simplerag.application.port.in;

import com.simplerag.application.dto.FileContentView;
import com.simplerag.application.dto.FileNodeView;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Browses the source directories of the active knowledge base and reports how the published index
 * sees each file.
 *
 * <p>Every call carries {@code knowledgeBaseId + expectedRevision} so a result produced for a stale
 * knowledge base or source revision is rejected rather than rendered. Traversal is one directory
 * level per call, which keeps large trees off the caller's UI thread.
 */
public interface BrowseKnowledgeFiles {
    /** Source roots of the knowledge base, in the order they were added. */
    List<FileNodeView> rootNodes(String knowledgeBaseId, long expectedRevision);

    /**
     * Direct children of {@code directory}, directories first, plus entries the snapshot still
     * holds whose files have been deleted from disk.
     *
     * @throws IllegalArgumentException when {@code directory} lies outside every source root
     */
    List<FileNodeView> children(String knowledgeBaseId, long expectedRevision, Path directory)
            throws IOException;

    /**
     * How the published index sees a single file, for callers that hold only a path — a citation or
     * a search hit — and still need the same state the tree reports.
     *
     * @throws IllegalArgumentException when {@code file} lies outside every source root
     */
    FileNodeView describe(String knowledgeBaseId, long expectedRevision, Path file);

    /**
     * Text of {@code file} as the indexing readers extract it.
     *
     * @throws IllegalArgumentException when {@code file} lies outside every source root
     */
    FileContentView readFile(String knowledgeBaseId, long expectedRevision, Path file) throws IOException;
}
