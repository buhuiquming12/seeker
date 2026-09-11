package com.simplerag.application.port.in;

import com.simplerag.application.dto.LocalModelView;
import com.simplerag.application.dto.ModelDownloadProgress;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Installing the local embedding model from inside the application, so semantic retrieval does not
 * depend on the user finding and running a setup script first.
 */
public interface InstallLocalModel {
    LocalModelView localModel();

    /**
     * Downloads whatever the model is missing and makes the running embedder pick it up, so the next
     * index build is semantic without a restart.
     *
     * @return the state the model is in afterwards
     */
    LocalModelView installLocalModel(Consumer<ModelDownloadProgress> progress)
            throws IOException, InterruptedException;
}
