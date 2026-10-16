package org.example;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** «Паук» на подставном источнике страниц, без сети. */
class SpiderTest {

    /** Источник страниц из таблицы: путь → (задержка, страница). Считает запросы. */
    static final class FakeSource implements PageSource {

        record Entry(long delayMs, Page page) {
        }

        final Map<String, Entry> pages = new HashMap<>();
        final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        final Set<Thread> threads = ConcurrentHashMap.newKeySet();

        FakeSource page(String path, long delayMs, String message, String... successors) {
            pages.put(path, new Entry(delayMs, new Page(message, List.of(successors))));
            return this;
        }

        @Override
        public Page fetch(String path) throws IOException, InterruptedException {
            hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            threads.add(Thread.currentThread());
            Entry entry = pages.get(path);
            if (entry == null) {
                throw new IOException("сервер ответил кодом 404");
            }
            Thread.sleep(entry.delayMs());
            return entry.page();
        }
    }

    @Test
    void collectsEveryMessageOnceAndSortsThem() throws InterruptedException {
        // Ромб (c достижим из a и из b), цикл (c → /), петля (b → b) и пути с косой чертой и без.
        FakeSource source = new FakeSource()
                .page("/", 0, "root", "a", "/b")
                .page("/a", 30, "alpha", "c")
                .page("/b", 10, "beta", "/c", "b")
                .page("/c", 20, "gamma", "/", "a");

        Spider.Result result = new Spider(source).crawl("/");

        assertEquals(List.of("alpha", "beta", "gamma", "root"), result.messages());
        assertEquals(Map.of("/", "root", "/a", "alpha", "/b", "beta", "/c", "gamma"), result.byPath());
        assertEquals(Map.of(), result.failures());
        assertEquals(4, result.visited());
        source.hits.forEach((path, count) -> assertEquals(1, count.get(), "запросов " + path));
        assertEquals(Set.of("/", "/a", "/b", "/c"), source.hits.keySet());
    }

    @Test
    void ordersByCharacterCodesAndKeepsEqualAndEmptyMessages() throws InterruptedException {
        FakeSource source = new FakeSource()
                .page("/", 0, "b", "1", "2", "3", "4", "5", "6", "7")
                .page("/1", 0, "B")
                .page("/2", 0, "a")
                .page("/3", 0, "10")
                .page("/4", 0, "9")
                .page("/5", 0, "ab")
                .page("/6", 0, "")
                .page("/7", 0, "a");

        Spider.Result result = new Spider(source).crawl("/");

        // String.compareTo: цифры < заглавные < строчные; префикс раньше более длинной строки;
        // одинаковые сообщения с разных URL — каждое на своём месте.
        assertEquals(List.of("", "10", "9", "B", "a", "a", "ab", "b"), result.messages());
    }

    @Test
    void requestsWaitConcurrentlyOnVirtualThreads() {
        // Корень → 1000 страниц по 1 с → у каждой ещё одна страница на 1 с. Последовательно это
        // 2000 с, одновременно — около 2 с.
        FakeSource source = new FakeSource();
        String[] children = new String[1000];
        for (int i = 0; i < children.length; i++) {
            children[i] = "p" + i;
            source.page("/p" + i, 1000, "m" + i, "q" + i)
                    .page("/q" + i, 1000, "n" + i);
        }
        source.page("/", 0, "root", children);

        Spider.Result result = assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> new Spider(source).crawl("/"));

        assertEquals(2001, result.messages().size());
        assertTrue(result.elapsed().compareTo(Duration.ofSeconds(2)) >= 0, "быстрее самой длинной цепочки: " + result.elapsed());
        assertTrue(result.elapsed().compareTo(Duration.ofSeconds(6)) < 0, "запросы шли не одновременно: " + result.elapsed());
        assertTrue(result.maxParallel() >= 1000, "одновременно ждали ответа: " + result.maxParallel());
        for (Thread thread : source.threads) {
            assertTrue(thread.isVirtual(), thread + " — не виртуальная нить");
            assertTrue(thread.getName().startsWith("spider-"), thread.getName());
        }
    }

    @Test
    void newPathIsRequestedWithoutWaitingForSiblings() throws InterruptedException {
        // Обход не идёт «по уровням»: /fast-child запрашивается сразу после ответа /fast,
        // не дожидаясь медленного соседа /slow.
        AtomicLong childRequestedAt = new AtomicLong();
        long began = System.nanoTime();
        FakeSource pages = new FakeSource()
                .page("/", 0, "root", "fast", "slow")
                .page("/fast", 0, "fast", "fast-child")
                .page("/slow", 2000, "slow")
                .page("/fast-child", 0, "child");
        PageSource source = path -> {
            if (path.equals("/fast-child")) {
                childRequestedAt.set(System.nanoTime() - began);
            }
            return pages.fetch(path);
        };

        new Spider(source).crawl("/");

        assertTrue(childRequestedAt.get() < Duration.ofSeconds(1).toNanos(),
                "дочерняя страница запрошена через " + childRequestedAt.get() / 1_000_000 + " мс");
    }

    @Test
    void failedUrlIsReportedAndOthersAreCollected() throws InterruptedException {
        FakeSource pages = new FakeSource()
                .page("/", 0, "root", "missing", "broken", "ok")
                .page("/ok", 0, "fine", "deeper")
                .page("/deeper", 0, "deep");
        PageSource source = path -> {
            if (path.equals("/broken")) {
                throw new IllegalArgumentException("плохой путь");
            }
            return pages.fetch(path);
        };

        Spider.Result result = new Spider(source).crawl("/");

        assertEquals(List.of("deep", "fine", "root"), result.messages());
        assertEquals(Map.of("/missing", "сервер ответил кодом 404", "/broken", "плохой путь"), result.failures());
        assertEquals(5, result.visited());
    }

    @Test
    void progressCountersAreReadableDuringCrawl() throws Exception {
        FakeSource source = new FakeSource()
                .page("/", 0, "root", "1", "2", "3")
                .page("/1", 0, "one")
                .page("/2", 0, "two")
                .page("/3", 1000, "three");
        Spider spider = new Spider(source);
        List<Integer> seenInFlight = new ArrayList<>();

        Thread crawl = Thread.ofVirtual().start(() -> {
            try {
                spider.crawl("/");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.sleep(500);
        seenInFlight.add(spider.inFlight());
        int answered = spider.answered();
        crawl.join();

        assertEquals(List.of(1), seenInFlight, "ответа ждёт только /3");
        assertEquals(3, answered);
        assertEquals(4, spider.answered());
        assertEquals(4, spider.discovered());
        assertEquals(0, spider.failed());
    }

    @Test
    void spiderIsSingleUse() throws InterruptedException {
        Spider spider = new Spider(new FakeSource().page("/", 0, "root"));
        spider.crawl("/");

        assertThrows(IllegalStateException.class, () -> spider.crawl("/"));
    }

    @Test
    void successorBecomesUrlPath() {
        assertEquals("/425", Spider.toPath("425"));
        assertEquals("/425", Spider.toPath("/425"));
        assertEquals("/", Spider.toPath("/"));
        assertEquals("/", Spider.toPath(""));
    }
}
