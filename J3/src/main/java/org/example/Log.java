package org.example;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Журнал в stderr: время, имя нити, текст. В stdout пишутся только собранные сообщения, поэтому
 * их можно перенаправить в файл, не смешивая с журналом.
 * <pre>
 *   12:00:05.814 [spider-17] GET /425 — 200 за 5,81 с, переходов: 1
 * </pre>
 */
final class Log {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** Ключ {@code --verbose}: выводить каждый запрос. */
    static volatile boolean verbose;

    private Log() {
    }

    static void info(String format, Object... args) {
        String line = String.format("%s [%s] %s", LocalTime.now().format(TIME),
                Thread.currentThread().getName(), String.format(format, args));
        System.err.println(line);
    }

    static void debug(String format, Object... args) {
        if (verbose) {
            info(format, args);
        }
    }
}
