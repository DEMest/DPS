package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Обход целиком, по HTTP: программа против уменьшенной копии сервера задачи. Как и там, страницы
 * разбиты на слои, корень ссылается на первый слой, каждая страница — на страницы следующего,
 * в каждом слое одна страница отвечает за максимальное время, остальные — быстрее.
 */
class CrawlTest {

    private static final int LAYERS = 6;
    private static final int WIDTH = 50;
    private static final long MAX_DELAY_MS = 500;

    /** Модель сервера задачи; {@code expected} заполняется ожидаемыми сообщениями. */
    private static TestServer modelServer(Map<String, String> expected, long seed) throws Exception {
        Random random = new Random(seed);
        TestServer server = new TestServer();
        List<List<String>> layers = new ArrayList<>();
        int next = 0;
        for (int layer = 0; layer < LAYERS; layer++) {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < WIDTH; i++) {
                ids.add(String.valueOf(next++));
            }
            layers.add(ids);
        }
        String rootMessage = message(random);
        expected.put("/", rootMessage);
        server.page("/", 0, rootMessage, layers.get(0).toArray(String[]::new));
        for (int layer = 0; layer < LAYERS; layer++) {
            for (int i = 0; i < WIDTH; i++) {
                List<String> successors = new ArrayList<>();
                if (layer + 1 < LAYERS) {
                    List<String> below = layers.get(layer + 1);
                    successors.add(below.get(i));
                    // Лишние ссылки на чужие страницы: на одну страницу ведёт несколько путей.
                    successors.add(below.get(random.nextInt(WIDTH)));
                }
                long delay = i == 0 ? MAX_DELAY_MS : random.nextInt((int) MAX_DELAY_MS);
                String path = "/" + layers.get(layer).get(i);
                String message = message(random);
                expected.put(path, message);
                server.page(path, delay, message, successors.toArray(String[]::new));
            }
        }
        return server;
    }

    private static String message(Random random) {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder text = new StringBuilder();
        int length = random.nextInt(64);
        for (int i = 0; i < length; i++) {
            text.append(chars.charAt(random.nextInt(chars.length())));
        }
        return text.toString();
    }

    @Test
    void collectsWholeServerInTimeOfLongestChain() throws Exception {
        Map<String, String> expected = new HashMap<>();
        try (TestServer server = modelServer(expected, 42)) {
            Spider.Result result = new Spider(new HttpPageSource(server.uri(), Duration.ofSeconds(10), 2)).crawl("/");

            List<String> sorted = new ArrayList<>(expected.values());
            sorted.sort(null);
            assertEquals(sorted, result.messages());
            assertEquals(expected, result.byPath());
            assertEquals(Map.of(), result.failures());
            server.allHits().forEach((path, count) -> assertEquals(1, count.get(), "запросов " + path));
            assertEquals(LAYERS * WIDTH + 1, server.allHits().size());
            // Самая длинная цепочка — LAYERS страниц по 0,5 с = 3 с; последовательно было бы ~75 с.
            assertTrue(result.elapsed().compareTo(Duration.ofSeconds(6)) < 0, "обход шёл " + result.elapsed());
            assertTrue(result.maxParallel() >= WIDTH, "одновременно ждали ответа: " + result.maxParallel());
        }
    }

    @Test
    void mainWritesSortedMessagesToFile(@TempDir Path dir) throws Exception {
        Map<String, String> expected = new HashMap<>();
        try (TestServer server = modelServer(expected, 7)) {
            Path out = dir.resolve("messages.txt");

            int code = Main.run(new String[]{"--url", server.uri().toString(), "--out", out.toString(), "--progress", "1"});

            assertEquals(0, code);
            List<String> sorted = new ArrayList<>(expected.values());
            sorted.sort(null);
            assertEquals(sorted, Files.readAllLines(out, StandardCharsets.UTF_8));
        }
    }

    @Test
    void mainReportsFailedUrls(@TempDir Path dir) throws Exception {
        try (TestServer server = new TestServer()) {
            server.page("/", 0, "root", "1", "404");
            server.page("/1", 0, "one");

            int code = Main.run(new String[]{"--url", server.uri().toString(), "--out", dir.resolve("m.txt").toString(),
                    "--retries", "0", "--progress", "0"});

            assertEquals(1, code);
            assertEquals(List.of("one", "root"), Files.readAllLines(dir.resolve("m.txt")));
        }
    }

    @Test
    void jsonOutputShowsEmptyMessages() throws Exception {
        String json = Main.format(List.of("", "A", "a"), true);

        assertEquals(String.join(System.lineSeparator(), "[", "  \"\",", "  \"A\",", "  \"a\"", "]", ""), json);
        assertEquals(List.of("", "A", "a"),
                List.of(new ObjectMapper().readValue(json, String[].class)));
        assertEquals(String.join(System.lineSeparator(), "", "A", "a", ""), Main.format(List.of("", "A", "a"), false));
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> Main.run(new String[]{"--depth", "3"}));
        assertThrows(IllegalArgumentException.class, () -> Main.run(new String[]{"--port"}));
        assertThrows(IllegalArgumentException.class, () -> Main.run(new String[]{"--port", "0"}));
        assertThrows(IllegalArgumentException.class, () -> Main.run(new String[]{"--port", "70000"}));
        assertThrows(IllegalArgumentException.class, () -> Main.run(new String[]{"--url", "localhost:8080"}));
        assertThrows(IllegalArgumentException.class,
                () -> Main.run(new String[]{"--url", "http://localhost:8080", "--port", "8080"}));
    }
}
