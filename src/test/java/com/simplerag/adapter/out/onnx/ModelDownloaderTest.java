package com.simplerag.adapter.out.onnx;

import com.simplerag.application.dto.ModelDownloadProgress;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelDownloaderTest {
    @Test
    void writesBothModelFilesAndReportsProgressAgainstTheAdvertisedSize(@TempDir Path directory)
            throws Exception {
        byte[] payload = payload(1_500_000);
        HttpServer server = server(exchange -> respond(exchange, 200, payload));
        try {
            List<ModelDownloadProgress> reports = new ArrayList<>();
            ModelDownloader downloader = new ModelDownloader(directory, mirror(server));
            assertFalse(downloader.installed());

            downloader.download(reports::add);

            assertTrue(downloader.installed());
            assertEquals(2L * payload.length, downloader.installedBytes());
            assertArrayEqualsFile(payload, directory.resolve("tokenizer.json"));
            assertArrayEqualsFile(payload, directory.resolve("model_quint8_avx2.onnx"));
            assertTrue(reports.stream().anyMatch(report -> report.percent() == 100));
            assertTrue(reports.stream().allMatch(report -> report.fileCount() == 2));
            assertEquals(payload.length, reports.get(reports.size() - 1).totalBytes(),
                    "content-length is what makes the progress bar determinate");
        } finally {
            server.stop(0);
        }
    }

    /** A rejected request must not leave a truncated model that {@code installed()} would believe. */
    @Test
    void aRejectedDownloadLeavesTheDirectoryEmpty(@TempDir Path directory) throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 503, new byte[0]));
        try {
            ModelDownloader downloader = new ModelDownloader(directory, mirror(server));

            IOException failure = assertThrows(IOException.class, () -> downloader.download(report -> { }));

            assertTrue(failure.getMessage().contains("503"), failure.getMessage());
            assertFalse(downloader.installed());
            assertEquals(0, downloader.installedBytes());
            try (var entries = Files.list(directory)) {
                assertEquals(List.of(), entries.toList(), "not even a .part file may survive");
            }
        } finally {
            server.stop(0);
        }
    }

    private static void assertArrayEqualsFile(byte[] expected, Path file) throws IOException {
        assertEquals(expected.length, Files.size(file));
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, Files.readAllBytes(file));
    }

    private static byte[] payload(int size) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) bytes[index] = (byte) (index % 251);
        return bytes;
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static String mirror(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
