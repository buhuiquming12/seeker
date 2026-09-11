package com.simplerag.adapter.out.onnx;

import com.simplerag.application.dto.ModelDownloadProgress;
import com.simplerag.application.port.out.EmbeddingModelStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Pure-Java downloader for the local multilingual embedding model. Replaces the retired
 * {@code download_model.py} so model setup no longer requires Python or huggingface_hub.
 *
 * <p>Files are fetched from the configurable Hugging Face mirror (defaults to hf-mirror.com),
 * following redirects to the backing CDN, and written atomically via a temporary file so an
 * interrupted download never leaves a half-written model in place.
 */
public final class ModelDownloader implements EmbeddingModelStore {
    private static final String DEFAULT_MIRROR = "https://hf-mirror.com";
    private static final String REPOSITORY = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2";
    private static final int BUFFER = 64 * 1024;
    /** Reporting every buffer would flood the UI with hundreds of events per second. */
    private static final long REPORT_STEP = 512 * 1024;

    // Remote path on the repo -> local file name inside the target directory.
    private static final String[][] FILES = {
            {"tokenizer.json", "tokenizer.json"},
            {"onnx/model_quint8_avx2.onnx", "model_quint8_avx2.onnx"},
    };

    private final HttpClient httpClient;
    private final String mirror;
    private final Path directory;

    public ModelDownloader(Path directory) {
        this(directory, resolveMirror());
    }

    public ModelDownloader(Path directory, String mirror) {
        this.directory = directory.toAbsolutePath().normalize();
        this.mirror = trimTrailingSlash(mirror);
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public Path directory() {
        return directory;
    }

    @Override
    public boolean installed() {
        for (String[] entry : FILES) {
            if (!Files.isRegularFile(directory.resolve(entry[1]))) return false;
        }
        return true;
    }

    @Override
    public long installedBytes() {
        long total = 0;
        for (String[] entry : FILES) {
            Path file = directory.resolve(entry[1]);
            try {
                if (Files.isRegularFile(file)) total += Files.size(file);
            } catch (IOException unreadable) {
                // A file we cannot measure contributes nothing; installed() already reports presence.
            }
        }
        return total;
    }

    @Override
    public String modelName() {
        return Langchain4jOnnxEmbeddingProvider.MODEL_NAME;
    }

    @Override
    public void download(Consumer<ModelDownloadProgress> progress) throws IOException, InterruptedException {
        Files.createDirectories(directory);
        for (int index = 0; index < FILES.length; index++) {
            downloadFile(FILES[index][0], directory.resolve(FILES[index][1]), index + 1, FILES.length,
                    progress == null ? report -> { } : progress);
        }
    }

    private void downloadFile(String remotePath, Path destination, int fileIndex, int fileCount,
                              Consumer<ModelDownloadProgress> progress)
            throws IOException, InterruptedException {
        String url = mirror + "/" + REPOSITORY + "/resolve/main/" + remotePath;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(30))
                .header("User-Agent", "SimpleRAG-ModelDownloader")
                .GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("下载失败 (HTTP " + response.statusCode() + "): " + url);
        }
        String name = destination.getFileName().toString();
        long expected = response.headers().firstValueAsLong("content-length").orElse(-1L);
        progress.accept(new ModelDownloadProgress(name, fileIndex, fileCount, 0, expected));
        Path temporary = Files.createTempFile(destination.getParent(), name, ".part");
        try (InputStream body = response.body()) {
            long bytes = copy(body, temporary, name, fileIndex, fileCount, expected, progress);
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            progress.accept(new ModelDownloadProgress(name, fileIndex, fileCount, bytes, bytes));
        } catch (IOException | InterruptedException | RuntimeException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
    }

    /**
     * Streams the body to {@code temporary}, reporting progress and honouring cancellation. Copying by
     * hand rather than through {@link Files#copy} is what makes both possible.
     */
    private static long copy(InputStream body, Path temporary, String name, int fileIndex, int fileCount,
                             long expected, Consumer<ModelDownloadProgress> progress)
            throws IOException, InterruptedException {
        byte[] buffer = new byte[BUFFER];
        long bytes = 0;
        long reported = 0;
        try (OutputStream out = Files.newOutputStream(temporary)) {
            int read;
            while ((read = body.read(buffer)) >= 0) {
                if (Thread.interrupted()) throw new InterruptedException("下载已取消");
                out.write(buffer, 0, read);
                bytes += read;
                if (bytes - reported >= REPORT_STEP) {
                    reported = bytes;
                    progress.accept(new ModelDownloadProgress(name, fileIndex, fileCount, bytes, expected));
                }
            }
        }
        return bytes;
    }

    private static String resolveMirror() {
        String configured = System.getenv("HF_ENDPOINT");
        return configured == null || configured.isBlank() ? DEFAULT_MIRROR : configured;
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null || value.isBlank() ? DEFAULT_MIRROR : value.strip();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    /** Command-line install, still used by {@code setup-semantic-model.cmd}. */
    public static void main(String[] args) throws Exception {
        Path target = Path.of(args.length > 0 ? args[0]
                : Langchain4jOnnxEmbeddingProvider.DEFAULT_MODEL_DIRECTORY);
        ModelDownloader downloader = new ModelDownloader(target);
        System.out.println("镜像: " + downloader.mirror);
        System.out.println("下载多语言语义模型到 " + downloader.directory);
        downloader.download(report -> {
            if (report.bytes() == report.totalBytes() && report.bytes() > 0) {
                System.out.printf("  %s: %.1f MB%n", report.file(), report.bytes() / 1024.0 / 1024.0);
            }
        });
        System.out.println("语义模型已就绪。请在 SimpleRAG 中重建索引。");
    }
}
