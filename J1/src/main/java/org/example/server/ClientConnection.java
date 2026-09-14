package org.example.server;

import org.example.common.Protocol;

import lombok.extern.slf4j.Slf4j;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;

/**
 * Состояние одного клиентского соединения.
 *
 * <p>Все методы, кроме {@link #publish(ByteBuffer)}, выполняются нитью ввода-вывода,
 * которой принадлежит соединение. Генерирующая нить только записывает готовый ответ
 * и ставит соединение в очередь этой нити — блокировок на передаче результата нет.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class ClientConnection {

    private enum State {
        /** Читаем имя до нулевого байта. */
        READING_NAME,
        /** Имя получено, ключ генерируется (или уже готов). */
        WAITING_FOR_KEY,
        /** Отдаём ответ клиенту. */
        WRITING,
        CLOSED
    }

    private final SocketChannel channel;
    private final IoWorker worker;
    private final KeyRegistry registry;
    private final SocketAddress remote;
    private final ByteBuffer readBuffer = ByteBuffer.allocate(512);
    private final ByteArrayOutputStream nameBuffer = new ByteArrayOutputStream(64);

    private SelectionKey key;
    private State state = State.READING_NAME;
    private String name;
    private volatile ByteBuffer response;

    void attach(SelectionKey key) {
        this.key = key;
    }

    /** Канал готов к чтению. */
    void onReadable() throws IOException {
        readBuffer.clear();
        int read = channel.read(readBuffer);
        if (read < 0) {
            // Клиент закрыл соединение, не дождавшись ответа (аварийное завершение).
            close("соединение закрыто клиентом");
            return;
        }
        if (read == 0 || state != State.READING_NAME) {
            // В остальных состояниях лишние данные игнорируем, но продолжаем следить за обрывом.
            return;
        }
        readBuffer.flip();
        while (readBuffer.hasRemaining()) {
            byte b = readBuffer.get();
            if (b == Protocol.NAME_TERMINATOR) {
                onNameReceived();
                return;
            }
            if (nameBuffer.size() >= Protocol.MAX_NAME_LENGTH) {
                state = State.WAITING_FOR_KEY;
                publish(Protocol.encodeError("Имя длиннее " + Protocol.MAX_NAME_LENGTH + " байт"));
                return;
            }
            nameBuffer.write(b);
        }
    }

    private void onNameReceived() {
        name = nameBuffer.toString(StandardCharsets.US_ASCII).trim();
        state = State.WAITING_FOR_KEY;
        if (name.isEmpty()) {
            publish(Protocol.encodeError("Пустое имя"));
            return;
        }
        log.debug("Запрос имени \"{}\" от {}", name, remote);
        registry.request(name).whenComplete((result, error) -> {
            if (error == null) {
                publish(Protocol.encodeOk(result));
            } else {
                publish(Protocol.encodeError(describe(error)));
            }
        });
    }

    /**
     * Публикует готовый ответ. Вызывается генерирующей нитью (или нитью ввода-вывода,
     * если ключ уже был в памяти): ответ кладётся в очередь нити ввода-вывода.
     */
    private void publish(ByteBuffer buffer) {
        this.response = buffer;
        worker.resultReady(this);
    }

    /** Ответ готов; вызывается нитью ввода-вывода после разбора очереди результатов. */
    void onResultReady() {
        if (state == State.CLOSED || response == null || state == State.WRITING) {
            return;
        }
        state = State.WRITING;
        if (!key.isValid()) {
            close("соединение уже закрыто");
            return;
        }
        key.interestOps(SelectionKey.OP_WRITE);
        try {
            onWritable();
        } catch (IOException e) {
            close("не удалось отправить ответ: " + e.getMessage());
        }
    }

    /** Канал готов к записи. */
    void onWritable() throws IOException {
        if (state != State.WRITING) {
            return;
        }
        channel.write(response);
        if (!response.hasRemaining()) {
            try {
                channel.shutdownOutput();
            } catch (IOException ignored) {
                // клиент мог уже уйти
            }
            close("ответ отправлен" + (name == null ? "" : " (" + name + ")"));
        }
    }

    void close(String reason) {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        if (key != null) {
            key.cancel();
        }
        try {
            channel.close();
        } catch (IOException e) {
            log.debug("Ошибка при закрытии {}: {}", remote, e.getMessage());
        }
        log.debug("Соединение {} закрыто: {}", remote, reason);
        worker.connectionClosed();
    }

    private static String describe(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }
}
