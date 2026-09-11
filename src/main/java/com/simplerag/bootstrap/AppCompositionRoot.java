package com.simplerag.bootstrap;

import com.simplerag.adapter.out.filesystem.FileSystemIndexRepository;
import com.simplerag.adapter.out.filesystem.FileSystemSourceFreshnessMonitor;
import com.simplerag.adapter.in.swing.AskController;
import com.simplerag.adapter.in.swing.FileBrowserController;
import com.simplerag.adapter.in.swing.KnowledgeController;
import com.simplerag.adapter.in.swing.SearchController;
import com.simplerag.application.usecase.KnowledgeService;
import com.simplerag.application.usecase.ApiSettingsUseCase;
import com.simplerag.application.usecase.AskUseCase;
import com.simplerag.application.usecase.DesktopQueryService;
import com.simplerag.application.usecase.FileExplorerUseCase;
import com.simplerag.application.usecase.IndexBuildUseCase;
import com.simplerag.application.usecase.KnowledgeBaseUseCases;
import com.simplerag.application.usecase.KnowledgeSourceUseCases;
import com.simplerag.application.usecase.LocalModelUseCase;
import com.simplerag.application.usecase.SearchUseCase;
import com.simplerag.application.usecase.WorkspaceLayoutUseCase;
import com.simplerag.application.runtime.ActiveKnowledgeRuntime;
import com.simplerag.application.runtime.IndexLifecycle;
import com.simplerag.application.freshness.FreshnessGate;
import com.simplerag.adapter.out.onnx.Langchain4jOnnxEmbeddingProvider;
import com.simplerag.adapter.out.onnx.ModelDownloader;
import com.simplerag.adapter.out.openai.OpenAiCompatibleClient;
import com.simplerag.adapter.out.openai.OpenAiCompatibleEmbeddingProvider;
import com.simplerag.adapter.out.openai.OpenAiCompatibleReranker;
import com.simplerag.search.FeatureReranker;
import com.simplerag.adapter.out.sqlite.AppRepository;
import com.simplerag.adapter.out.sqlite.DatabaseManager;
import com.simplerag.adapter.out.security.SecretCodec;
import com.simplerag.adapter.out.security.WindowsCredentialManagerSecretStore;
import com.simplerag.adapter.out.diagnostics.InMemoryDiagnosticLog;
import com.simplerag.application.diagnostics.DiagnosticReportService;
import com.simplerag.adapter.in.swing.MainFrame;
import com.simplerag.adapter.in.swing.StartupProgressWindow;
import com.simplerag.adapter.in.swing.ThemeBootstrap;
import com.simplerag.adapter.in.swing.BackgroundTaskCoordinator;
import com.simplerag.adapter.in.swing.SystemDesktopFileGateway;
import com.simplerag.application.conversation.ConversationContext;
import com.simplerag.application.conversation.ConversationStore;
import com.simplerag.application.conversation.TokenEstimator;

import javax.swing.SwingUtilities;
import java.nio.file.Path;

public final class AppCompositionRoot {
    /**
     * Shows a progress window first, then assembles the application off the event dispatch thread.
     *
     * <p>Opening the database and restoring an index takes seconds on a large knowledge base, and it
     * used to happen before any window existed: launching looked like nothing happening at all. It has
     * to stay off the event thread so the progress window can actually repaint while it runs.
     */
    public void start() {
        ThemeBootstrap.install();
        StartupProgressWindow progress = openProgressWindow();
        Thread startup = new Thread(() -> launch(progress), "simplerag-startup");
        startup.start();
    }

