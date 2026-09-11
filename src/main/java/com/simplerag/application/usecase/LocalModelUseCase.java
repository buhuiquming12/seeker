package com.simplerag.application.usecase;

import com.simplerag.application.dto.LocalModelView;
import com.simplerag.application.dto.ModelDownloadProgress;
import com.simplerag.application.port.in.InstallLocalModel;
import com.simplerag.application.port.out.EmbeddingModelStore;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Installs the local embedding model and tells the embedder to look again.
 *
 * <p>The reload matters: the embedder caches its loaded model, its status text and the file signature
 * that ties an index snapshot to the model that produced it. A session that started without the model
 * has already cached "missing" for all three, so without the reload a download would only take effect
 * after a restart.
 */
public final class LocalModelUseCase implements InstallLocalModel {
    private final EmbeddingModelStore store;
    private final Runnable reloadEmbedder;

    public LocalModelUseCase(EmbeddingModelStore store, Runnable reloadEmbedder) {
        this.store = store;
        this.reloadEmbedder = reloadEmbedder;
    }

    @Override
    public LocalModelView localModel() {
        return new LocalModelView(store.installed(), store.directory(), store.installedBytes(),
                store.modelName());
    }

    @Override
    public LocalModelView installLocalModel(Consumer<ModelDownloadProgress> progress)
            throws IOException, InterruptedException {
        Consumer<ModelDownloadProgress> report = progress == null ? ignored -> { } : progress;
        try {
            store.download(report);
        } finally {
            // Also on failure: a partial install must not leave the embedder reporting the state it
            // cached before the attempt.
            reloadEmbedder.run();
        }
        return localModel();
    }
}
