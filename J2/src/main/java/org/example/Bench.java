package org.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Замер: сколько шагов суммарно сделают все дочерние нити за заданное время в собственном
 * списке и в {@code synchronizedList}, и сравнение с теорией.
 *
 * <p>Теория. Одна нить делает шаг за {@code d_вн + d_между}, N нитей — в N раз больше. Но пока
 * идёт задержка внутри шага, записи захвачены, и одновременно может идти не больше M шагов,
 * то есть не больше {@code M / d_вн} шагов в единицу времени. Итого за время T:
 *
 * <pre>
 *   S = T · min( N / (d_вн + d_между),  M / d_вн )
 * </pre>
 *
 * Собственный список: шаг держит 3 соседние записи, M = ⌊(L + 1) / 3⌋ (L записей плюс голова)
 * — на длинном списке ограничение не срабатывает. {@code synchronizedList}: шаг держит весь
 * список, M = 1.
 */
public class Bench {

    static final Set<String> OPTIONS = Set.of("threads", "size", "duration", "inner-delay", "delay");

    static void run(Map<String, String> options) throws InterruptedException {
        if (options.containsKey("help")) {
            System.out.println("""
                    Использование: java -jar build/libs/J2.jar bench [опции]
                      --threads <n>        число сортирующих нитей N, по умолчанию 4
                      --size <n>           число строк в списке L, по умолчанию 50
                      --duration <с>       сколько секунд мерить каждый список, по умолчанию 10
                      --inner-delay <мс>   задержка внутри шага, по умолчанию 100
                      --delay <мс>         задержка между шагами, по умолчанию 100""");
            return;
        }
        int threads = (int) Main.number(options, "threads", 4, 1);
        int size = (int) Main.number(options, "size", 50, 2);
        long seconds = Main.number(options, "duration", 10, 1);
        long insideMs = Main.number(options, "inner-delay", 100, 0);
        long betweenMs = Main.number(options, "delay", 100, 0);
        if (insideMs + betweenMs == 0) {
            throw new IllegalArgumentException("хотя бы одна задержка должна быть больше нуля");
        }

        System.out.printf("Нитей %d, строк %d, задержка внутри шага %d мс, между шагами %d мс, по %d с на список%n%n",
                threads, size, insideMs, betweenMs, seconds);
        List<String> lines = randomLines(size, new Random(2026));

        long linked = measure(new LinkedStringList(), lines, threads, insideMs, betweenMs, seconds);
        double linkedTheory = theory(threads, seconds, insideMs, betweenMs, (size + 1) / 3);
        System.out.printf("собственный список:  шагов %5d, теория %7.1f (%3.0f %%)%n",
                linked, linkedTheory, 100 * linked / linkedTheory);

        long sync = measure(new SyncStringList(), lines, threads, insideMs, betweenMs, seconds);
        double syncTheory = theory(threads, seconds, insideMs, betweenMs, 1);
        System.out.printf("synchronizedList:    шагов %5d, теория %7.1f (%3.0f %%)%n",
                sync, syncTheory, 100 * sync / syncTheory);

        System.out.printf("""

                Теория: S = T · min(N / (d_вн + d_между), M / d_вн), где M — сколько шагов может идти одновременно:
                  собственный список: M = ⌊(L + 1) / 3⌋ = %d (шаг держит 3 соседние записи)
                  synchronizedList:   M = 1 (шаг держит весь список)
                """, (size + 1) / 3);
    }

    /** Заполняет список строками, даёт нитям посортировать {@code seconds} секунд, возвращает число шагов. */
    static long measure(StringList list, List<String> lines, int threads, long insideMs, long betweenMs,
                        double seconds) throws InterruptedException {
        lines.forEach(list::addFirst);
        AtomicLong steps = new AtomicLong();
        List<Thread> sorters = Main.startSorters(list, threads, insideMs, betweenMs, steps);
        Thread.sleep((long) (seconds * 1000));
        Main.stopSorters(sorters);
        return steps.get();
    }

    /** Теоретическое число шагов: {@code T · min(N / (d_вн + d_между), M / d_вн)}. */
    static double theory(int threads, double seconds, long insideMs, long betweenMs, int maxAtOnce) {
        double ms = seconds * 1000;
        double parallel = threads * ms / (insideMs + betweenMs);
        return insideMs == 0 ? parallel : Math.min(parallel, maxAtOnce * ms / insideMs);
    }

    /** Случайные строки из 3–8 строчных латинских букв. */
    static List<String> randomLines(int count, Random random) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            StringBuilder s = new StringBuilder();
            int length = 3 + random.nextInt(6);
            for (int j = 0; j < length; j++) {
                s.append((char) ('a' + random.nextInt(26)));
            }
            lines.add(s.toString());
        }
        return lines;
    }
}
