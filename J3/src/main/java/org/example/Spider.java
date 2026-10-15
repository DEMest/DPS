package org.example;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * «Паук»: обходит пути URL начиная с заданного (обычно {@code /}) и переходит по путям из поля
 * {@code successors} каждого ответа.
 *
 * <p><b>Одна виртуальная нить на каждый URL.</b> Как только из ответа стал известен новый путь,
 * под него сразу запускается своя виртуальная нить, и запрос уходит, не дожидаясь остальных.
 * Сервер отвечает на запрос до 12 секунд, поэтому все известные на данный момент запросы
 * должны ждать ответа одновременно — тогда обход займёт столько, сколько самая медленная
 * цепочка «запрос → его successors → …», а не сумму всех задержек. Нитей ОС на это не нужно:
 * ожидающая ответа виртуальная нить снимается с нити-носителя, и сотни ожидающих запросов
 * обслуживает пул из нескольких нитей ОС.
 *
 * <p><b>Каждый путь запрашивается ровно один раз.</b> Путь попадает в обход только если
 * {@code seen.add(path)} вернул {@code true}; это атомарная операция над
 * {@link ConcurrentHashMap#newKeySet()}, поэтому даже если две нити одновременно нашли один
 * и тот же путь, запрос отправит только одна из них. Циклы в графе тоже не страшны.
 *
 * <p><b>Конец обхода.</b> Нить, обработав свой URL, ждёт нити, которые сама запустила
 * (блокировка виртуальной нити почти ничего не стоит). Значит, когда закончилась корневая
 * задача, закончились и все остальные: обход завершён, новых путей больше не будет.
 *
 * <p>Объект одноразовый: один {@code Spider} — один обход. Счётчики можно читать из другой
 * нити во время обхода, чтобы показывать ход работы.
 */
public class Spider {

    /**
     * Результат обхода.
     *
     * @param messages    все собранные сообщения в лексикографическом порядке
     *                    ({@link String#compareTo}); одинаковые сообщения с разных URL
     *                    остаются, каждое — один раз на свой URL
     * @param byPath      сообщение каждого успешно обойдённого URL, по путям
     * @param failures    URL, которые не удалось получить, и причина
     * @param visited     сколько URL запрошено всего (успешно и с ошибкой)
     * @param maxParallel наибольшее число запросов, одновременно ожидавших ответа
     * @param elapsed     время обхода
     */
    public record Result(List<String> messages, SortedMap<String, String> byPath,
                         SortedMap<String, String> failures, int visited, int maxParallel,
                         Duration elapsed) {
    }

    private final PageSource source;

    /** Все пути, которые уже запрошены или запрашиваются. */
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final Map<String, String> messages = new ConcurrentHashMap<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private volatile boolean started;

    public Spider(PageSource source) {
        this.source = source;
    }

    /**
     * Обходит все пути, достижимые из {@code start}, и возвращается, когда обход закончен.
     * Ошибка отдельного URL обход не прерывает: она попадает в {@link Result#failures()}.
     */
    public Result crawl(String start) throws InterruptedException {
        synchronized (this) {
            if (started) {
                throw new IllegalStateException("Spider одноразовый: создайте новый для нового обхода");
            }
            started = true;
        }
        long began = System.nanoTime();
        String root = toPath(start);
        seen.add(root);
        // Исполнитель «новая виртуальная нить на каждую задачу»; имена spider-1, spider-2, …
        // видны в журнале (--verbose).
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("spider-", 1).factory())) {
            Future<?> rootTask = executor.submit(() -> visit(root, executor));
            try {
                rootTask.get();
            } catch (InterruptedException e) {
                executor.shutdownNow();
                throw e;
            } catch (ExecutionException e) {
                throw new IllegalStateException("обход прерван", e.getCause());
            }
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - began);

        List<String> sorted = new ArrayList<>(messages.values());
        Collections.sort(sorted);
        return new Result(Collections.unmodifiableList(sorted),
                Collections.unmodifiableSortedMap(new TreeMap<>(messages)),
                Collections.unmodifiableSortedMap(new TreeMap<>(failures)),
                seen.size(), maxInFlight.get(), elapsed);
    }

    /** Запрашивает один URL, запускает нити для новых путей из ответа и ждёт их. */
    private void visit(String path, ExecutorService executor) {
        List<Future<?>> children = new ArrayList<>();
        try {
            Page page = fetch(path);
            messages.put(path, page.message());
            for (String successor : page.successors()) {
                String next = toPath(successor);
                if (seen.add(next)) {
                    children.add(executor.submit(() -> visit(next, executor)));
                }
            }
        } catch (InterruptedException e) {
            // Обход отменён (crawl прервали): выходим, не дожидаясь остальных.
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            failures.put(path, HttpPageSource.describe(e));
            Log.info("GET %s — ошибка: %s", path, HttpPageSource.describe(e));
        }
        for (Future<?> child : children) {
            try {
                child.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                // visit перехватывает все Exception, сюда попадают только Error — пусть
                // поднимаются до crawl, а не теряются молча.
                throw new IllegalStateException(e.getCause());
            }
        }
    }

    private Page fetch(String path) throws Exception {
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        long began = System.nanoTime();
        try {
            Page page = source.fetch(path);
            Log.debug("GET %s — 200 за %.2f с, переходов: %d",
                    path, (System.nanoTime() - began) / 1e9, page.successors().size());
            return page;
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /**
     * Путь из поля {@code successors} в путь URL. Сервер присылает пути без ведущей косой
     * черты ({@code "425"}), а запрашивать нужно {@code /425}; путь с косой чертой не меняется.
     */
    static String toPath(String successor) {
        return successor.startsWith("/") ? successor : "/" + successor;
    }

    /** Сколько URL уже запрошено или запрашивается. */
    public int discovered() {
        return seen.size();
    }

    /** Сколько URL прислали ответ. */
    public int answered() {
        return messages.size();
    }

    /** Сколько URL получить не удалось. */
    public int failed() {
        return failures.size();
    }

    /** Сколько запросов прямо сейчас ждут ответа. */
    public int inFlight() {
        return inFlight.get();
    }
}
