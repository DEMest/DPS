package org.example;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListTest {

    /** Тесты с этим источником запускаются для обеих реализаций списка. */
    static Stream<Named<Supplier<StringList>>> bothLists() {
        return Stream.of(
                Named.of("собственный список", LinkedStringList::new),
                Named.of("synchronizedList", SyncStringList::new));
    }

    // ---------- Простые проверки ----------

    @ParameterizedTest
    @MethodSource("bothLists")
    void addFirstPutsLineAtHead(Supplier<StringList> newList) {
        StringList list = newList.get();
        list.addFirst("c");
        list.addFirst("b");
        list.addFirst("a");

        assertEquals(List.of("a", "b", "c"), toList(list));
    }

    @Test
    void longLineIsCutInto80CharPartsInOriginalOrder() {
        StringList list = new LinkedStringList();
        list.addFirst("old");

        Main.addLine(list, "a".repeat(80) + "b".repeat(80) + "c");

        assertEquals(List.of("a".repeat(80), "b".repeat(80), "c", "old"), toList(list));
    }

    @Test
    void passComparesEveryPairEvenInSortedList() throws InterruptedException {
        StringList list = listOf("a", "b", "c", "d", "e");
        AtomicLong steps = new AtomicLong();

        assertEquals(4, list.sortPass(0, 0, steps));
        assertEquals(4, list.sortPass(0, 0, steps));
        assertEquals(8, steps.get());
    }

    @Test
    void sortingMovesNodesNotStrings() throws InterruptedException {
        LinkedStringList list = listOf("e", "d", "c", "b", "a");
        List<LinkedStringList.Node> before = nodes(list);

        for (int i = 0; i < 4; i++) {
            list.sortPass(0, 0, new AtomicLong());
        }

        assertEquals(List.of("a", "b", "c", "d", "e"), toList(list));
        // Те же самые объекты-записи, только в обратном порядке.
        List<LinkedStringList.Node> after = nodes(list);
        for (int i = 0; i < before.size(); i++) {
            assertSame(before.get(i), after.get(after.size() - 1 - i));
        }
    }

    @Test
    void stepLocksOnlyThreeNeighbours() throws InterruptedException {
        LinkedStringList list = listOf("a", "b", "c", "d", "e", "f");
        List<LinkedStringList.Node> nodes = nodes(list);
        // Внутри первого шага нить спит 2 с, держа голову и две первые записи.
        Thread sorter = new Thread(() -> {
            try {
                list.sortPass(2000, 0, new AtomicLong());
            } catch (InterruptedException e) {
                // конец теста
            }
        });
        sorter.start();
        while (!nodes.get(1).lock.isLocked()) {
            Thread.sleep(5);
        }

        assertTrue(list.head.lock.isLocked());
        assertTrue(nodes.get(0).lock.isLocked());
        for (int i = 2; i < nodes.size(); i++) {
            assertFalse(nodes.get(i).lock.isLocked(), "запись " + i + " не должна быть захвачена");
        }
        sorter.interrupt();
        sorter.join();
        // После остановки все записи отпущены.
        assertFalse(list.head.lock.isLocked());
        assertTrue(nodes.stream().noneMatch(n -> n.lock.isLocked()));
    }

    // ---------- Несколько нитей ----------

    @ParameterizedTest
    @MethodSource("bothLists")
    void fourThreadsSortTheList(Supplier<StringList> newList) {
        StringList list = newList.get();
        List<String> lines = Bench.randomLines(40, new Random(1));
        lines.forEach(list::addFirst);
        List<String> sorted = new ArrayList<>(lines);
        sorted.sort(null);

        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            List<Thread> sorters = Main.startSorters(list, 4, 0, 0, new AtomicLong());
            while (!toList(list).equals(sorted)) {
                Thread.sleep(10);
            }
            Main.stopSorters(sorters);
        });
    }

    /**
     * Восемь нитей сортируют без задержек, а мы одновременно добавляем строки и обходим список.
     * Каждый обход обязан вернуть ровно добавленные строки. Мёртвая блокировка уронила бы тест
     * по таймауту.
     */
    @ParameterizedTest
    @MethodSource("bothLists")
    void everyPrintShowsExactlyTheAddedLines(Supplier<StringList> newList) {
        StringList list = newList.get();
        Random random = new Random(2);
        List<String> added = new ArrayList<>(Bench.randomLines(20, random));
        added.forEach(list::addFirst);

        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            List<Thread> sorters = Main.startSorters(list, 8, 0, 0, new AtomicLong());
            long end = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            int prints = 0;
            while (System.nanoTime() < end) {
                if (random.nextInt(4) == 0) {
                    String line = Bench.randomLines(1, random).get(0);
                    list.addFirst(line);
                    added.add(line);
                } else {
                    List<String> seen = toList(list);
                    seen.sort(null);
                    List<String> expected = new ArrayList<>(added);
                    expected.sort(null);
                    assertEquals(expected, seen);
                    prints++;
                }
            }
            Main.stopSorters(sorters);
            assertTrue(prints > 10, "обходов: " + prints);
        });
    }

    /** Пока нить ждёт между шагами, она ничего не держит, и вывод проходит сразу. */
    @ParameterizedTest
    @MethodSource("bothLists")
    void printDoesNotWaitForDelayBetweenSteps(Supplier<StringList> newList) throws InterruptedException {
        StringList list = newList.get();
        Bench.randomLines(10, new Random(3)).forEach(list::addFirst);
        AtomicLong steps = new AtomicLong();
        // Шаг длится 200 мс, потом нить 5 с спит между шагами.
        List<Thread> sorters = Main.startSorters(list, 1, 200, 5000, steps);
        while (steps.get() == 0) {
            Thread.sleep(5);
        }

        long start = System.nanoTime();
        int count = toList(list).size();
        long ms = (System.nanoTime() - start) / 1_000_000;
        Main.stopSorters(sorters);

        assertEquals(10, count);
        assertTrue(ms < 100, "вывод занял " + ms + " мс");
    }

    // ---------- Число шагов ----------

    @Test
    void theoryForOneSecondDelaysAndOneMinute() {
        // Собственный список растёт с числом нитей: 30, 120, 240 шагов.
        assertEquals(30, Bench.theory(1, 60, 1000, 1000, 17), 1e-9);
        assertEquals(120, Bench.theory(4, 60, 1000, 1000, 17), 1e-9);
        assertEquals(240, Bench.theory(8, 60, 1000, 1000, 17), 1e-9);
        // synchronizedList упирается в 60 шагов при любом числе нитей больше двух.
        assertEquals(30, Bench.theory(1, 60, 1000, 1000, 1), 1e-9);
        assertEquals(60, Bench.theory(4, 60, 1000, 1000, 1), 1e-9);
        assertEquals(60, Bench.theory(8, 60, 1000, 1000, 1), 1e-9);
    }

    /**
     * Короткий замер, 8 нитей по 3 с. По теории собственный список делает в 4 раза больше шагов.
     * В первые секунды он до теории не дотягивает (нити стартуют с головы и ждут друг друга),
     * поэтому проверяем с запасом — хотя бы в 1,8 раза больше.
     */
    @Test
    void linkedListMakesSeveralTimesMoreStepsThanSyncList() throws InterruptedException {
        List<String> lines = Bench.randomLines(50, new Random(5));

        long linked = Bench.measure(new LinkedStringList(), lines, 8, 50, 50, 3);
        long sync = Bench.measure(new SyncStringList(), lines, 8, 50, 50, 3);

        double syncLimit = Bench.theory(8, 3, 50, 50, 1);       // 60 шагов
        assertTrue(sync <= syncLimit * 1.02, "synchronizedList: " + sync + " при потолке " + syncLimit);
        assertTrue(linked >= sync * 1.8, "собственный список: " + linked + ", synchronizedList: " + sync);
    }

    // ---------- Вспомогательное ----------

    private static LinkedStringList listOf(String... values) {
        LinkedStringList list = new LinkedStringList();
        for (int i = values.length - 1; i >= 0; i--) {
            list.addFirst(values[i]);
        }
        return list;
    }

    private static List<String> toList(Iterable<String> list) {
        List<String> result = new ArrayList<>();
        for (String s : list) {
            result.add(s);
        }
        return result;
    }

    /** Записи по порядку; только когда никто не сортирует. */
    private static List<LinkedStringList.Node> nodes(LinkedStringList list) {
        List<LinkedStringList.Node> result = new ArrayList<>();
        for (LinkedStringList.Node n = list.head.next; n != null; n = n.next) {
            result.add(n);
        }
        return result;
    }
}
