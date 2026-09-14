package org.example.common;

/**
 * Результат работы сервиса для одного имени: приватный ключ и сертификат в формате PEM.
 * Готовые байты хранятся как есть — их же сервер отдаёт клиенту, а клиент пишет в файлы
 * {@code .key} и {@code .crt}.
 *
 * @param privateKeyPem приватный ключ RSA, PEM (PKCS#8)
 * @param certPem       сертификат X509, PEM
 */
public record KeyResult(byte[] privateKeyPem, byte[] certPem) {
}
