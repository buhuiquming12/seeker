package com.simplerag.consistency;

import com.simplerag.application.conversation.ChatRequest;
import com.simplerag.adapter.out.filesystem.FileSystemIndexRepository;
import com.simplerag.adapter.out.filesystem.FileSystemSourceFreshnessMonitor;
import com.simplerag.adapter.out.filesystem.FreshnessReconciler;
import com.simplerag.adapter.out.openai.OpenAiCompatibleClient;
import com.simplerag.adapter.out.security.SecretCodec;
import com.simplerag.adapter.out.sqlite.AppRepository;
import com.simplerag.adapter.out.sqlite.DatabaseManager;
import com.simplerag.application.usecase.KnowledgeService;
import com.simplerag.embedding.EmbeddingProvider;
import com.simplerag.model.IndexStatus;
import com.simplerag.rag.ApiConfig;
import com.simplerag.search.EmbeddingModelSignature;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeFreshnessTest {
    @TempDir Path temporaryDirectory;

    @Test
    void readyFileModificationBecomesDirtyWithoutRestart() throws Exception {
        Path source = source("modify", "original content");
        try (FileSystemSourceFreshnessMonitor monitor = monitor();
             KnowledgeService service = service(monitor, new TestEmbeddingProvider())) {
            publish(service, source);

            Files.writeString(source.resolve("note.txt"), "changed content with a different size");

            await(() -> service.indexStatus() == IndexStatus.DIRTY, Duration.ofSeconds(5));
            assertTrue(service.currentKnowledgeBase().freshnessReason().contains("源文件"));
        }
    }

    @Test
    void deletingSensitiveFileKeepsRemoteHttpRequestCountAtZero() throws Exception {
        Path source = source("delete", "sensitive customer token");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try (FileSystemSourceFreshnessMonitor monitor = monitor();
             KnowledgeService service = service(monitor, new TestEmbeddingProvider())) {
            publish(service, source);
            Files.delete(source.resolve("note.txt"));
            await(() -> service.indexStatus() == IndexStatus.DIRTY, Duration.ofSeconds(5));

            assertThrows(IllegalStateException.class, () -> service.ask("token?",
                    new ApiConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "", "model")));
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void closedWatcherBlocksRemoteRagEvenIfDatabaseStillSaysReady() throws Exception {
        Path source = source("closed", "content");
        AtomicInteger requests = new AtomicInteger();
        try (FileSystemSourceFreshnessMonitor monitor = monitor();
             KnowledgeService service = service(monitor, new TestEmbeddingProvider(), requests)) {
            publish(service, source);
            monitor.close();

            assertThrows(IllegalStateException.class,
                    () -> service.ask("content", new ApiConfig("http://unused/v1", "", "model")));
            assertEquals(0, requests.get());
        }
    }

    @Test
    void sourceEventDuringBuildCannotPublishAndOldEventCannotDirtyNewReadyIndex() throws Exception {
        Path source = source("build-race", "first version");
        BlockingEmbeddingProvider provider = new BlockingEmbeddingProvider();
        try (FileSystemSourceFreshnessMonitor monitor = monitor();
             KnowledgeService service = service(monitor, provider)) {
            publish(service, source);
            Files.writeString(source.resolve("note.txt"), "second version with more text");
            await(() -> service.indexStatus() == IndexStatus.DIRTY, Duration.ofSeconds(5));

            provider.blockNext.set(true);
            var executor = Executors.newSingleThreadExecutor();
            try {
                var rebuilding = executor.submit(() -> service.rebuildCurrent(null));
                assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
                Files.writeString(source.resolve("note.txt"), "third version changed during the build");
                provider.release.countDown();

                ExecutionException failure = assertThrows(ExecutionException.class, rebuilding::get);
                assertTrue(failure.getCause() instanceof RuntimeException || failure.getCause() instanceof IOException);
                assertEquals(IndexStatus.DIRTY, service.indexStatus());
            } finally {
                provider.release.countDown();
                executor.shutdownNow();
            }

            service.rebuildCurrent(null);
            await(() -> service.indexStatus() == IndexStatus.READY, Duration.ofSeconds(5));
            Thread.sleep(250);
            assertEquals(IndexStatus.READY, service.indexStatus());
        }
    }

    @Test
    void failedIncrementalBuildKeepsPreviouslyPublishedRevisionFile() throws Exception {
        Path source = source("incremental-failure", "first published version with enough text");
        Path database = temporaryDirectory.resolve("incremental-failure.db");
        Path indexes = temporaryDirectory.resolve("incremental-failure-indexes");
        AppRepository repository = new AppRepository(new DatabaseManager(database));
        FailingEmbeddingProvider provider = new FailingEmbeddingProvider();
        try (FileSystemSourceFreshnessMonitor monitor = monitor();
             KnowledgeService service = new KnowledgeService(provider, repository, repository, new SecretCodec(),
                     new OpenAiCompatibleClient(), new FileSystemIndexRepository(indexes), monitor)) {
            publish(service, source);
            String knowledgeBaseId = service.currentKnowledgeBase().id();
            long publishedRevision = service.currentKnowledgeBase().publishedIndexRevision();

            Files.writeString(source.resolve("note.txt"), "changed content that requires a new incremental vector");
            await(() -> service.indexStatus() == IndexStatus.DIRTY, Duration.ofSeconds(5));
            assertTrue(service.sourceRevision() > publishedRevision);
            provider.failNext.set(true);

            assertThrows(IOException.class, () -> service.rebuildCurrent(null));
            assertEquals(publishedRevision, service.currentKnowledgeBase().publishedIndexRevision());
            assertTrue(Files.isRegularFile(indexes.resolve(knowledgeBaseId).resolve(publishedRevision + ".bin")));
        }
    }

    private void publish(KnowledgeService service, Path source) throws Exception {
        service.restore();
        service.addSource(source);
        service.rebuildCurrent(null);
        assertEquals(IndexStatus.READY, service.indexStatus());
    }

    private Path source(String name, String content) throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve(name));
        Files.writeString(source.resolve("note.txt"), content);
        return source;
    }

    private FileSystemSourceFreshnessMonitor monitor() {
        return new FileSystemSourceFreshnessMonitor(new FreshnessReconciler(),
                Duration.ofMillis(50), Duration.ofMillis(150));
    }

    private KnowledgeService service(FileSystemSourceFreshnessMonitor monitor, EmbeddingProvider provider) {
        AppRepository repository = new AppRepository(new DatabaseManager(temporaryDirectory.resolve(
                "app-" + System.nanoTime() + ".db")));
        return new KnowledgeService(provider, repository, repository, new SecretCodec(),
                new OpenAiCompatibleClient(), new FileSystemIndexRepository(
                temporaryDirectory.resolve("indexes-" + System.nanoTime())), monitor);
    }

    private KnowledgeService service(FileSystemSourceFreshnessMonitor monitor, EmbeddingProvider provider,
                                     AtomicInteger requests) {
        AppRepository repository = new AppRepository(new DatabaseManager(temporaryDirectory.resolve(
                "app-" + System.nanoTime() + ".db")));
        com.simplerag.application.port.out.ChatModel chat = new com.simplerag.application.port.out.ChatModel() {
            @Override public List<String> listModels(ApiConfig config) { return List.of(); }
            @Override public com.simplerag.model.RagAnswer answer(ApiConfig config,
                    com.simplerag.application.conversation.ChatRequest request) {
                requests.incrementAndGet();
                return new com.simplerag.model.RagAnswer("", request.citations(), "model");
            }
            @Override public com.simplerag.model.RagAnswer answerStream(ApiConfig config,
                    com.simplerag.application.conversation.ChatRequest request,
                    java.util.function.Consumer<com.simplerag.application.conversation.AnswerDelta> onDelta) {
                return answer(config, request);
            }
        };
        return new KnowledgeService(provider, repository, repository, new SecretCodec(), chat,
                new FileSystemIndexRepository(temporaryDirectory.resolve("indexes-" + System.nanoTime())), monitor);
    }

    private static void await(java.util.function.BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "condition was not met within " + timeout);
    }

    private static class TestEmbeddingProvider implements EmbeddingProvider {
        @Override public boolean isConfigured() { return true; }
        @Override public List<float[]> embed(List<String> texts) throws IOException {
            List<float[]> vectors = new ArrayList<>();
            for (String text : texts) {
                float[] vector = new float[8];
                for (int index = 0; index < text.length(); index++) vector[index % vector.length]++;
                vectors.add(vector);
            }
            return vectors;
        }
        @Override public String modelName() { return "freshness-test"; }
        @Override public String status() { return "ready"; }
        @Override public int dimension() { return 8; }
        @Override public EmbeddingModelSignature signature() {
            return new EmbeddingModelSignature(getClass().getName(), modelName(), "test", 8, 1);
        }
        @Override public void close() { }
    }

    private static final class BlockingEmbeddingProvider extends TestEmbeddingProvider {
        private final AtomicBoolean blockNext = new AtomicBoolean();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override public List<float[]> embed(List<String> texts) throws IOException {
            if (blockNext.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("timed out waiting for test release");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", interrupted);
                }
            }
            return super.embed(texts);
        }
    }

    private static final class FailingEmbeddingProvider extends TestEmbeddingProvider {
        private final AtomicBoolean failNext = new AtomicBoolean();

        @Override public List<float[]> embed(List<String> texts) throws IOException {
            if (failNext.compareAndSet(true, false)) throw new IOException("injected incremental embedding failure");
            return super.embed(texts);
        }
    }
}