    private static StartupProgressWindow openProgressWindow() {
        try {
            java.util.concurrent.atomic.AtomicReference<StartupProgressWindow> created =
                    new java.util.concurrent.atomic.AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> created.set(new StartupProgressWindow()));
            return created.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("启动被中断", interrupted);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw new IllegalStateException("无法创建启动窗口", failure.getCause());
        }
    }

    private void launch(StartupProgressWindow progress) {
        try {
            progress.stage("正在打开本地数据库…");
            DatabaseManager database = new DatabaseManager();
            AppRepository sqlite = new AppRepository(database);
            InMemoryDiagnosticLog diagnostics = new InMemoryDiagnosticLog();
            progress.stage("正在读取已保存的模型配置…");
            WindowsCredentialManagerSecretStore secrets = new WindowsCredentialManagerSecretStore(
                    new SecretCodec(), diagnostics);
            // One estimator shared by the adapter (which observes real usage) and history trimming
            // (which spends the budget), so the budget tracks what the endpoint actually charges.
            TokenEstimator tokens = new TokenEstimator(sqlite);
            OpenAiCompatibleClient chat = new OpenAiCompatibleClient(diagnostics, tokens);
            ApiSettingsUseCase apiSettings = new ApiSettingsUseCase(sqlite, secrets, chat);
            Path modelDirectory = Langchain4jOnnxEmbeddingProvider.defaultModelDirectory();
            Langchain4jOnnxEmbeddingProvider localEmbeddings =
                    new Langchain4jOnnxEmbeddingProvider(modelDirectory);
            // The downloader writes where this provider reads, and makes it look again afterwards.
            LocalModelUseCase localModel = new LocalModelUseCase(new ModelDownloader(modelDirectory),
                    localEmbeddings::reload);
            OpenAiCompatibleEmbeddingProvider embeddings = new OpenAiCompatibleEmbeddingProvider(
                    localEmbeddings, apiSettings::embeddingApiConfig);
            OpenAiCompatibleReranker reranker = new OpenAiCompatibleReranker(
                    new FeatureReranker(), apiSettings::rerankApiConfig, diagnostics);
            FileSystemSourceFreshnessMonitor freshness = new FileSystemSourceFreshnessMonitor();
            ActiveKnowledgeRuntime runtime = new ActiveKnowledgeRuntime(new IndexLifecycle(), diagnostics);
            KnowledgeService service = new KnowledgeService(
                    embeddings, sqlite, sqlite, secrets, chat, new FileSystemIndexRepository(
                    Path.of(System.getProperty("user.home"), ".simplerag", "indexes")),
                    freshness, runtime, diagnostics, reranker);
            Runtime.getRuntime().addShutdownHook(new Thread(service::close, "simplerag-shutdown"));
            progress.stage("正在恢复知识库与索引…");
            service.restore();
            KnowledgeBaseUseCases knowledgeBases = new KnowledgeBaseUseCases(service);
            KnowledgeSourceUseCases sources = new KnowledgeSourceUseCases(service);
            IndexBuildUseCase indexBuild = new IndexBuildUseCase(service);
            SearchUseCase search = new SearchUseCase(runtime);
            AskUseCase ask = new AskUseCase(runtime, sqlite, new FreshnessGate(freshness), chat, sqlite, diagnostics);
            FileExplorerUseCase explorer = new FileExplorerUseCase(runtime, sqlite);
            DesktopQueryService desktopQueries = new DesktopQueryService(service);
            WorkspaceLayoutUseCase workspaceLayout = new WorkspaceLayoutUseCase(sqlite);
            progress.stage("正在准备界面…");
            SwingUtilities.invokeLater(() -> {
                MainFrame frame = new MainFrame(
                        new KnowledgeController(knowledgeBases, sources, indexBuild, desktopQueries,
                                localModel),
                        new SearchController(search), new AskController(ask, service,
                                new ConversationStore(ConversationContext.defaults(tokens))),
                        new FileBrowserController(explorer),
                        new BackgroundTaskCoordinator(), new SystemDesktopFileGateway(),
                        workspaceLayout, new DiagnosticReportService(runtime, diagnostics));
                frame.setVisible(true);
                progress.close();
                frame.initializeKnowledge(Path.of("examples", "knowledge"));
            });
        } catch (Throwable failure) {
            progress.failed(failure);
        }
    }
}
