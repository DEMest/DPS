package org.example;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Запуск «паука»: обходит HTTP-сервер задачи и печатает собранные сообщения,
 * отсортированные в лексикографическом порядке.
 *
 * <pre>
 *   java -jar J3.jar [--host localhost] [--port 8080] [--url http://…] [--out файл] [--json] …
 * </pre>
 *
 * Сообщения пишутся в stdout по одному на строку (или в файл {@code --out}), ход работы и
 * итог — в stderr.
 */
public class Main {

    private static final Set<String> VALUE_OPTIONS =
            Set.of("host", "port", "url", "start", "timeout", "retries", "progress", "out");
    private static final Set<String> FLAGS = Set.of("help", "json", "verbose");

    public static void main(String[] args) throws Exception {
        int code;
        try {
            code = run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Ошибка: " + e.getMessage());
            code = 2;
        }
        System.exit(code);
    }

    /** @return код завершения: 0 — всё собрано, 1 — часть URL получить не удалось */
    static int run(String[] args) throws Exception {
        Map<String, String> options = parseOptions(args);
        if (options.containsKey("help")) {
            System.out.println("""
                    Использование: java -jar build/libs/J3.jar [опции]
                      --host <адрес>     адрес сервера, по умолчанию localhost
                      --port <порт>      порт сервера, по умолчанию 8080
                      --url <адрес>      адрес целиком, например http://192.168.1.10:8080
                                         (вместо --host и --port)
                      --start <путь>     с какого пути начинать, по умолчанию /
                      --timeout <сек>    сколько ждать ответа на один запрос, по умолчанию 30
                      --retries <n>      сколько раз повторять неудачный запрос, по умолчанию 2
                      --progress <сек>   как часто печатать ход работы, по умолчанию 5; 0 — не печатать
                      --out <файл>       записать сообщения в файл (UTF-8), а не в stdout
                      --json             вывести сообщения JSON-массивом, а не по строке
                      --verbose          печатать каждый запрос""");
            return 0;
        }
        URI base = baseUri(options);
        String start = options.getOrDefault("start", "/");
        Duration timeout = Duration.ofSeconds(number(options, "timeout", 30, 1));
        int retries = (int) number(options, "retries", 2, 0);
        long progressSeconds = number(options, "progress", 5, 0);
        Log.verbose = options.containsKey("verbose");

        Spider spider = new Spider(new HttpPageSource(base, timeout, retries));
        Log.info("Обход %s начиная с %s; таймаут запроса %d с, повторов %d",
                base, Spider.toPath(start), timeout.toSeconds(), retries);
        Thread reporter = progressSeconds > 0 ? startReporter(spider, progressSeconds) : null;
        Spider.Result result = spider.crawl(start);
        if (reporter != null) {
            reporter.interrupt();
        }

        String text = format(result.messages(), options.containsKey("json"));
        if (options.containsKey("out")) {
            Path out = Path.of(options.get("out"));
            Files.writeString(out, text, StandardCharsets.UTF_8);
            Log.info("Сообщения записаны в %s", out.toAbsolutePath());
        } else {
            System.out.print(text);
            System.out.flush();
        }

        Log.info("Готово за %.1f с: обойдено URL — %d, сообщений — %d, ошибок — %d; "
                        + "максимум одновременных запросов — %d",
                result.elapsed().toMillis() / 1000.0, result.visited(), result.messages().size(),
                result.failures().size(), result.maxParallel());
        result.failures().forEach((path, reason) -> Log.info("Не удалось получить %s: %s", path, reason));
        return result.failures().isEmpty() ? 0 : 1;
    }

    /** Сообщения по одному на строку или JSON-массивом (в нём видны и пустые сообщения). */
    static String format(List<String> messages, boolean json) throws IOException {
        if (json) {
            // По элементу на строку: обычный «красивый» вывод Jackson пишет массив в одну строку.
            DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                    .withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
            return new ObjectMapper().writer(printer).writeValueAsString(messages) + System.lineSeparator();
        }
        StringBuilder text = new StringBuilder();
        for (String message : messages) {
            text.append(message).append(System.lineSeparator());
        }
        return text.toString();
    }

    /** Раз в {@code seconds} секунд печатает, сколько URL уже обойдено. */
    private static Thread startReporter(Spider spider, long seconds) {
        long began = System.nanoTime();
        return Thread.ofVirtual().name("progress").start(() -> {
            try {
                while (true) {
                    Thread.sleep(Duration.ofSeconds(seconds));
                    Log.info("%3d с: найдено URL — %d, получено ответов — %d, ждут ответа — %d, ошибок — %d",
                            (System.nanoTime() - began) / 1_000_000_000L, spider.discovered(),
                            spider.answered(), spider.inFlight(), spider.failed());
                }
            } catch (InterruptedException e) {
                // Обход закончен.
            }
        });
    }

    static URI baseUri(Map<String, String> options) {
        if (options.containsKey("url")) {
            if (options.containsKey("host") || options.containsKey("port")) {
                throw new IllegalArgumentException("--url задаёт адрес целиком, вместе с ним --host и --port не нужны");
            }
            try {
                URI uri = URI.create(options.get("url"));
                if (uri.getHost() != null && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
                    return uri;
                }
            } catch (IllegalArgumentException e) {
                // Сообщение ниже.
            }
            throw new IllegalArgumentException("--url: ожидается адрес вида http://localhost:8080, получено "
                    + options.get("url"));
        }
        String host = options.getOrDefault("host", "localhost");
        long port = number(options, "port", 8080, 1);
        if (port > 65535) {
            throw new IllegalArgumentException("--port должен быть от 1 до 65535, получено " + port);
        }
        return URI.create("http://" + host + ":" + port);
    }

    /** Разбирает аргументы вида {@code --имя значение} и флаги {@code --help}, {@code --json}, {@code --verbose}. */
    static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i].equals("-h") ? "--help" : args[i];
            String name = arg.startsWith("--") ? arg.substring(2) : null;
            if (name != null && FLAGS.contains(name)) {
                options.put(name, "");
                continue;
            }
            if (name == null || !VALUE_OPTIONS.contains(name)) {
                throw new IllegalArgumentException("неизвестный аргумент " + args[i] + " (справка: --help)");
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("у " + args[i] + " нет значения");
            }
            options.put(name, args[++i]);
        }
        return options;
    }

    /** Целое число из опций, не меньше {@code min}. */
    static long number(Map<String, String> options, String name, long defaultValue, long min) {
        String value = options.get(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed >= min) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // Сообщение ниже.
        }
        throw new IllegalArgumentException("--" + name + " должно быть целым числом не меньше " + min + ", получено " + value);
    }
}
