package org.example.common;


import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Формат обмена между клиентом и сервером.
 *
 * <p>Запрос: имя клиента (ASCII), завершённое нулевым байтом.
 *
 * <p>Ответ:
 * <pre>
 *   byte  status                 0 - успех, 1 - ошибка
 *   успех:  int32 keyLen, keyLen байт PEM приватного ключа,
 *           int32 certLen, certLen байт PEM сертификата
 *   ошибка: int32 msgLen, msgLen байт текста ошибки (UTF-8)
 * </pre>
 * Числа передаются в сетевом порядке байт (big-endian). После отправки ответа
 * сервер закрывает соединение.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Protocol {

    /** Порт по умолчанию. */
    public static final int DEFAULT_PORT = 9000;

    /** Разделитель имени в запросе. */
    public static final byte NAME_TERMINATOR = 0;

    /** Максимальная длина имени клиента в байтах. */
    public static final int MAX_NAME_LENGTH = 255;

    /** Максимальный размер поля ответа (защита клиента от некорректных данных). */
    public static final int MAX_FIELD_LENGTH = 1 << 20;

    public static final byte STATUS_OK = 0;
    public static final byte STATUS_ERROR = 1;

    /** Кодирует запрос: имя + нулевой байт. */
    public static byte[] encodeRequest(String name) {
        byte[] raw = name.getBytes(StandardCharsets.US_ASCII);
        if (raw.length == 0) {
            throw new IllegalArgumentException("Имя не может быть пустым");
        }
        if (raw.length > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Имя длиннее " + MAX_NAME_LENGTH + " байт");
        }
        byte[] request = new byte[raw.length + 1];
        System.arraycopy(raw, 0, request, 0, raw.length);
        request[raw.length] = NAME_TERMINATOR;
        return request;
    }

    /** Кодирует успешный ответ с ключом и сертификатом. */
    public static ByteBuffer encodeOk(KeyResult result) {
        byte[] key = result.privateKeyPem();
        byte[] cert = result.certPem();
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + key.length + 4 + cert.length);
        buffer.put(STATUS_OK);
        buffer.putInt(key.length).put(key);
        buffer.putInt(cert.length).put(cert);
        buffer.flip();
        return buffer;
    }

    /** Кодирует ответ об ошибке. */
    public static ByteBuffer encodeError(String message) {
        byte[] text = message.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + text.length);
        buffer.put(STATUS_ERROR);
        buffer.putInt(text.length).put(text);
        buffer.flip();
        return buffer;
    }
}
