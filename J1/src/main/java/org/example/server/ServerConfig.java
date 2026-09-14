package org.example.server;

import org.example.common.Args;
import org.example.common.Protocol;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * Настройки микросервиса. Значения берутся из командной строки, при отсутствии —
 * из конфигурационного файла (--config), затем из значений по умолчанию.
 *
 * @param bindAddress   адрес прослушивания (по умолчанию 0.0.0.0 — доступ с других компьютеров)
 * @param port          TCP-порт
 * @param backlog       длина очереди входящих соединений
 * @param generatorThreads размер фиксированного пула генерирующих нитей
 * @param ioThreads     число нитей ввода-вывода (мультиплексирование через Selector)
 * @param keySize       длина генерируемого приватного ключа RSA в битах
 * @param caKeyFile     файл с ключом подписи УЦ
 * @param caCertFile    файл с сертификатом УЦ
 * @param issuerName    Issuer Name выдаваемых сертификатов (null — взять из сертификата УЦ)
 * @param validity      срок действия выдаваемых сертификатов
 */
public record ServerConfig(
        String bindAddress,
        int port,
        int backlog,
        int generatorThreads,
        int ioThreads,
        int keySize,
        Path caKeyFile,
        Path caCertFile,
        String issuerName,
        Duration validity) {

    public static final Set<String> FLAGS = Set.of("help", "verbose");

    public ServerConfig {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Недопустимый порт: " + port);
        }
        if (generatorThreads < 1) {
            throw new IllegalArgumentException("Число генерирующих нитей должно быть не меньше 1");
        }
        if (ioThreads < 1) {
            throw new IllegalArgumentException("Число нитей ввода-вывода должно быть не меньше 1");
        }
        if (keySize < 512) {
            throw new IllegalArgumentException("Слишком короткий ключ: " + keySize);
        }
    }

    /** Разбирает аргументы командной строки (и, при наличии, --config файл). */
    public static ServerConfig from(Args args) {
        return new ServerConfig(
                args.get("bind", "0.0.0.0"),
                args.getInt("port", Protocol.DEFAULT_PORT),
                args.getInt("backlog", 512),
                args.getInt("threads", Runtime.getRuntime().availableProcessors()),
                args.getInt("io-threads", 1),
                args.getInt("key-size", 8192),
                Path.of(args.require("ca-key", "файл с ключом подписи УЦ")),
                Path.of(args.require("ca-cert", "файл с сертификатом УЦ")),
                args.get("issuer", null),
                Duration.ofDays(args.getInt("validity-days", 365)));
    }

    /** Конфигурация для тестов и встроенного запуска. */
    public static ServerConfig forTests(int generatorThreads, int ioThreads, int keySize) {
        return new ServerConfig("127.0.0.1", 0, 128, generatorThreads, ioThreads, keySize,
                Path.of("ca.key"), Path.of("ca.crt"), null, Duration.ofDays(365));
    }
}
