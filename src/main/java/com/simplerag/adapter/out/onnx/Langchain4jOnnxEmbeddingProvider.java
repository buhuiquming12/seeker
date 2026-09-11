package com.simplerag.adapter.out.onnx;

import com.simplerag.embedding.EmbeddingProvider;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.PoolingMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * In-process sentence-embedding provider backed by LangChain4j's {@link OnnxEmbeddingModel}.
 *
 * <p>This replaces the previous external Python worker. The same quantized multilingual ONNX model
 * ({@code model_quint8_avx2.onnx}) and its {@code tokenizer.json} are loaded directly through the
 * Java ONNX Runtime and the DJL HuggingFace tokenizer, so no Python, NumPy or pip packages are
 * required at runtime. Mean pooling and L2 normalization are performed by the model, matching the
 * behaviour of the retired {@code embedding_worker.py}.
 */
public final class Langchain4jOnnxEmbeddingProvider implements EmbeddingProvider {
    // Kept identical to the retired Python provider so existing index snapshots stay compatible.
    public static final String MODEL_NAME = "paraphrase-multilingual-MiniLM-L12-v2-int8";
    /** Where the model lives unless {@code -Dsimplerag.modelDir} says otherwise. */
    public static final String DEFAULT_MODEL_DIRECTORY = "models/multilingual-minilm";
    private static final String MODEL_FILE = "model_quint8_avx2.onnx";
    private static final String TOKENIZER_FILE = "tokenizer.json";

    private final Path modelPath;
    private final Path tokenizerPath;
    private final ModelFileSignatureCache signatureCache;
    private volatile OnnxEmbeddingModel model;
    private volatile String status;
    private volatile String fileSignature;

    public Langchain4jOnnxEmbeddingProvider() {
        this(defaultModelDirectory());
    }

    /** The directory both this provider and the in-app installer have to agree on. */
    public static Path defaultModelDirectory() {
        return Path.of(System.getProperty("simplerag.modelDir", DEFAULT_MODEL_DIRECTORY));
    }

    public Langchain4jOnnxEmbeddingProvider(Path modelDirectory) {
        Path directory = modelDirectory.toAbsolutePath().normalize();
        this.modelPath = directory.resolve(MODEL_FILE);
        this.tokenizerPath = directory.resolve(TOKENIZER_FILE);
        this.signatureCache = new ModelFileSignatureCache(Path.of(System.getProperty("user.home"),
                ".simplerag", "cache", "model-signatures.json"));
        this.status = isConfigured() ? "模型已就绪" : "未安装语义模型";
    }

    @Override
    public boolean isConfigured() {
        return Files.isRegularFile(modelPath) && Files.isRegularFile(tokenizerPath);
    }

    @Override
    public List<float[]> embed(List<String> texts) throws IOException {
        if (texts.isEmpty()) return List.of();
        OnnxEmbeddingModel embeddingModel = ensureModel();
        List<TextSegment> segments = new ArrayList<>(texts.size());
        for (String text : texts) segments.add(TextSegment.from(text));
        try {
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            List<float[]> vectors = new ArrayList<>(embeddings.size());
            for (Embedding embedding : embeddings) vectors.add(embedding.vector());
            status = "向量语义已启用";
            return vectors;
        } catch (RuntimeException inferenceFailure) {
            status = "模型错误";
            throw new IOException("生成本地语义向量失败：" + inferenceFailure.getMessage(), inferenceFailure);
        }
    }

    private OnnxEmbeddingModel ensureModel() throws IOException {
        OnnxEmbeddingModel current = model;
        if (current != null) return current;
        synchronized (this) {
            if (model == null) {
                if (!isConfigured()) {
                    throw new IOException("语义模型未安装，请先运行 setup-semantic-model.cmd");
                }
                try {
                    model = new OnnxEmbeddingModel(modelPath, tokenizerPath, PoolingMode.MEAN);
                    status = "向量语义已启用";
                } catch (RuntimeException loadFailure) {
                    status = "模型错误";
                    throw new IOException("无法加载本地语义模型：" + loadFailure.getMessage(), loadFailure);
                }
            }
            return model;
        }
    }

    @Override
    public String modelName() {
        return MODEL_NAME;
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public int dimension() {
        return 384;
    }

    @Override
    public com.simplerag.search.EmbeddingModelSignature signature() {
        return new com.simplerag.search.EmbeddingModelSignature(getClass().getName(), MODEL_NAME,
                modelFilesSignature(), dimension(), 1);
    }

    private String modelFilesSignature() {
        String current = fileSignature;
        if (current != null) return current;
        synchronized (this) {
            if (fileSignature == null) {
                try {
                    fileSignature = signatureCache.signature(List.of(modelPath, tokenizerPath));
                } catch (Exception failure) {
                    fileSignature = "unavailable";
                }
            }
            return fileSignature;
        }
    }

    @Override
    public synchronized void close() {
        model = null;
    }

    /**
     * Forgets everything derived from the model files, so an install that happened while the
     * application was running takes effect without a restart.
     *
     * <p>The loaded model, the status text and the file signature that ties an index snapshot to the
     * model that produced it are all cached on first use. A session that started with no model has
     * cached the answers for "missing" in all three.
     */
    public synchronized void reload() {
        model = null;
        fileSignature = null;
        status = isConfigured() ? "模型已就绪" : "未安装语义模型";
    }
}
