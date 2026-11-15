package dev.plexusmodloader.compat.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdleTimeoutDownloadTest {

    private static final Duration IDLE = Duration.ofMillis(500);
    private static final byte[] BODY = new byte[100];

    private static final byte[] SHORT = new byte[BODY.length / 2];

    static {
        Arrays.fill(BODY, (byte) 7);
        Arrays.fill(SHORT, (byte) 9);
    }

    @TempDir
    Path folder;

    private HttpServer server;
    private ExecutorService executor;
    private HttpClient client;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/stalls", exchange -> {
            requests.incrementAndGet();
            stall(exchange);
        });
        server.createContext("/slow", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, BODY.length);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < BODY.length; i += 10) {
                    out.write(BODY, i, 10);
                    out.flush();
                    pause(Duration.ofMillis(150));
                }
            }
        });
        server.createContext("/stallsOnce", exchange -> {
            if (requests.incrementAndGet() == 1) {
                stall(exchange);
                return;
            }
            exchange.sendResponseHeaders(200, BODY.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(BODY);
            }
        });
        server.createContext("/stallsOnceThenShort", exchange -> {
            if (requests.incrementAndGet() == 1) {
                stall(exchange);
                return;
            }
            exchange.sendResponseHeaders(200, SHORT.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(SHORT);
            }
        });
        server.start();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void aBodyThatStopsArrivingFailsInsteadOfWaitingForever() {
        Path target = folder.resolve("stalled.jar");
        HttpTimeoutException thrown = assertTimeoutPreemptively(
                Duration.ofSeconds(20),
                () -> assertThrows(
                        HttpTimeoutException.class,
                        () -> IdleTimeoutDownload.send(
                                client, request("/stalls"), HttpResponse.BodyHandlers.ofFile(target), IDLE, 2)));
        assertEquals(2, requests.get(), thrown.getMessage());
    }

    @Test
    void aSlowBodyThatKeepsArrivingCompletes() throws Exception {
        Path target = folder.resolve("slow.jar");
        HttpResponse<Path> response = assertTimeoutPreemptively(
                Duration.ofSeconds(20),
                () -> IdleTimeoutDownload.send(
                        client, request("/slow"), HttpResponse.BodyHandlers.ofFile(target), IDLE, 1));
        assertEquals(200, response.statusCode());
        assertArrayEquals(BODY, Files.readAllBytes(target));
    }

    @Test
    void aStalledAttemptIsTriedAgain() throws Exception {
        HttpResponse<byte[]> response = assertTimeoutPreemptively(
                Duration.ofSeconds(20),
                () -> IdleTimeoutDownload.send(
                        client, request("/stallsOnce"), HttpResponse.BodyHandlers.ofByteArray(), IDLE, 3));
        assertEquals(200, response.statusCode());
        assertArrayEquals(BODY, response.body());
        assertEquals(2, requests.get());
    }

    @Test
    void aFileWrittenAgainAfterAStallHasOnlyTheLastAttemptsBody() throws Exception {
        Path target = folder.resolve("retried.json");
        HttpResponse<Path> response = assertTimeoutPreemptively(
                Duration.ofSeconds(20),
                () -> IdleTimeoutDownload.send(
                        client, request("/stallsOnceThenShort"), IdleTimeoutDownload.fileBody(target), IDLE, 3));
        assertEquals(200, response.statusCode());
        assertArrayEquals(SHORT, Files.readAllBytes(target));
        assertEquals(2, requests.get());
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                .timeout(Duration.ofSeconds(10))
                .build();
    }

    private static void stall(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, 1_000_000);
        OutputStream out = exchange.getResponseBody();
        out.write(BODY);
        out.flush();
        pause(Duration.ofSeconds(30));
        exchange.close();
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
