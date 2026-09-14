package org.example.client;

import org.example.common.KeyResult;
import org.example.common.Protocol;

import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Клиент микросервиса: подключается к серверу, передаёт имя и получает
 * пару ключей и сертификат.
 */
@Slf4j
public final class KeyClient {

    private final String host;
    private final int port;
    private final Duration delay;
    private final boolean abort;
    private final int timeoutMillis;

    /**
     * @param host          адрес или DNS-имя сервера
     * @param port          порт сервера
     * @param delay         задержка между отправкой запроса и чтением ответа («медленный» клиент)
     * @param abort         завершиться, не читая ответ (имитация аварийного завершения клиента)
     * @param timeoutMillis таймаут чтения ответа
     */
    public KeyClient(String host, int port, Duration delay, boolean abort, int timeoutMillis) {
        this.host = host;
        this.port = port;
        this.delay = delay == null ? Duration.ZERO : delay;
        this.abort = abort;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Запрашивает ключи для имени.
     *
     * @return пара ключей и сертификат либо {@code null}, если клиент завершается,
     *         не читая ответ (режим {@code --abort})
     */
    public KeyResult request(String name) throws IOException, InterruptedException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(timeoutMillis);

            OutputStream out = socket.getOutputStream();
            out.write(Protocol.encodeRequest(name));
            out.flush();
            log.debug("Запрос \"{}\" отправлен на {}:{}", name, host, port);

            if (abort) {
                // Аварийное завершение: обрываем соединение, не читая ответ.
                socket.setSoLinger(true, 0);
                log.info("Клиент \"{}\": аварийное завершение без чтения ответа", name);
                return null;
            }
            if (!delay.isZero() && !delay.isNegative()) {
                log.info("Клиент \"{}\": пауза {} с перед чтением ответа",
                        name, String.format("%.1f", delay.toMillis() / 1000.0));
                Thread.sleep(delay.toMillis());
            }
            return readResponse(new DataInputStream(socket.getInputStream()), name);
        }
    }

    private KeyResult readResponse(DataInputStream in, String name) throws IOException {
        int status = in.read();
        if (status < 0) {
            throw new IOException("Сервер закрыл соединение, не отправив ответ на запрос \"" + name + "\"");
        }
        if (status == Protocol.STATUS_ERROR) {
            throw new IOException("Сервер вернул ошибку: " + new String(readField(in), StandardCharsets.UTF_8));
        }
        if (status != Protocol.STATUS_OK) {
            throw new IOException("Неизвестный код ответа: " + status);
        }
        byte[] privateKeyPem = readField(in);
        byte[] certPem = readField(in);
        return new KeyResult(privateKeyPem, certPem);
    }

    private static byte[] readField(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > Protocol.MAX_FIELD_LENGTH) {
            throw new IOException("Некорректная длина поля ответа: " + length);
        }
        return in.readNBytes(length);
    }

    /**
     * Сохраняет полученные ключ и сертификат в файлы {@code <имя>.key} и {@code <имя>.crt}.
     *
     * @return пути записанных файлов: [0] — ключ, [1] — сертификат
     */
    public static Path[] save(KeyResult result, Path directory, String baseName) throws IOException {
        Files.createDirectories(directory);
        Path keyFile = directory.resolve(sanitize(baseName) + ".key");
        Path certFile = directory.resolve(sanitize(baseName) + ".crt");
        Files.write(keyFile, result.privateKeyPem());
        Files.write(certFile, result.certPem());
        return new Path[]{keyFile, certFile};
    }

    /** Приводит имя к виду, пригодному для имени файла. */
    public static String sanitize(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() ? "client" : safe;
    }
}
