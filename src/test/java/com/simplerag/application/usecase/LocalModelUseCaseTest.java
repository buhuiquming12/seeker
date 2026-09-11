package com.simplerag.application.usecase;

import com.simplerag.application.dto.LocalModelView;
import com.simplerag.application.dto.ModelDownloadProgress;
import com.simplerag.application.port.out.EmbeddingModelStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalModelUseCaseTest {
    @Test
    void anInstallMakesTheEmbedderLookAgainAndReportsWhatLanded() throws Exception {
        FakeStore store = new FakeStore();
        AtomicInteger reloads = new AtomicInteger();
        List<ModelDownloadProgress> seen = new ArrayList<>();

        LocalModelView before = new LocalModelUseCase(store, reloads::incrementAndGet).localModel();
        assertFalse(before.installed());
        assertEquals(0, before.bytes());

        LocalModelView after = new LocalModelUseCase(store, reloads::incrementAndGet)
                .installLocalModel(seen::add);
        assertTrue(after.installed());
        assertEquals(127_535_388L, after.bytes());
        assertEquals(1, reloads.get(), "a cached 'missing' would survive the download otherwise");
        assertEquals(2, seen.size());
    }

    /**
     * A failed or cancelled download can still have replaced one of the two files, so the embedder has
     * to re-read the directory either way rather than keep the answer it cached before the attempt.
     */
    @Test
    void aFailedInstallStillMakesTheEmbedderLookAgain() {
        FakeStore store = new FakeStore();
        store.failure = new IOException("下载失败 (HTTP 503)");
        AtomicInteger reloads = new AtomicInteger();

        assertThrows(IOException.class,
                () -> new LocalModelUseCase(store, reloads::incrementAndGet).installLocalModel(report -> { }));
        assertEquals(1, reloads.get());
    }

    private static final class FakeStore implements EmbeddingModelStore {
        private boolean installed;
        private IOException failure;

        @Override public Path directory() { return Path.of("models", "multilingual-minilm"); }
        @Override public boolean installed() { return installed; }
        @Override public long installedBytes() { return installed ? 127_535_388L : 0L; }
        @Override public String modelName() { return "paraphrase-multilingual-MiniLM-L12-v2-int8"; }

        @Override
        public void download(Consumer<ModelDownloadProgress> progress) throws IOException {
            progress.accept(new ModelDownloadProgress("tokenizer.json", 1, 2, 9_081_518L, 9_081_518L));
            if (failure != null) throw failure;
            progress.accept(new ModelDownloadProgress("model_quint8_avx2.onnx", 2, 2,
                    118_453_870L, 118_453_870L));
            installed = true;
        }
    }
}
