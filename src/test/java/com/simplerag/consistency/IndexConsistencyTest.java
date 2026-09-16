package com.simplerag.consistency;

import com.simplerag.adapter.out.filesystem.FileSystemIndexRepository;
import com.simplerag.application.usecase.KnowledgeService;
import com.simplerag.application.usecase.StaleTaskException;
import com.simplerag.embedding.EmbeddingProvider;
import com.simplerag.model.IndexStatus;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.rag.ApiConfig;
import com.simplerag.adapter.out.openai.OpenAiCompatibleClient;
import com.simplerag.adapter.out.sqlite.AppRepository;
import com.simplerag.adapter.out.sqlite.DatabaseManager;
import com.simplerag.adapter.out.security.SecretCodec;
import com.simplerag.search.IndexIdentity;
import com.simplerag.search.EmbeddingModelSignature;
import com.simplerag.search.IndexManifest;
import com.simplerag.search.IndexSnapshot;
import com.simplerag.search.SemanticSearchEngine;
import com.simplerag.support.ImmediateFreshnessMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IndexConsistencyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rebuildingReadyIndexAllocatesANewRevision() throws Exception {
        AppRepository repository = new AppRepository(new DatabaseManager(temporaryDirectory.resolve("rebuild-ready.db")));
        KnowledgeBase knowledgeBase = repository.createKnowledgeBase("ready", "");
        Path source = Files.createDirectories(temporaryDirectory.resolve("ready-source"));
        Files.writeString(source.resolve("note.txt"), "published content for revision allocation");
        repository.addSource(knowledgeBase.id(), source);
        KnowledgeBase dirty = repository.findKnowledgeBase(knowledgeBase.id()).orElseThrow();
        assertTrue(repository.beginIndexBuild(knowledgeBase.id(), dirty.sourceRevision()));
        IndexManifest manifest = new IndexManifest(knowledgeBase.id(), dirty.sourceRevision(),
                IndexIdentity.sourceSetHash(List.of(source)), "model", 8, IndexIdentity.CHUNKING_VERSION,
                IndexSnapshot.CURRENT_VERSION, System.currentTimeMillis());
        assertTrue(repository.publishIndex(manifest, dirty.sourceRevision() + ".bin"));

        Long rebuildRevision = repository.beginIndexBuildRevision(knowledgeBase.id(), dirty.sourceRevision());

        assertEquals(dirty.sourceRevision() + 1, rebuildRevision);
        KnowledgeBase rebuilding = repository.findKnowledgeBase(knowledgeBase.id()).orElseThrow();
        assertEquals(dirty.sourceRevision(), rebuilding.publishedIndexRevision());
        assertEquals(IndexStatus.BUILDING, rebuilding.indexStatus());
    }

    @Test
    void obsoleteBuildCannotOverwriteANewerBuildState() throws Exception {
        AppRepository repository = new AppRepository(
                new DatabaseManager(temporaryDirectory.resolve("overlapping-builds.db")));
        KnowledgeBase created = repository.createKnowledgeBase("overlap", "");
        Path firstSource = Files.createDirectories(temporaryDirectory.resolve("overlap-source-a"));
        repository.addSource(created.id(), firstSource);
        KnowledgeBase beforeA = repository.findKnowledgeBase(created.id()).orElseThrow();
        long buildA = repository.beginIndexBuildRevision(created.id(), beforeA.sourceRevision());

        // A source change invalidates A and the UI immediately starts B at the new revision.
        Path secondSource = Files.createDirectories(temporaryDirectory.resolve("overlap-source-b"));
        repository.addSource(created.id(), secondSource);
        KnowledgeBase beforeB = repository.findKnowledgeBase(created.id()).orElseThrow();
        long buildB = repository.beginIndexBuildRevision(created.id(), beforeB.sourceRevision());
        assertNotEquals(buildA, buildB);

        assertFalse(repository.markIndexBuildDiscarded(created.id(), buildA, "A is stale"));
        repository.markIndexBuildFailed(created.id(), buildA, "A failed late");

        KnowledgeBase current = repository.findKnowledgeBase(created.id()).orElseThrow();
        assertEquals(buildB, current.sourceRevision());
        assertEquals(IndexStatus.BUILDING, current.indexStatus());
    }

    @Test
    void migratesLegacyDatabaseAndPreservesRows() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("legacy.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE knowledge_base(id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE knowledge_source(id INTEGER PRIMARY KEY AUTOINCREMENT, knowledge_base_id TEXT NOT NULL, path TEXT NOT NULL, UNIQUE(knowledge_base_id, path))");
            statement.executeUpdate("CREATE TABLE app_setting(setting_key TEXT PRIMARY KEY, setting_value TEXT NOT NULL)");
            statement.executeUpdate("INSERT INTO knowledge_base VALUES('legacy', 'Legacy', '', 1, 2)");
        }

        AppRepository repository = new AppRepository(new DatabaseManager(databaseFile));
        KnowledgeBase migrated = repository.findKnowledgeBase("legacy").orElseThrow();

        assertEquals(0, migrated.sourceRevision());
        assertNull(migrated.publishedIndexRevision());
        assertEquals(IndexStatus.EMPTY, migrated.indexStatus());
        assertEquals("", migrated.lastVerifiedSourceHash());
        assertNull(migrated.lastVerifiedAt());
        assertEquals("", migrated.freshnessReason());
    }

    @Test
    void sourceMutationMarksDirtyAndReadyPublicationUsesRevisionFile() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Files.createDirectories(source);
        Files.writeString(source.resolve("note.txt"), "database connection credentials should use environment variables");
        DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("app.db"));
        KnowledgeService service = service(database, new FakeEmbeddingProvider("model-a", 8));
        service.restore();

        service.addSource(source);
        assertEquals(IndexStatus.DIRTY, service.indexStatus());
        assertEquals(1, service.sourceRevision());

        service.rebuildCurrent(null);
        assertEquals(IndexStatus.READY, service.indexStatus());
        KnowledgeBase published = service.currentKnowledgeBase();
        assertEquals(published.sourceRevision(), published.publishedIndexRevision());
        assertFalse(published.lastVerifiedSourceHash().isBlank());
        assertNotNull(published.lastVerifiedAt());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("indexes")
                .resolve(published.id()).resolve(published.sourceRevision() + ".bin")));

        service.removeSource(source);
        assertEquals(IndexStatus.DIRTY, service.indexStatus());
        assertThrows(IllegalStateException.class,
                () -> service.ask("secret?", new ApiConfig("http://127.0.0.1:1/v1", "", "model")));
    }

    @Test
    void staleBuildCannotPublishAfterSourceRevisionChanges() throws Exception {
        DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("stale.db"));
        AppRepository repository = new AppRepository(database);
        KnowledgeBase knowledgeBase = repository.createKnowledgeBase("kb", "");
        Path first = temporaryDirectory.resolve("first");
        Path second = temporaryDirectory.resolve("second");
        Files.createDirectories(first);
        Files.createDirectories(second);
        repository.addSource(knowledgeBase.id(), first);
        KnowledgeBase building = repository.findKnowledgeBase(knowledgeBase.id()).orElseThrow();
        assertTrue(repository.beginIndexBuild(knowledgeBase.id(), building.sourceRevision()));
        repository.addSource(knowledgeBase.id(), second);

        IndexManifest obsolete = new IndexManifest(knowledgeBase.id(), building.sourceRevision(),
                IndexIdentity.sourceSetHash(List.of(first)), "model", 8, IndexIdentity.CHUNKING_VERSION,
                IndexSnapshot.CURRENT_VERSION, System.currentTimeMillis());
        assertFalse(repository.publishIndex(obsolete, building.sourceRevision() + ".bin"));
        assertEquals(IndexStatus.DIRTY, repository.findKnowledgeBase(knowledgeBase.id()).orElseThrow().indexStatus());
    }

    @Test
    void incompatibleModelNeverEmbedsQueryOrHighlights() throws Exception {
        Path source = temporaryDirectory.resolve("semantic");
        Files.createDirectories(source);
        Files.writeString(source.resolve("note.txt"), "collapse the sidebar to make room for the editor");
        FakeEmbeddingProvider originalProvider = new FakeEmbeddingProvider("same-model", 8, "file-a");
        SemanticSearchEngine original = new SemanticSearchEngine(originalProvider);
        original.index(List.of(source), null);
        long now = System.currentTimeMillis();
        IndexManifest manifest = new IndexManifest("kb", 1, IndexIdentity.sourceSetHash(List.of(source)),
                originalProvider.signature().value(), 8, IndexIdentity.CHUNKING_VERSION,
                IndexSnapshot.CURRENT_VERSION, now);

        FakeEmbeddingProvider replacement = new FakeEmbeddingProvider("same-model", 8, "file-b");
        SemanticSearchEngine restored = new SemanticSearchEngine(replacement);
        restored.restore(original.snapshot(manifest));
        int callsBeforeSearch = replacement.calls;

        restored.search("sidebar", 5, "all");
        assertEquals(callsBeforeSearch, replacement.calls);
        assertFalse(restored.semanticEnabled());
        assertTrue(restored.semanticHighlights("sidebar", restored.snapshot().chunks().get(0), 2).isEmpty());
    }

    @Test
    void incompatibleVectorDimensionNeverEmbedsQuery() throws Exception {
        Path source = temporaryDirectory.resolve("dimension-source");
        Files.createDirectories(source);
        Files.writeString(source.resolve("note.txt"), "dimension compatibility content");
        FakeEmbeddingProvider originalProvider = new FakeEmbeddingProvider("same-model", 8, "same-file");
        SemanticSearchEngine original = new SemanticSearchEngine(originalProvider);
        original.index(List.of(source), null);
        IndexManifest manifest = new IndexManifest("kb", 1, IndexIdentity.sourceSetHash(List.of(source)),
                originalProvider.signature().value(), 8, IndexIdentity.CHUNKING_VERSION,
                IndexSnapshot.CURRENT_VERSION, System.currentTimeMillis());
        FakeEmbeddingProvider replacement = new FakeEmbeddingProvider("same-model", 4, "same-file");
        SemanticSearchEngine restored = new SemanticSearchEngine(replacement);
        restored.restore(original.snapshot(manifest));

        restored.search("dimension", 5, "all");

        assertEquals(0, replacement.calls);
        assertFalse(restored.semanticEnabled());
    }

    @Test
    void failedRebuildKeepsPublishedRevisionButBlocksRemoteRag() throws Exception {
        Path first = temporaryDirectory.resolve("first-source");
        Path second = temporaryDirectory.resolve("second-source");
        Files.createDirectories(first);
        Files.createDirectories(second);
        Files.writeString(first.resolve("first.txt"), "first published content about authentication tokens");
        Files.writeString(second.resolve("second.txt"), "new content that will fail embedding");
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider("model-a", 8);
        KnowledgeService service = service(new DatabaseManager(temporaryDirectory.resolve("failure.db")), provider);
        service.restore();
        service.addSource(first);
        service.rebuildCurrent(null);
        long publishedRevision = service.currentKnowledgeBase().publishedIndexRevision();

        service.addSource(second);
        provider.fail = true;
        assertThrows(IOException.class, () -> service.rebuildCurrent(null));
        KnowledgeBase failed = service.currentKnowledgeBase();
        assertEquals(IndexStatus.FAILED, failed.indexStatus());
        assertEquals(publishedRevision, failed.publishedIndexRevision());
        assertThrows(IllegalStateException.class,
                () -> service.ask("tokens", new ApiConfig("http://127.0.0.1:1/v1", "", "model")));
    }

    @Test
    void externalFileChangeIsDetectedWhenPublishedIndexIsRestored() throws Exception {
        Path source = temporaryDirectory.resolve("watched-source");
        Files.createDirectories(source);
        Path note = source.resolve("note.txt");
        Files.writeString(note, "original content");
        DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("external-change.db"));
        KnowledgeService first = service(database, new FakeEmbeddingProvider("model-a", 8));
        first.restore();
        first.addSource(source);
        first.rebuildCurrent(null);
        assertEquals(IndexStatus.READY, first.indexStatus());

        Files.writeString(note, "changed content with a different size");
        KnowledgeService restored = service(database, new FakeEmbeddingProvider("model-a", 8));
        restored.restore();

        assertEquals(IndexStatus.DIRTY, restored.indexStatus());
        assertFalse(restored.semanticEnabled());
    }

    @Test
    void switchingKnowledgeBaseRejectsResultsForOldIdentity() throws Exception {
        Path source = temporaryDirectory.resolve("identity-source");
        Files.createDirectories(source);
        Files.writeString(source.resolve("note.txt"), "identity scoped search content");
        KnowledgeService service = service(new DatabaseManager(temporaryDirectory.resolve("identity.db")),
                new FakeEmbeddingProvider("model-a", 8));
        service.restore();
        service.addSource(source);
        service.rebuildCurrent(null);
        String oldId = service.currentKnowledgeBase().id();
        long oldRevision = service.sourceRevision();

        service.createKnowledgeBase("second", "");

        assertThrows(StaleTaskException.class,
                () -> service.search(oldId, oldRevision, "identity", 5, "all"));
    }

    @Test
    void cancelledBuildKeepsPublishedRevisionAndCleansTemporaryFile() throws Exception {
        Path source = temporaryDirectory.resolve("cancel-source");
        Path added = temporaryDirectory.resolve("cancel-added");
        Files.createDirectories(source);
        Files.createDirectories(added);
        Files.writeString(source.resolve("note.txt"), "published content");
        Files.writeString(added.resolve("new.txt"), "new content");
        KnowledgeService service = service(new DatabaseManager(temporaryDirectory.resolve("cancel.db")),
                new FakeEmbeddingProvider("model-a", 8));
        service.restore();
        service.addSource(source);
        service.rebuildCurrent(null);
        long published = service.currentKnowledgeBase().publishedIndexRevision();
        service.addSource(added);

        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> service.rebuildCurrent(null));
        } finally {
            Thread.interrupted();
        }

        assertEquals(IndexStatus.DIRTY, service.indexStatus());
        assertEquals(published, service.currentKnowledgeBase().publishedIndexRevision());
        Path indexDirectory = temporaryDirectory.resolve("indexes").resolve(service.currentKnowledgeBase().id());
        if (Files.isDirectory(indexDirectory)) {
            try (var files = Files.list(indexDirectory)) {
                assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
            }
        }
    }

    @Test
    void startupRecoversInterruptedBuildingStateAndRemovesTemporaryFile() throws Exception {
        DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("crash.db"));
        AppRepository repository = new AppRepository(database);
        KnowledgeBase knowledgeBase = repository.createKnowledgeBase("crash", "");
        Path source = temporaryDirectory.resolve("crash-source");
        Files.createDirectories(source);
        repository.addSource(knowledgeBase.id(), source);
        KnowledgeBase dirty = repository.findKnowledgeBase(knowledgeBase.id()).orElseThrow();
        assertTrue(repository.beginIndexBuild(knowledgeBase.id(), dirty.sourceRevision()));
        Path indexDirectory = temporaryDirectory.resolve("indexes").resolve(knowledgeBase.id());
        Files.createDirectories(indexDirectory);
        Path temporary = indexDirectory.resolve(dirty.sourceRevision() + ".bin.tmp");
        Files.writeString(temporary, "partial");

        KnowledgeService restored = service(database, new FakeEmbeddingProvider("model-a", 8));
        restored.restore();

        assertEquals(IndexStatus.FAILED, restored.indexStatus());
        assertFalse(Files.exists(temporary));
    }

    private KnowledgeService service(DatabaseManager database, EmbeddingProvider provider) {
        AppRepository repository = new AppRepository(database);
        return new KnowledgeService(provider, repository, repository, new SecretCodec(),
                new OpenAiCompatibleClient(), new FileSystemIndexRepository(temporaryDirectory.resolve("indexes")),
                new ImmediateFreshnessMonitor());
    }

    private static final class FakeEmbeddingProvider implements EmbeddingProvider {
        private final String modelName;
        private final int dimension;
        private final String fileSignature;
        private int calls;
        private boolean fail;

        private FakeEmbeddingProvider(String modelName, int dimension) {
            this(modelName, dimension, modelName);
        }

        private FakeEmbeddingProvider(String modelName, int dimension, String fileSignature) {
            this.modelName = modelName;
            this.dimension = dimension;
            this.fileSignature = fileSignature;
        }

        @Override public boolean isConfigured() { return true; }

        @Override
        public List<float[]> embed(List<String> texts) throws IOException {
            calls++;
            if (fail) throw new IOException("injected embedding failure");
            List<float[]> result = new ArrayList<>();
            for (String text : texts) {
                float[] vector = new float[dimension];
                for (int index = 0; index < text.length(); index++) {
                    vector[Math.floorMod(text.charAt(index), vector.length)]++;
                }
                result.add(vector);
            }
            return result;
        }

        @Override public String modelName() { return modelName; }
        @Override public String status() { return "ready"; }
        @Override public int dimension() { return dimension; }
        @Override public EmbeddingModelSignature signature() {
            return new EmbeddingModelSignature(getClass().getName(), modelName, fileSignature, dimension, 1);
        }
        @Override public void close() { }
    }
}
