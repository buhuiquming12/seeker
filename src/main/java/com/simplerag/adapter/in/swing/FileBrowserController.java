package com.simplerag.adapter.in.swing;

import com.simplerag.application.dto.FileContentView;
import com.simplerag.application.dto.FileNodeView;
import com.simplerag.application.port.in.BrowseKnowledgeFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class FileBrowserController {
    private final BrowseKnowledgeFiles files;

    public FileBrowserController(BrowseKnowledgeFiles files) {
        this.files = files;
    }

    public List<FileNodeView> roots(KnowledgeController.TaskIdentity identity) {
        return files.rootNodes(identity.knowledgeBaseId(), identity.sourceRevision());
    }

    public List<FileNodeView> children(KnowledgeController.TaskIdentity identity, Path directory)
            throws IOException {
        return files.children(identity.knowledgeBaseId(), identity.sourceRevision(), directory);
    }

    public FileContentView read(KnowledgeController.TaskIdentity identity, Path file) throws IOException {
        return files.readFile(identity.knowledgeBaseId(), identity.sourceRevision(), file);
    }

    /**
     * State and text of one file in a single background call, for pages reached from a citation or a
     * search hit rather than from the tree.
     */
    public FileOpen open(KnowledgeController.TaskIdentity identity, Path file) throws IOException {
        FileNodeView node = files.describe(identity.knowledgeBaseId(), identity.sourceRevision(), file);
        return new FileOpen(node, files.readFile(identity.knowledgeBaseId(), identity.sourceRevision(), file));
    }

    public record FileOpen(FileNodeView node, FileContentView content) { }
}
