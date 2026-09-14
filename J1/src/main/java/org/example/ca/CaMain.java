package org.example.ca;

import org.example.common.Args;

import lombok.extern.slf4j.Slf4j;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * Утилита командной строки: создаёт ключ подписи и самоподписанный сертификат
 * удостоверяющего центра, которые затем читает сервер при запуске.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CaMain {

    private static final Set<String> FLAGS = Set.of("help");

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

        String issuer = args.get("issuer", "CN=DPS Key Service CA, O=NSU, C=RU");
        int keySize = args.getInt("key-size", 4096);
        int validityDays = args.getInt("validity-days", 3650);
        Path keyFile = Path.of(args.get("out-key", "ca.key"));
        Path certFile = Path.of(args.get("out-cert", "ca.crt"));

        log.info("Генерация ключа УЦ: {} бит, Issuer Name = {}", keySize, issuer);
        long started = System.nanoTime();
        CaGenerator.Ca ca = CaGenerator.generate(issuer, keySize, Duration.ofDays(validityDays));
        ca.write(keyFile, certFile);
        log.info("Готово за {} с: ключ {}, сертификат {}",
                String.format("%.1f", (System.nanoTime() - started) / 1e9),
                keyFile.toAbsolutePath(), certFile.toAbsolutePath());
    }

    private static void usage() {
        System.out.println("""
                Создание ключа и сертификата удостоверяющего центра.

                Использование: gen-ca [опции]

                  --issuer <DN>         Issuer Name УЦ (по умолчанию "CN=DPS Key Service CA, O=NSU, C=RU")
                  --key-size <бит>      длина ключа подписи (по умолчанию 4096)
                  --validity-days <дни> срок действия сертификата (по умолчанию 3650)
                  --out-key <файл>      куда записать приватный ключ (по умолчанию ca.key)
                  --out-cert <файл>     куда записать сертификат (по умолчанию ca.crt)
                  --help                эта справка
                """);
    }
}
