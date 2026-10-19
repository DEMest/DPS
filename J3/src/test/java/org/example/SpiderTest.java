package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpiderTest {

    record Response(long delayMs, String body) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Response> pages = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1000);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            Response response = pages.get(path);
            try {
                if (response == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                Thread.sleep(response.delayMs());
                byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void page(String path, long delayMs, String message, String... successors) throws IOException {
        String body = json.writeValueAsString(new Spider.Page(message, List.of(successors)));
        pages.put(path, new Response(delayMs, body));
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void collectsAllMessagesSorted() throws Exception {
        page("/", 0, "root", "a", "/b");
        page("/a", 30, "alpha", "c");
        page("/b", 10, "Beta", "/c", "b");
        page("/c", 20, "", "/", "a");

        Spider spider = new Spider(url());
        List<String> messages = spider.crawl();

        assertEquals(List.of("", "Beta", "alpha", "root"), messages);
        assertEquals(Set.of("/", "/a", "/b", "/c"), hits.keySet());
        hits.values().forEach(count -> assertEquals(1, count.get()));
        assertTrue(spider.getFailed().isEmpty());
    }

    @Test
    void requestsRunInParallel() throws Exception {
        List<String> children = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            children.add("p" + i);
            page("/p" + i, 1000, "p" + i, "q" + i);
            page("/q" + i, 1000, "q" + i);
        }
        page("/", 0, "root", children.toArray(String[]::new));

        long start = System.nanoTime();
        List<String> messages = new Spider(url()).crawl();
        double seconds = (System.nanoTime() - start) / 1e9;

        assertEquals(401, messages.size());
        assertTrue(seconds < 5, "обход занял " + seconds + " с");
    }

    @Test
    void brokenLinkDoesNotStopCrawl() throws Exception {
        page("/", 0, "root", "missing", "ok");
        page("/ok", 0, "ok", "deep");
        page("/deep", 0, "deep");

        Spider spider = new Spider(url());
        List<String> messages = spider.crawl();

        assertEquals(List.of("deep", "ok", "root"), messages);
        assertEquals(Set.of("/missing"), spider.getFailed());
    }
}
