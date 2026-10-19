package org.example;

import java.util.List;

public class Main {

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "http://localhost:8080";

        long start = System.nanoTime();
        Spider spider = new Spider(url);
        List<String> messages = spider.crawl();
        double seconds = (System.nanoTime() - start) / 1e9;

        for (String message : messages) {
            System.out.println(message);
        }
        System.err.printf("Сообщений: %d, ошибок: %d, время: %.1f с%n",
                messages.size(), spider.getFailed().size(), seconds);
    }
}
