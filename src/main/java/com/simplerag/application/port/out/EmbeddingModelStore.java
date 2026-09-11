package com.simplerag.application.port.out;

import com.simplerag.application.dto.ModelDownloadProgress;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Where the local embedding model files live, and how to fetch them if they are missing. */
public interface EmbeddingModelStore {
    Path directory();

    /** True only when every file the model needs is present. */
    boolean installed();

    /** Size of the files that are present, so a partial install is visible instead of silent. */
    long installedBytes();

    String modelName();

    /**
     * Fetches the missing files, reporting progress as they arrive.
     *
     * @throws InterruptedException when the calling thread is interrupted; a cancelled download
     *                              leaves no partial file behind
     */
    void download(Consumer<ModelDownloadProgress> progress) throws IOException, InterruptedException;
}
