package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP-сервер для тестов, устроенный как сервер задачи: на GET по пути отвечает JSON
 * {@code {"message": …, "successors": […]}} с заданной задержкой, на неизвестный путь — 404.
 * Каждый запрос обрабатывается в своей виртуальной нити, поэтому задержки разных запросов
 * идут одновременно. Считает, сколько раз запрошен каждый путь.
 */
final class TestServer implements AutoCloseable {

    /** Один ответ: код, тело, задержка перед ответом. */
    record Reply(int status, String body, long delayMs) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /** Очередь ответов для пути; последний ответ повторяется для всех следующих запросов. */
    private final Map<String, Deque<Reply>> replies = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    TestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 2000);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    URI uri() {
        return URI.create("http://" + server.getAddress().getAddress().getHostAddress()
                + ":" + server.getAddress().getPort());
    }

    /** Страница {@code path} отвечает через {@code delayMs} мс. */
    TestServer page(String path, long delayMs, String message, String... successors) {
        return replies(path, new Reply(200, json(message, List.of(successors)), delayMs));
    }

    /** Ответы на первый, второй, … запрос пути; последний повторяется. */
    TestServer replies(String path, Reply... sequence) {
        replies.put(path, new ArrayDeque<>(List.of(sequence)));
        return this;
    }

    static String json(String message, List<String> successors) {
        try {
            return JSON.writeValueAsString(Map.of("message", message, "successors", successors));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    int hits(String path) {
        AtomicInteger count = hits.get(path);
        return count == null ? 0 : count.get();
    }

    Map<String, AtomicInteger> allHits() {
        return hits;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            Reply reply = next(path);
            if (reply.delayMs() > 0) {
                Thread.sleep(reply.delayMs());
            }
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // Клиент не дождался ответа и закрыл соединение — для тестов таймаута это нормально.
        }
    }

    private Reply next(String path) {
        Deque<Reply> queue = replies.get(path);
        if (queue == null) {
            return new Reply(404, "Not found", 0);
        }
        synchronized (queue) {
            return queue.size() > 1 ? queue.poll() : queue.peek();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
