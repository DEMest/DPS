package org.example.server;

import org.example.ca.CertificateAuthority;
import org.example.common.Args;
import org.example.common.Logging;

import lombok.extern.slf4j.Slf4j;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Точка входа микросервиса генерации ключей. */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ServerMain {

    public static void main(String[] argv) throws Exception {
        Args args;
        try {
            args = Args.parse(argv, ServerConfig.FLAGS);
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
        if (args.has("config")) {
            args.loadConfig(Path.of(args.get("config", "server.properties")));
        }
        Logging.setVerbose(args.getBoolean("verbose", false));

        ServerConfig config;
        try {
            config = ServerConfig.from(args);
        } catch (IllegalArgumentException e) {
            log.error("{}", e.getMessage());
            usage();
            System.exit(2);
            return;
        }

        CertificateAuthority ca = CertificateAuthority.load(
                config.caKeyFile(), config.caCertFile(), config.issuerName(), config.validity());
        log.info("Ключ подписи прочитан из {}, Issuer Name = {}", config.caKeyFile(), ca.issuer());

        KeyServer server = new KeyServer(config, ca);
        server.start();

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            stopped.countDown();
        }, "shutdown"));
        stopped.await();
    }

    private static void usage() {
        System.out.println("""
                Микросервис генерации пар ключей RSA.

                Использование: server --ca-key <файл> --ca-cert <файл> [опции]

                  --ca-key <файл>       приватный ключ УЦ в формате PEM (создаётся заранее, gen-ca)
                  --ca-cert <файл>      сертификат УЦ в формате PEM
                  --issuer <DN>         Issuer Name выдаваемых сертификатов
                                        (по умолчанию Subject Name сертификата УЦ)
                  --port <порт>         TCP-порт (по умолчанию 9000)
                  --bind <адрес>        адрес прослушивания (по умолчанию 0.0.0.0)
                  --threads <n>         число генерирующих нитей (по умолчанию число ядер)
                  --io-threads <n>      число нитей ввода-вывода (по умолчанию 1)
                  --key-size <бит>      длина приватного ключа RSA (по умолчанию 8192)
                  --validity-days <дни> срок действия выдаваемых сертификатов (по умолчанию 365)
                  --backlog <n>         длина очереди входящих соединений (по умолчанию 512)
                  --config <файл>       properties-файл с теми же именами параметров
                  --verbose             подробный журнал
                  --help                эта справка
                """);
    }
}
