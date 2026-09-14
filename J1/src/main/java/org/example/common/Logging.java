package org.example.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Управление уровнем журналирования из командной строки (ключ {@code --verbose}).
 *
 * <p>Формат сообщений и приёмники задаются в {@code src/main/resources/logback.xml};
 * здесь только переключается уровень корневого логгера.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Logging {

    /** {@code true} — выводить отладочные сообщения (уровень DEBUG), иначе INFO. */
    public static void setVerbose(boolean verbose) {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext context) {
            context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(verbose ? Level.DEBUG : Level.INFO);
        }
    }
}
