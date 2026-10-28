package org.example;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Родительская нить: читает строки и кладёт их в начало списка, по пустой строке выводит
 * список. Дочерние нити всё это время сортируют список.
 *
 * <pre>
 *   java -jar J2.jar [--list linked|sync] [--threads 2] [--inner-delay 1000] [--delay 1000]
 *   java -jar J2.jar bench [опции]      — замер числа шагов, см. {@link Bench}
 * </pre>
 */
public class Main {

    /** Строки длиннее режутся на части. */
    static final int MAX_LENGTH = 80;

    public static void main(String[] args) throws Exception {
        try {
            if (args.length > 0 && args[0].equals("bench")) {
                Bench.run(parseOptions(Arrays.copyOfRange(args, 1, args.length), Bench.OPTIONS));
            } else {
                interactive(parseOptions(args, Set.of("list", "threads", "inner-delay", "delay")));
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.exit(2);
        }
    }

    private static void interactive(Map<String, String> options) throws Exception {
        if (options.containsKey("help")) {
            System.out.println("""
                    Использование: java -jar build/libs/J2.jar [опции]
                      --list linked        собственный связный список (по умолчанию)
                      --list sync          ArrayList + Collections.synchronizedList
                      --threads <n>        число сортирующих нитей, по умолчанию 2
                      --inner-delay <мс>   задержка внутри шага, по умолчанию 1000
                      --delay <мс>         задержка между шагами, по умолчанию 1000
                    Замер числа шагов: java -jar build/libs/J2.jar bench --help""");
            return;
        }
        String kind = options.getOrDefault("list", "linked");
        StringList list = switch (kind) {
            case "linked" -> new LinkedStringList();
            case "sync" -> new SyncStringList();
            default -> throw new IllegalArgumentException("--list: ожидается linked или sync, получено " + kind);
        };
        int threads = (int) number(options, "threads", 2, 1);
        long insideMs = number(options, "inner-delay", 1000, 0);
        long betweenMs = number(options, "delay", 1000, 0);

        System.out.printf("""
                Список: %s, сортирующих нитей: %d, задержка внутри шага %d мс, между шагами %d мс.
                Вводите строки. Пустая строка — показать список, Ctrl+D (в Windows Ctrl+Z и Enter) — выход.
                %n""", kind.equals("linked") ? "собственный связный" : "ArrayList + synchronizedList",
                threads, insideMs, betweenMs);

        AtomicLong steps = new AtomicLong();
        List<Thread> sorters = startSorters(list, threads, insideMs, betweenMs, steps);

        Charset charset = System.console() != null ? System.console().charset() : Charset.defaultCharset();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, charset));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isEmpty()) {
                printList(list, steps, System.out);
            } else {
                addLine(list, line);
            }
        }

        stopSorters(sorters);
        System.out.println("Ввод закончен, сортирующие нити остановлены.");
        printList(list, steps, System.out);
    }

    /** Кладёт строку в начало списка; длинную — частями по 80 символов, в исходном порядке. */
    static void addLine(StringList list, String line) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < line.length(); i += MAX_LENGTH) {
            parts.add(line.substring(i, Math.min(line.length(), i + MAX_LENGTH)));
        }
        // С конца: последняя часть ляжет в начало первой, а первая окажется самой первой.
        for (int i = parts.size() - 1; i >= 0; i--) {
            list.addFirst(parts.get(i));
        }
    }

    /**
     * Выводит список обычным циклом for-each, как требует задание. Счётчик шагов читается после
     * обхода: обход мог подождать шаг, который шёл в этот момент, и этот шаг уже виден в списке.
     */
    static void printList(StringList list, AtomicLong steps, PrintStream out) {
        StringBuilder text = new StringBuilder();
        int count = 0;
        for (String s : list) {
            count++;
            text.append(String.format("%4d  %s%n", count, s));
        }
        out.printf("--- записей: %d, шагов сортировки: %d ---%n%s", count, steps.get(), text);
        out.flush();
    }

    /** Запускает {@code count} дочерних нитей, каждая бесконечно сортирует список. */
    static List<Thread> startSorters(StringList list, int count, long insideMs, long betweenMs, AtomicLong steps) {
        List<Thread> sorters = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Thread sorter = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        if (list.sortPass(insideMs, betweenMs, steps) == 0) {
                            Thread.sleep(Math.max(1, betweenMs));
                        }
                    }
                } catch (InterruptedException e) {
                }
            }, "sorter-" + i);
            sorter.setDaemon(true);
            sorter.start();
            sorters.add(sorter);
        }
        return sorters;
    }

    static void stopSorters(List<Thread> sorters) throws InterruptedException {
        for (Thread sorter : sorters) {
            sorter.interrupt();
        }
        for (Thread sorter : sorters) {
            sorter.join();
        }
    }

    /** Разбирает аргументы вида {@code --имя значение}; {@code --help} — без значения. */
    static Map<String, String> parseOptions(String[] args, Set<String> known) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--help") || args[i].equals("-h")) {
                options.put("help", "");
                continue;
            }
            String name = args[i].startsWith("--") ? args[i].substring(2) : null;
            if (name == null || !known.contains(name)) {
                throw new IllegalArgumentException("неизвестный аргумент " + args[i] + " (справка: --help)");
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("у " + args[i] + " нет значения");
            }
            options.put(name, args[++i]);
        }
        return options;
    }

    /** Целое число из опций, не меньше {@code min}. */
    static long number(Map<String, String> options, String name, long defaultValue, long min) {
        String value = options.get(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed >= min) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // Сообщение ниже.
        }
        throw new IllegalArgumentException("--" + name + " должно быть целым числом не меньше " + min + ", получено " + value);
    }
}
