package com.simplerag.adapter.out.openai;

import com.simplerag.application.conversation.ChatRequest;
import com.simplerag.application.conversation.RetrievalAttempt;
import com.simplerag.application.conversation.RetrievalDecision;
import com.simplerag.application.conversation.RetrievalPlanRequest;
import com.simplerag.application.conversation.TokenEstimator;
import com.simplerag.application.diagnostics.DiagnosticSink;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.RagAnswer;
import com.simplerag.model.RagCitation;
import com.simplerag.rag.ApiConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleClientTest {
    @Test
    void plannerParsesJsonSearchDecisionAndSendsCurrentEvidence() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"choices\":[{\"message\":{\"content\":\"```json\\n{\\\"action\\\":\\\"search\\\",\\\"query\\\":\\\"AskUseCase call chain\\\"}\\n```\"}}]}");
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ApiConfig config = new ApiConfig("http://127.0.0.1:" + port + "/v1", "secret", "model");
            DocumentChunk chunk = new DocumentChunk("c1", "src/AskUseCase.java", "src", "AskUseCase.java",
                    ".java", 60, 90, "evidence", 1L, null);
            RetrievalPlanRequest request = new RetrievalPlanRequest("kb", 2L, "where is retrieval?", List.of(),
                    List.of(new RagCitation(1, chunk, 0.9)),
                    List.of(new RetrievalAttempt("where is retrieval?", 1)), 3);

            RetrievalDecision decision = new OpenAiCompatibleClient().planRetrieval(config, request);

            assertTrue(decision.shouldSearch());
            assertEquals("AskUseCase call chain", decision.query());
            assertTrue(requestBody.get().contains("src/AskUseCase.java"));
            assertTrue(requestBody.get().contains("Previous searches"));
            assertTrue(requestBody.get().contains("\"stream\":false"));
            assertFalse(requestBody.get().contains("secret"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void plannerGeneratesSeveralQueriesForAMultiFacetQuestion() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                    + "\"{\\\"action\\\":\\\"search\\\",\\\"queries\\\":[\\\"IterativeRetrieval collect\\\","
                    + "\\\"planRetrieval callers\\\",\\\"IterativeRetrieval collect\\\"]}\"}}]}");
        });
        server.start();
        try {
            ApiConfig config = new ApiConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "secret", "model");
            DocumentChunk chunk = new DocumentChunk("c1", "src/AskUseCase.java", "src", "AskUseCase.java",
                    ".java", 60, 90, "evidence", 1L, null);
            RetrievalPlanRequest request = new RetrievalPlanRequest("kb", 2L, "how does planning work?", List.of(),
                    List.of(new RagCitation(1, chunk, 0.42)),
                    List.of(new RetrievalAttempt("how does planning work?", 1)), 3);

            RetrievalDecision decision = new OpenAiCompatibleClient().planRetrieval(config, request);

            assertTrue(decision.shouldSearch());
            // The duplicate the model emitted is dropped; the two distinct facets survive.
            assertEquals(List.of("IterativeRetrieval collect", "planRetrieval callers"), decision.queryTexts());
            // A bare string array carries no mode, which must keep meaning ordinary keyword retrieval.
            assertTrue(decision.queries().stream()
                    .allMatch(query -> query.mode() == RetrievalDecision.QueryMode.KEYWORD));
            // The planner needs the relevance score to judge "related but not sufficient".
            assertTrue(requestBody.get().contains("relevance 0.42"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void plannerParsesTypedQueriesAndRoutesAHypotheticalDocumentByMode() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                    + "\"{\\\"action\\\":\\\"search\\\",\\\"queries\\\":["
                    + "{\\\"text\\\":\\\"max_retries config key\\\",\\\"mode\\\":\\\"keyword\\\"},"
                    + "{\\\"text\\\":\\\"Retry count is set by max_retries, default 3.\\\","
                    + "\\\"mode\\\":\\\"Hypothetical\\\"},"
                    + "{\\\"text\\\":\\\"backoff policy\\\"}]}\"}}]}");
        });
        server.start();
        try {
            ApiConfig config = new ApiConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "secret", "model");
            RetrievalPlanRequest request = new RetrievalPlanRequest("kb", 1L, "how does retry work?",
                    List.of(), List.of(), List.of(new RetrievalAttempt("retry", 0)), 3);

            RetrievalDecision decision = new OpenAiCompatibleClient().planRetrieval(config, request);

            assertTrue(decision.shouldSearch());
            assertEquals(3, decision.queries().size());
            assertEquals(RetrievalDecision.QueryMode.KEYWORD, decision.queries().get(0).mode());
            // Mode matching is case-insensitive: models are inconsistent about capitalisation.
            assertEquals(RetrievalDecision.QueryMode.HYPOTHETICAL, decision.queries().get(1).mode());
            assertEquals("Retry count is set by max_retries, default 3.", decision.queries().get(1).text());
            // A typed entry with no mode falls back to keyword rather than being dropped.
            assertEquals(RetrievalDecision.QueryMode.KEYWORD, decision.queries().get(2).mode());
            assertTrue(requestBody.get().contains("hypothetical"),
                    "the planner must be told the mode exists before it can use it");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void plannerIsToldWhenVectorRetrievalIsUnavailable() throws Exception {
        AtomicReference<String> withVectors = new AtomicReference<>();
        AtomicReference<String> withoutVectors = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<AtomicReference<String>> target = new AtomicReference<>(withVectors);
        server.createContext("/v1/chat/completions", exchange -> {
            target.get().set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"choices\":[{\"message\":{\"content\":\"{\\\"action\\\":\\\"answer\\\"}\"}}]}");
        });
        server.start();
        try {
            ApiConfig config = new ApiConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "secret", "model");
            List<RetrievalAttempt> attempts = List.of(new RetrievalAttempt("q", 0));
            OpenAiCompatibleClient client = new OpenAiCompatibleClient();

            client.planRetrieval(config, new RetrievalPlanRequest("kb", 1L, "q", List.of(), List.of(),
                    attempts, 3, true));
            target.set(withoutVectors);
            client.planRetrieval(config, new RetrievalPlanRequest("kb", 1L, "q", List.of(), List.of(),
                    attempts, 3, false));

            assertFalse(withVectors.get().contains("Semantic (vector) retrieval is unavailable"));
            assertTrue(withoutVectors.get().contains("emit keyword queries only"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aTruncatedPlannerResponseIsReportedAsUnavailableRatherThanAsAnAnswer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange ->
                // Valid JSON, but the provider cut generation at the token ceiling: the query list is
                // complete-looking yet unfinished, so the decision must not be trusted.
                respond(exchange, "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":"
                        + "\"{\\\"action\\\":\\\"search\\\",\\\"queries\\\":[\\\"partial\\\"]}\"}}]}"));
        server.start();
        try {
            ApiConfig config = new ApiConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "secret", "model");
            RetrievalPlanRequest request = new RetrievalPlanRequest("kb", 1L, "q", List.of(), List.of(),
                    List.of(new RetrievalAttempt("q", 0)), 3);

            RetrievalDecision decision = new OpenAiCompatibleClient().planRetrieval(config, request);

            assertFalse(decision.shouldSearch());
            assertTrue(decision.plannerUnavailable());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void prosePlannerResponseIsUnavailableWhileAnExplicitAnswerStaysAnAnswer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange ->
                respond(exchange, "{\"choices\":[{\"message\":{\"content\":\"I think we have enough.\"}}]}"));
        HttpServer decided = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        decided.createContext("/v1/chat/completions", exchange ->
                respond(exchange, "{\"choices\":[{\"message\":{\"content\":\"{\\\"action\\\":\\\"answer\\\"}\"}}]}"));
        server.start();
        decided.start();
        try {
            RetrievalPlanRequest request = new RetrievalPlanRequest("kb", 1L, "q", List.of(), List.of(),
                    List.of(new RetrievalAttempt("q", 0)), 3);

            RetrievalDecision prose = new OpenAiCompatibleClient().planRetrieval(
                    new ApiConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "k", "m"), request);
            RetrievalDecision answer = new OpenAiCompatibleClient().planRetrieval(
                    new ApiConfig("http://127.0.0.1:" + decided.getAddress().getPort() + "/v1", "k", "m"), request);

            // Unparseable prose is a planner failure; only an explicit action:answer means "sufficient".
            assertTrue(prose.plannerUnavailable());
            assertFalse(answer.plannerUnavailable());
            assertFalse(answer.shouldSearch());
        } finally {
            server.stop(0);
            decided.stop(0);
        }
    }

    @Test
    void streamedAnswerReportsTheProvidersRealUsageAndCalibratesTheEstimator() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Use env vars [1].\"}}]}\n\n"
                    // Final usage frame: no choices, only the billed figures.
                    + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1234,"
                    + "\"completion_tokens\":56,\"total_tokens\":1290}}\n\n"
                    + "data: [DONE]\n\n";
            byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ApiConfig config = new ApiConfig("http://127.0.0.1:" + port + "/v1", "secret", "mock-model");
            DocumentChunk chunk = new DocumentChunk("c1", "docs/db.md", "docs", "db.md", ".md",
                    1, 3, "credentials belong in environment variables", 1L, null);
            ChatRequest request = new ChatRequest("kb", 1L, "where do credentials go?", List.of(),
                    List.of(new RagCitation(1, chunk, 0.9)));
            TokenEstimator estimator = new TokenEstimator();
            StringBuilder streamed = new StringBuilder();

            RagAnswer answer = new OpenAiCompatibleClient(DiagnosticSink.noop(), estimator)
                    .answerStream(config, request, streamed::append);

            assertEquals("Use env vars [1].", streamed.toString());
            assertEquals(1234, answer.usage().promptTokens());
            assertEquals(56, answer.usage().completionTokens());
            assertEquals(1290, answer.usage().totalTokens());
            assertTrue(requestBody.get().contains("\"include_usage\":true"),
                    "a streamed request must opt in to usage reporting or the turn cost is unknowable");
            assertNotEquals(1.0, estimator.factor("mock-model"),
                    "the real prompt_tokens should have corrected the local estimate");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aDroppedConnectionIsRetriedOnAFreshSocket() throws Exception {
        ServerSocket server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
        AtomicInteger connections = new AtomicInteger();
        Thread acceptor = new Thread(() -> {
            try {
                while (true) {
                    Socket accepted = server.accept();
                    if (connections.incrementAndGet() == 1) {
                        // A relay reaping an idle keep-alive socket looks exactly like this to the client.
                        accepted.setSoLinger(true, 0);
                        accepted.close();
                        continue;
                    }
                    try (Socket socket = accepted) {
                        drainHttpRequest(socket.getInputStream());
                        byte[] body = "{\"choices\":[{\"message\":{\"content\":\"{\\\"action\\\":\\\"answer\\\"}\"}}]}"
                                .getBytes(StandardCharsets.UTF_8);
                        OutputStream output = socket.getOutputStream();
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        output.write(body);
                        output.flush();
                    }
                }
            } catch (IOException stopped) {
                // The test closed the server socket.
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        try {
            ApiConfig config = new ApiConfig("http://127.0.0.1:" + server.getLocalPort() + "/v1", "secret", "model");
            RetrievalPlanRequest request = new RetrievalPlanRequest("kb", 1L, "where is retrieval?", List.of(),
                    List.of(), List.of(new RetrievalAttempt("where is retrieval?", 0)), 3);

            RetrievalDecision decision = new OpenAiCompatibleClient().planRetrieval(config, request);

            assertFalse(decision.shouldSearch());
            assertTrue(connections.get() >= 2, "the dropped connection should have been retried");
        } finally {
            server.close();
        }
    }

    /** Consumes request line, headers and any declared body so the response is not written too early. */
    private static void drainHttpRequest(InputStream input) throws IOException {
        StringBuilder headers = new StringBuilder();
        int value;
        while ((value = input.read()) != -1) {
            headers.append((char) value);
            if (headers.length() >= 4 && headers.indexOf("\r\n\r\n", headers.length() - 4) >= 0) break;
        }
        int length = 0;
        for (String line : headers.toString().split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(line.indexOf(':') + 1).strip());
            }
        }
        for (int read = 0; read < length; read++) {
            if (input.read() == -1) break;
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
