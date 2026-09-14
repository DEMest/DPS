package org.example;

import java.util.Arrays;

/**
 * Общая точка входа: выбирает подкоманду (сервер, клиент или создание ключа УЦ).
 */
public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
            return;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "server" -> org.example.server.ServerMain.main(rest);
            case "client" -> org.example.client.ClientMain.main(rest);
            case "gen-ca" -> org.example.ca.CaMain.main(rest);
            case "--help", "-h", "help" -> usage();
            default -> {
                System.err.println("Неизвестная подкоманда: " + args[0]);
                usage();
                System.exit(2);
            }
        }
    }

    private static void usage() {
        System.out.println("""
                Task J1: сервер генерации ключей.

                Использование: <подкоманда> [опции]

                  gen-ca    создать ключ и сертификат удостоверяющего центра
                  server    запустить микросервис генерации ключей
                  client    запросить у сервера пару ключей и сертификат

                Справка по подкоманде: <подкоманда> --help
                """);
    }
}
