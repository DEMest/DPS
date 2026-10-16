package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Страницы с HTTP-сервера: {@code GET <base><path>}, ответ 200 с JSON {@link Page}.
 *
 * <p>Один {@link HttpClient} на всю программу: он потокобезопасен и держит пул соединений
 * keep-alive, поэтому сотни виртуальных нитей могут вызывать {@link #fetch} одновременно.
 * Метод {@link HttpClient#send} блокирующий, но в виртуальной нити блокировка лишь снимает
 * её с нити-носителя, а нить ОС тем временем выполняет другие виртуальные нити.
 *
 * <p>Обрыв соединения, таймаут и ответ 5xx повторяются до {@code retries} раз; ответ 4xx —
 * ошибка сразу (такого пути на сервере нет, повтор не поможет).
 */
public class HttpPageSource implements PageSource {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration RETRY_PAUSE = Duration.ofMillis(200);

    private final HttpClient client;
    private final String base;
    private final Duration timeout;
    private final int retries;

    /**
     * @param base    адрес сервера, например {@code http://localhost:8080}
     * @param timeout сколько ждать ответа на один запрос
     * @param retries сколько раз повторять неудачный запрос
     */
    public HttpPageSource(URI base, Duration timeout, int retries) {
        String text = base.toString();
        this.base = text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
        this.timeout = timeout;
        this.retries = retries;
        this.client = HttpClient.newBuilder()
                // Без попытки перейти на HTTP/2: сервер отвечает по HTTP/1.1.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public Page fetch(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        for (int attempt = 0; ; attempt++) {
            HttpResponse<byte[]> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                // Не удалось соединиться, соединение оборвалось или истёк таймаут.
                if (attempt >= retries && e instanceof ConnectException) {
                    // У самого ConnectException от HttpClient сообщения нет.
                    ConnectException refused = new ConnectException("нет соединения с " + base + " — сервер запущен?");
                    refused.initCause(e);
                    throw refused;
                }
                if (attempt >= retries) {
                    throw e;
                }
                pauseBeforeRetry(path, attempt, describe(e));
                continue;
            }
            int status = response.statusCode();
            if (status == 200) {
                return parse(response.body());
            }
            String problem = "сервер ответил кодом " + status;
            // 4xx: такого пути на сервере нет, повтор не поможет.
            if (status < 500 || attempt >= retries) {
                throw new IOException(problem);
            }
            pauseBeforeRetry(path, attempt, problem);
        }
    }

    private void pauseBeforeRetry(String path, int attempt, String problem) throws InterruptedException {
        Log.debug("GET %s — %s, повтор %d из %d", path, problem, attempt + 1, retries);
        Thread.sleep(RETRY_PAUSE.multipliedBy(attempt + 1));
    }

    private static Page parse(byte[] body) throws IOException {
        Page page = JSON.readValue(body, Page.class);
        if (page == null || page.message() == null) {
            throw new IOException("в ответе нет поля message");
        }
        return page;
    }

    /** Текст исключения для журнала: у некоторых исключений HttpClient сообщения нет. */
    static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
