package org.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Обход выданного сервера {@code Task_J3-server.jar}. Идёт около двух минут, поэтому включается
 * только явно:
 * <pre>
 *   ./gradlew test -PserverJar=Task_J3-server.jar [-PstudentId=IvanIvanov] --tests ServerJarTest
 * </pre>
 * Сервер всегда слушает порт 8080: если передать ему порт, он падает с ClassCastException.
 *
 * <p>По исходникам сервера (они лежат внутри jar) страниц ровно 1000 с номерами 0–999 плюс
 * корень, они разбиты на 10 слоёв, ответ задерживается не больше чем на 12 с. Значит, обход,
 * который ждёт ответы одновременно, обязан уложиться примерно в 120 с.
 */
@EnabledIfSystemProperty(named = "serverJar", matches = ".+")
class ServerJarTest {

    private static final URI SERVER = URI.create("http://localhost:8080");

    @Test
    void crawlsTaskServer() throws Exception {
        Path jar = Path.of(System.getProperty("serverJar"));
        assertTrue(Files.isRegularFile(jar), "нет файла " + jar);
        try (ServerSocket probe = new ServerSocket(8080)) {
            // Порт свободен.
        } catch (IOException e) {
            fail("порт 8080 занят: остановите уже запущенный сервер — тест запускает свой");
        }
        String java = ProcessHandle.current().info().command().orElse("java");
        String studentId = System.getProperty("studentId", "IvanIvanov");
        Process server = new ProcessBuilder(java, "-jar", jar.toString(), studentId).inheritIO().start();
        try {
            waitUntilUp(server);

            Spider.Result result = new Spider(new HttpPageSource(SERVER, Duration.ofSeconds(30), 2)).crawl("/");

            System.out.printf("student_id=%s: сообщений %d, ошибок %d, %.1f с, максимум одновременных запросов %d%n",
                    studentId, result.messages().size(), result.failures().size(),
                    result.elapsed().toMillis() / 1000.0, result.maxParallel());
            assertEquals(0, result.failures().size(), "ошибки: " + result.failures());
            Set<String> expectedPaths = new TreeSet<>(Set.of("/"));
            for (int i = 0; i < 1000; i++) {
                expectedPaths.add("/" + i);
            }
            assertEquals(expectedPaths, result.byPath().keySet());
            List<String> sorted = new ArrayList<>(result.messages());
            sorted.sort(null);
            assertEquals(sorted, result.messages());
            assertEquals(1001, result.messages().size());
            assertTrue(result.elapsed().compareTo(Duration.ofSeconds(130)) < 0,
                    "обход дольше 120 с — запросы шли не одновременно: " + result.elapsed());
        } finally {
            server.destroy();
            if (!server.waitFor(10, TimeUnit.SECONDS)) {
                server.destroyForcibly();
            }
        }
    }

    /** Ждёт, пока сервер начнёт отвечать на {@code GET /} (корень отвечает без задержки). */
    private static void waitUntilUp(Process server) throws InterruptedException {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest request = HttpRequest.newBuilder(SERVER.resolve("/")).timeout(Duration.ofSeconds(5)).build();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (!server.isAlive()) {
                fail("сервер завершился с кодом " + server.exitValue());
            }
            try {
                if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                // Ещё не запустился.
            }
            Thread.sleep(500);
        }
        fail("сервер не ответил за 60 с");
    }
}
