package org.example;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Библиотечный вариант: {@code ArrayList}, обёрнутый {@code Collections.synchronizedList()}.
 *
 * <p>Обёртка защищает каждый вызов по отдельности, а шаг сортировки — это несколько вызовов
 * (прочитать две строки, сравнить, записать обратно). Если между ними другая нить переставит
 * строки или в начало вставят новую (индексы сдвинутся), одна строка задвоится, а другая
 * пропадёт. Поэтому шаг целиком, вместе с задержкой внутри шага, идёт под монитором списка.
 * Захватить отдельные элементы у {@code synchronizedList} нельзя — только весь список.
 */
public class SyncStringList implements StringList {

    private final List<String> list = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void addFirst(String s) {
        list.add(0, s);
    }

    @Override
    public int sortPass(long insideMs, long betweenMs, AtomicLong steps) throws InterruptedException {
        int made = 0;
        for (int i = 0; ; i++) {
            synchronized (list) {
                if (i + 1 >= list.size()) {
                    return made;
                }
                String a = list.get(i);
                String b = list.get(i + 1);
                Thread.sleep(insideMs);
                if (a.compareTo(b) > 0) {
                    list.set(i, b);
                    list.set(i + 1, a);
                }
                steps.incrementAndGet();
                made++;
            }
            Thread.sleep(betweenMs);
        }
    }

    @Override
    public Iterator<String> iterator() {
        synchronized (list) {
            return new ArrayList<>(list).iterator();
        }
    }
}
