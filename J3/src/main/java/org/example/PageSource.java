package org.example;

import java.io.IOException;

/**
 * Откуда «паук» берёт страницы. В программе это HTTP-сервер ({@link HttpPageSource}),
 * в тестах — подставной источник с заданным графом и задержками.
 */
@FunctionalInterface
public interface PageSource {

    /**
     * Запрашивает страницу по пути URL. Метод блокирующий: вызывается из виртуальной нити,
     * поэтому ожидание ответа не занимает нить ОС.
     *
     * @param path путь URL, всегда начинается с {@code /}
     * @throws IOException сервер не ответил, ответил ошибкой или прислал не тот JSON
     */
    Page fetch(String path) throws IOException, InterruptedException;
}
