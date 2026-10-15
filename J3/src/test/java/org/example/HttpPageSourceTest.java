package org.example;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Запросы к настоящему HTTP-серверу ({@link TestServer}): разбор ответа, ошибки, повторы. */
class HttpPageSourceTest {

    private TestServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new TestServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private HttpPageSource source(Duration timeout, int retries) {
        return new HttpPageSource(server.uri(), timeout, retries);
    }

    @Test
    void parsesMessageAndSuccessors() throws Exception {
        server.page("/", 0, "vEvMgjql", "425", "225")
                .replies("/425", new TestServer.Reply(200, "{\"message\":\"m\",\"extra\":1}", 0));

        assertEquals(new Page("vEvMgjql", List.of("425", "225")), source(Duration.ofSeconds(5), 0).fetch("/"));
        // Лишние поля пропускаются, без successors — пустой список.
        assertEquals(new Page("m", List.of()), source(Duration.ofSeconds(5), 0).fetch("/425"));
    }

    @Test
    void notFoundIsNotRetried() {
        IOException e = assertThrows(IOException.class, () -> source(Duration.ofSeconds(5), 3).fetch("/nope"));

        assertEquals("сервер ответил кодом 404", e.getMessage());
        assertEquals(1, server.hits("/nope"));
    }

    @Test
    void serverErrorIsRetried() throws Exception {
        server.replies("/flaky",
                new TestServer.Reply(503, "busy", 0),
                new TestServer.Reply(200, TestServer.json("ok", List.of()), 0));

        assertEquals("ok", source(Duration.ofSeconds(5), 2).fetch("/flaky").message());
        assertEquals(2, server.hits("/flaky"));
    }

    @Test
    void givesUpAfterRetries() {
        server.replies("/down", new TestServer.Reply(500, "error", 0));

        IOException e = assertThrows(IOException.class, () -> source(Duration.ofSeconds(5), 2).fetch("/down"));

        assertEquals("сервер ответил кодом 500", e.getMessage());
        assertEquals(3, server.hits("/down"));
    }

    @Test
    void slowAnswerHitsTimeout() {
        server.page("/slow", 3000, "too late");
        long began = System.nanoTime();

        assertThrows(HttpTimeoutException.class, () -> source(Duration.ofMillis(300), 1).fetch("/slow"));

        assertEquals(2, server.hits("/slow"));
        assertTrue(System.nanoTime() - began < Duration.ofMillis(2500).toNanos(), "таймаут не сработал");
    }

    @Test
    void waitsForSlowAnswerWithinTimeout() throws Exception {
        server.page("/slow", 1500, "in time");

        assertEquals("in time", source(Duration.ofSeconds(5), 0).fetch("/slow").message());
    }

    @Test
    void rejectsAnswerWithoutMessage() {
        server.replies("/empty", new TestServer.Reply(200, "{\"successors\":[]}", 0));
        server.replies("/garbage", new TestServer.Reply(200, "<html>", 0));

        IOException e = assertThrows(IOException.class, () -> source(Duration.ofSeconds(5), 0).fetch("/empty"));
        assertEquals("в ответе нет поля message", e.getMessage());
        assertThrows(IOException.class, () -> source(Duration.ofSeconds(5), 0).fetch("/garbage"));
    }

    @Test
    void connectionRefusedIsAnIOException() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        HttpPageSource source = new HttpPageSource(URI.create("http://127.0.0.1:" + closedPort), Duration.ofSeconds(5), 1);

        IOException e = assertThrows(ConnectException.class, () -> source.fetch("/"));
        assertEquals("нет соединения с http://127.0.0.1:" + closedPort + " — сервер запущен?", e.getMessage());
    }
}
