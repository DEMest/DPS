package org.example.client;

import org.example.common.Args;
import org.example.common.KeyResult;
import org.example.common.Logging;
import org.example.common.Protocol;

import lombok.extern.slf4j.Slf4j;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Точка входа клиента: получает имя, адрес (или DNS-имя) сервера и порт,
 * сохраняет полученную пару ключей в файлы .key и .crt.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ClientMain {

    private static final Set<String> FLAGS = Set.of("help", "verbose", "abort", "unique-names");

    public static void main(String[] argv) throws Exception {
        Args args;
        try {
            args = Args.parse(argv, FLAGS);
        } catch (IllegalArgumentException e) {
            log.error("{}", e.getMessage());
            usage();
            System.exit(2);
            return;
        }
        if (args.getBoolean("help", false)) {
            usage();
            return;
        }
        Logging.setVerbose(args.getBoolean("verbose", false));

        // Позиционные аргументы: <имя> [<хост>] [<порт>] — как альтернатива опциям.
        List<String> positional = args.positional();
        String name = args.get("name", positional.size() > 0 ? positional.get(0) : null);
        String host = args.get("host", positional.size() > 1 ? positional.get(1) : "localhost");
        int port = args.getInt("port", positional.size() > 2
                ? Integer.parseInt(positional.get(2))
                : Protocol.DEFAULT_PORT);

        if (name == null || name.isBlank()) {
            log.error("Не задано имя клиента (--name или первый позиционный аргумент)");
            usage();
            System.exit(2);
            return;
        }

        Duration delay = Duration.ofMillis(Math.round(args.getDouble("delay", 0) * 1000));
        boolean abort = args.getBoolean("abort", false);
        int timeout = args.getInt("timeout", 600_000);
        Path outDir = Path.of(args.get("out-dir", "."));
        int clients = args.getInt("clients", 1);
        boolean uniqueNames = args.getBoolean("unique-names", false);

        if (clients <= 1) {
            runOne(new KeyClient(host, port, delay, abort, timeout), name, outDir, null);
            return;
        }
        runMany(host, port, delay, abort, timeout, name, outDir, clients, uniqueNames);
    }

    private static void runOne(KeyClient client, String name, Path outDir, String fileSuffix) throws Exception {
        long started = System.nanoTime();
        KeyResult result = client.request(name);
        if (result == null) {
            return; // режим --abort: ответ не читаем
        }
        String baseName = KeyClient.sanitize(name) + (fileSuffix == null ? "" : fileSuffix);
        Path[] files = KeyClient.save(result, outDir, baseName);
        log.info("Клиент \"{}\": ключ получен за {} с, сохранено в {} и {}",
                name, String.format("%.1f", (System.nanoTime() - started) / 1e9),
                files[0].toAbsolutePath(), files[1].toAbsolutePath());
    }

    /** Нагрузочный режим: несколько одновременных клиентов в одном процессе. */
    private static void runMany(String host, int port, Duration delay, boolean abort, int timeout,
                                String name, Path outDir, int clients, boolean uniqueNames) throws Exception {
        log.info("Запуск {} одновременных клиентов (имена {})",
                clients, uniqueNames ? "уникальные" : "одинаковые");
        CountDownLatch done = new CountDownLatch(clients);
        AtomicInteger failures = new AtomicInteger();
        long started = System.nanoTime();

        for (int i = 0; i < clients; i++) {
            int index = i;
            Thread.ofVirtual().name("client-" + i).start(() -> {
                String clientName = uniqueNames ? name + "-" + index : name;
                try {
                    // При одинаковых именах файлы разводим по номеру клиента.
                    runOne(new KeyClient(host, port, delay, abort, timeout), clientName, outDir,
                            uniqueNames ? null : "-" + index);
                } catch (Exception e) {
                    failures.incrementAndGet();
                    log.warn("Клиент {} (\"{}\") завершился с ошибкой", index, clientName, e);
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        log.info("Готово за {} с, ошибок: {}",
                String.format("%.1f", (System.nanoTime() - started) / 1e9), failures.get());
        if (failures.get() > 0) {
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("""
                Клиент микросервиса генерации ключей.

                Использование: client --name <имя> [--host <адрес>] [--port <порт>] [опции]
                          либо: client <имя> [<адрес>] [<порт>] [опции]

                  --name <имя>       имя, передаваемое серверу (Subject Name сертификата)
                  --host <адрес>     адрес или DNS-имя сервера (по умолчанию localhost)
                  --port <порт>      порт сервера (по умолчанию 9000)
                  --out-dir <путь>   каталог для файлов .key и .crt (по умолчанию текущий)
                  --delay <сек>      пауза между отправкой запроса и чтением ответа
                                     (имитация медленного клиента, допустимы дробные значения)
                  --abort            завершиться, не читая ответ
                                     (имитация аварийного завершения клиента)
                  --clients <n>      запустить n одновременных клиентов (нагрузочный режим)
                  --unique-names     в нагрузочном режиме давать каждому клиенту своё имя
                  --timeout <мс>     таймаут соединения и чтения (по умолчанию 600000)
                  --verbose          подробный журнал
                  --help             эта справка
                """);
    }
}
