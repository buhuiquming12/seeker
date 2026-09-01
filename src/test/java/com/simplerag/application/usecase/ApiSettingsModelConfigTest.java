package com.simplerag.application.usecase;

import com.simplerag.application.conversation.ChatRequest;
import com.simplerag.application.conversation.RetrievalPlanRequest;
import com.simplerag.application.port.out.ChatModel;
import com.simplerag.application.port.out.SecretStore;
import com.simplerag.application.port.out.SettingsRepository;
import com.simplerag.model.RagAnswer;
import com.simplerag.rag.ApiConfig;
import com.simplerag.rag.ModelApiConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiSettingsModelConfigTest {
    @Test
    void persistsChatEmbeddingAndRerankCredentialsIndependently() {
        MapSettings settings = new MapSettings();
        ApiSettingsUseCase useCase = new ApiSettingsUseCase(settings, new NamespacedSecrets(), new NoopChat());
        useCase.saveApiConfig(new ApiConfig("https://chat.example/v1", "chat-key", "chat-model"));
        useCase.saveEmbeddingApiConfig(new ModelApiConfig(true, "https://embed.example/v1", "embed-key", "embed-model", 1024));
        useCase.saveRerankApiConfig(new ModelApiConfig(true, "https://rerank.example/v1", "rerank-key", "rerank-model", 0));

        assertEquals("chat-key", useCase.apiConfig().apiKey());
        assertEquals("embed-key", useCase.embeddingApiConfig().apiKey());
        assertEquals("rerank-key", useCase.rerankApiConfig().apiKey());
        assertEquals(1024, useCase.embeddingApiConfig().dimensions());
        assertTrue(useCase.embeddingApiConfig().enabled());
        assertTrue(useCase.rerankApiConfig().enabled());
    }

    private static final class MapSettings implements SettingsRepository {
        private final Map<String, String> values = new HashMap<>();
        @Override public Optional<String> getSetting(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public void putSetting(String key, String value) { values.put(key, value); }
    }

    private static final class NamespacedSecrets implements SecretStore {
        @Override public String encrypt(String plainText) { return "default:" + plainText; }
        @Override public String decrypt(String encoded) { return encoded.startsWith("default:") ? encoded.substring(8) : ""; }
        @Override public String encrypt(String namespace, String plainText) { return namespace + ":" + plainText; }
        @Override public String decrypt(String namespace, String encoded) {
            String prefix = namespace + ":";
            return encoded.startsWith(prefix) ? encoded.substring(prefix.length()) : "";
        }
    }

    private static final class NoopChat implements ChatModel {
        @Override public List<String> listModels(ApiConfig config) { return List.of(); }
        @Override public RagAnswer answer(ApiConfig config, ChatRequest request) { throw new UnsupportedOperationException(); }
        @Override public RagAnswer answerStream(ApiConfig config, ChatRequest request,
                                                Consumer<com.simplerag.application.conversation.AnswerDelta> onDelta) {
            throw new UnsupportedOperationException();
        }
    }
}
