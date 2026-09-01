package com.simplerag.application;

import com.simplerag.application.conversation.ChatRequest;
import com.simplerag.application.port.out.ChatModel;
import com.simplerag.application.port.out.IndexRepository;
import com.simplerag.application.port.out.KnowledgeBaseRepository;
import com.simplerag.application.port.out.SecretStore;
import com.simplerag.application.port.out.SettingsRepository;
import com.simplerag.application.port.out.TextEmbedder;
import com.simplerag.application.usecase.KnowledgeService;
import com.simplerag.model.IndexStatus;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.RagAnswer;
import com.simplerag.model.RagCitation;
import com.simplerag.rag.ApiConfig;
import com.simplerag.search.IndexManifest;
import com.simplerag.search.IndexSnapshot;
import com.simplerag.support.ImmediateFreshnessMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeServiceUnitTest {
    @TempDir Path temporaryDirectory;

    @Test
    void applicationUseCaseRunsWithOnlyInMemoryFakes() throws Exception {
        FakePersistence persistence = new FakePersistence(temporaryDirectory);
        KnowledgeService service = new KnowledgeService(new FakeEmbedder(), persistence, persistence,
                new PlainSecretStore(), new FakeChatModel(), persistence, new ImmediateFreshnessMonitor());
        service.restore();
        Path source = temporaryDirectory.resolve("source");
        Files.createDirectories(source);
        Files.writeString(source.resolve("note.txt"), "store credentials in environment variables");

        service.addSource(source);
        service.rebuildCurrent(null);
        RagAnswer answer = service.ask("credentials", new ApiConfig("http://unused/v1", "", "fake"));

        assertEquals(IndexStatus.READY, service.indexStatus());
        assertEquals("fake answer", answer.text());
        assertFalse(answer.citations().isEmpty());
    }

    @Test
    void indexSaveFailureDoesNotMovePublishedRevision() throws Exception {
        FakePersistence persistence = new FakePersistence(temporaryDirectory);
        KnowledgeService service = service(persistence);
        Path first = source("save-first", "first published content");
        Path second = source("save-second", "second changed content");
        service.addSource(first);
        service.rebuildCurrent(null);
        long published = service.currentKnowledgeBase().publishedIndexRevision();
        service.addSource(second);
        persistence.failSave = true;

        assertThrows(IOException.class, () -> service.rebuildCurrent(null));
        assertEquals(IndexStatus.FAILED, service.indexStatus());
        assertEquals(published, service.currentKnowledgeBase().publishedIndexRevision());
    }

    @Test
    void databasePublishFailureDoesNotReplaceActiveIndex() throws Exception {
        FakePersistence persistence = new FakePersistence(temporaryDirectory);
        KnowledgeService service = service(persistence);
        Path first = source("publish-first", "first published content");
        Path second = source("publish-second", "second changed content");
        service.addSource(first);
        service.rebuildCurrent(null);
        long published = service.currentKnowledgeBase().publishedIndexRevision();
        int activeChunks = service.stats().chunks();
        service.addSource(second);
        persistence.failPublish = true;

        assertThrows(IllegalStateException.class, () -> service.rebuildCurrent(null));
        assertEquals(IndexStatus.FAILED, service.indexStatus());
        assertEquals(published, service.currentKnowledgeBase().publishedIndexRevision());
        assertEquals(activeChunks, service.stats().chunks());
    }

    private KnowledgeService service(FakePersistence persistence) {
        KnowledgeService service = new KnowledgeService(new FakeEmbedder(), persistence, persistence,
                new PlainSecretStore(), new FakeChatModel(), persistence, new ImmediateFreshnessMonitor());
        service.restore();
        return service;
    }

    private Path source(String name, String content) throws IOException {
        Path source = temporaryDirectory.resolve(name);
        Files.createDirectories(source);
        Files.writeString(source.resolve("note.txt"), content);
        return source;
    }

    private static final class FakePersistence implements KnowledgeBaseRepository, SettingsRepository, IndexRepository {
        private final Path root;
        private final Map<String, KnowledgeBase> knowledgeBases = new LinkedHashMap<>();
        private final Map<String, List<Path>> sources = new LinkedHashMap<>();
        private final Map<String, String> settings = new LinkedHashMap<>();
        private final Map<String, IndexSnapshot> indexes = new LinkedHashMap<>();
        private boolean failSave;
        private boolean failPublish;

        private FakePersistence(Path root) { this.root = root; }

        @Override public List<KnowledgeBase> listKnowledgeBases() { return List.copyOf(knowledgeBases.values()); }
        @Override public Optional<KnowledgeBase> findKnowledgeBase(String id) { return Optional.ofNullable(knowledgeBases.get(id)); }
        @Override public KnowledgeBase createKnowledgeBase(String name, String description) {
            String id = UUID.randomUUID().toString();
            KnowledgeBase value = new KnowledgeBase(id, name, description, 1, 1, 0, null, IndexStatus.EMPTY,
                    "", "", null, "");
            knowledgeBases.put(id, value);
            sources.put(id, new ArrayList<>());
            return value;
        }
        @Override public KnowledgeBase updateKnowledgeBase(String id, String name, String description) {
            KnowledgeBase old = knowledgeBases.get(id);
            KnowledgeBase value = new KnowledgeBase(id, name, description, old.createdAt(), 2,
                    old.sourceRevision(), old.publishedIndexRevision(), old.indexStatus(), old.lastIndexError(),
                    old.lastVerifiedSourceHash(), old.lastVerifiedAt(), old.freshnessReason());
            knowledgeBases.put(id, value);
            return value;
        }
        @Override public void deleteKnowledgeBase(String id) { knowledgeBases.remove(id); sources.remove(id); }
        @Override public List<Path> listSources(String id) { return List.copyOf(sources.get(id)); }
        @Override public void addSource(String id, Path path) {
            Path normalized = path.toAbsolutePath().normalize();
            if (!sources.get(id).contains(normalized)) { sources.get(id).add(normalized); dirty(id); }
        }
        @Override public void removeSource(String id, Path path) {
            if (sources.get(id).remove(path.toAbsolutePath().normalize())) dirty(id);
        }
        @Override public boolean beginIndexBuild(String id, long revision) {
            return beginIndexBuildRevision(id, revision) != null;
        }
        @Override public Long beginIndexBuildRevision(String id, long revision) {
            KnowledgeBase old = knowledgeBases.get(id);
            if (old.sourceRevision() != revision) return null;
            long buildRevision = old.publishedIndexRevision() != null
                    && old.publishedIndexRevision() == old.sourceRevision() ? revision + 1 : revision;
            replace(old, buildRevision, old.publishedIndexRevision(), IndexStatus.BUILDING, "");
            return buildRevision;
        }
        @Override public void markIndexBuildFailed(String id, long revision, String error) {
            KnowledgeBase old = knowledgeBases.get(id);
            replace(old, old.sourceRevision(), old.publishedIndexRevision(),
                    old.sourceRevision() == revision ? IndexStatus.FAILED : IndexStatus.DIRTY, error);
        }
        @Override public void markIndexIncompatible(String id, String error) { status(id, IndexStatus.INCOMPATIBLE, error); }
        @Override public void markIndexDirty(String id, String error) { status(id, IndexStatus.DIRTY, error); }
        @Override public boolean markIndexDirtyIfCurrent(String id, long revision, String reason,
                                                         String observedHash, Long verifiedAt) {
            KnowledgeBase old = knowledgeBases.get(id);
            if (old.sourceRevision() != revision) return false;
            knowledgeBases.put(id, new KnowledgeBase(old.id(), old.name(), old.description(), old.createdAt(),
                    old.updatedAt(), old.sourceRevision() + 1, old.publishedIndexRevision(), IndexStatus.DIRTY,
                    reason, observedHash == null ? old.lastVerifiedSourceHash() : observedHash,
                    verifiedAt == null ? old.lastVerifiedAt() : verifiedAt, reason));
            return true;
        }
        @Override public void recordSourceVerification(String id, long revision, String hash, long verifiedAt) {
            KnowledgeBase old = knowledgeBases.get(id);
            if (old.sourceRevision() != revision) return;
            knowledgeBases.put(id, new KnowledgeBase(old.id(), old.name(), old.description(), old.createdAt(),
                    old.updatedAt(), old.sourceRevision(), old.publishedIndexRevision(), old.indexStatus(),
                    old.lastIndexError(), hash, verifiedAt,
                    old.indexStatus() == IndexStatus.READY ? "" : old.freshnessReason()));
        }
        @Override public boolean publishIndex(IndexManifest manifest, String fileName) {
            if (failPublish) throw new IllegalStateException("injected database publish failure");
            KnowledgeBase old = knowledgeBases.get(manifest.knowledgeBaseId());
            if (old.sourceRevision() != manifest.sourceRevision() || old.indexStatus() != IndexStatus.BUILDING) return false;
            replace(old, old.sourceRevision(), manifest.sourceRevision(), IndexStatus.READY, "");
            return true;
        }
        @Override public Optional<String> findIndexFile(String id, long revision) { return Optional.of(revision + ".bin"); }
        @Override public Path databasePath() { return root.resolve("fake.db"); }
        @Override public Optional<String> getSetting(String key) { return Optional.ofNullable(settings.get(key)); }
        @Override public void putSetting(String key, String value) { settings.put(key, value); }
        @Override public Optional<IndexSnapshot> loadRevision(String id, long revision) { return Optional.ofNullable(indexes.get(id + ":" + revision)); }
        @Override public Optional<IndexSnapshot> loadLegacy(String id) { return Optional.empty(); }
        @Override public Optional<IndexSnapshot> loadGlobalLegacy() { return Optional.empty(); }
        @Override public String saveRevision(String id, IndexSnapshot snapshot) throws IOException {
            if (failSave) throw new IOException("injected index save failure");
            indexes.put(id + ":" + snapshot.manifest().sourceRevision(), snapshot);
            return snapshot.manifest().sourceRevision() + ".bin";
        }
        @Override public void deleteRevision(String id, long revision) { indexes.remove(id + ":" + revision); }
        @Override public void cleanTemporaryFiles(String id) { }
        @Override public void cleanUnreferenced(String id, Long revision) { }
        @Override public void deleteIndex(String id) { indexes.keySet().removeIf(key -> key.startsWith(id + ":")); }
        @Override public Path location(String id) { return root.resolve("indexes").resolve(id); }
        @Override public boolean usesDefaultLocation() { return false; }

        private void dirty(String id) {
            KnowledgeBase old = knowledgeBases.get(id);
            replace(old, old.sourceRevision() + 1, old.publishedIndexRevision(), IndexStatus.DIRTY, "");
        }
        private void status(String id, IndexStatus status, String error) {
            KnowledgeBase old = knowledgeBases.get(id);
            replace(old, old.sourceRevision(), old.publishedIndexRevision(), status, error);
        }
        private void replace(KnowledgeBase old, long revision, Long published, IndexStatus status, String error) {
            knowledgeBases.put(old.id(), new KnowledgeBase(old.id(), old.name(), old.description(), old.createdAt(),
                    old.updatedAt(), revision, published, status, error, old.lastVerifiedSourceHash(),
                    old.lastVerifiedAt(), status == IndexStatus.READY ? "" : old.freshnessReason()));
        }
    }

    private static final class FakeEmbedder implements TextEmbedder {
        @Override public boolean isConfigured() { return true; }
        @Override public List<float[]> embed(List<String> texts) {
            return texts.stream().map(text -> new float[]{text.length(), 1, 2, 3}).toList();
        }
        @Override public String modelName() { return "fake"; }
        @Override public String status() { return "ready"; }
        @Override public int dimension() { return 4; }
        @Override public void close() { }
    }

    private static final class PlainSecretStore implements SecretStore {
        @Override public String encrypt(String plainText) { return plainText; }
        @Override public String decrypt(String encoded) { return encoded; }
    }

    private static final class FakeChatModel implements ChatModel {
        @Override public List<String> listModels(ApiConfig config) { return List.of("fake"); }
        @Override public RagAnswer answer(ApiConfig config, ChatRequest request) {
            return new RagAnswer("fake answer", request.citations(), "fake");
        }
        @Override public RagAnswer answerStream(ApiConfig config, ChatRequest request,
                                                Consumer<com.simplerag.application.conversation.AnswerDelta> onDelta) {
            if (onDelta != null) {
                onDelta.accept(com.simplerag.application.conversation.AnswerDelta.answer("fake answer"));
            }
            return answer(config, request);
        }
    }
}
