package org.example;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Собственный односвязный список, у каждой записи — своя блокировка.
 *
 * <pre>
 *   head → a → b → c → null
 * </pre>
 *
 * {@code head} — фиктивная запись без строки. С ней у любой пары записей, даже у первых двух,
 * есть предыдущая запись, поэтому перестановка всегда делается одинаково.
 *
 * <p>Правила, на которых всё держится:
 * <ul>
 *   <li>поле {@code next} записи читается и меняется только под блокировкой этой записи;</li>
 *   <li>записи всегда захватываются по порядку от головы к хвосту — так мёртвая блокировка
 *       невозможна: никто не держит дальнюю запись, ожидая ближнюю;</li>
 *   <li>весь список целиком не блокируется никогда.</li>
 * </ul>
 */
public class LinkedStringList implements StringList {

    /** Запись списка. Строка {@code final}: при сортировке меняются ссылки, а не строки. */
    static class Node {
        final String value;
        final ReentrantLock lock = new ReentrantLock();
        Node next;

        Node(String value, Node next) {
            this.value = value;
            this.next = next;
        }
    }

    final Node head = new Node(null, null);

    @Override
    public void addFirst(String s) {
        head.lock.lock();
        try {
            head.next = new Node(s, head.next);
        } finally {
            head.lock.unlock();
        }
    }

    /**
     * Проход пузырька. Каждый шаг захватывает три соседние записи — {@code prev}, {@code a},
     * {@code b} — и, если {@code a > b}, переставляет ссылки:
     *
     * <pre>
     *   prev → a → b → c     ⇒     prev → b → a → c
     * </pre>
     *
     * Между шагами нить не держит ни одной записи — в это время список можно спокойно вывести.
     */
    @Override
    public int sortPass(long insideMs, long betweenMs, AtomicLong steps) throws InterruptedException {
        int made = 0;
        Node prev = head;
        while (true) {
            prev.lock.lock();
            Node a = prev.next;
            if (a == null) {
                prev.lock.unlock();
                return made;
            }
            a.lock.lock();
            Node b = a.next;
            if (b == null) {
                a.lock.unlock();
                prev.lock.unlock();
                return made;
            }
            b.lock.lock();

            Node nextPrev;
            try {
                Thread.sleep(insideMs);
                if (a.value.compareTo(b.value) > 0) {
                    prev.next = b;
                    a.next = b.next;
                    b.next = a;
                    nextPrev = b;
                } else {
                    nextPrev = a;
                }
                steps.incrementAndGet();
                made++;
            } finally {
                b.lock.unlock();
                a.lock.unlock();
                prev.lock.unlock();
            }

            Thread.sleep(betweenMs);
            prev = nextPrev;
        }
    }

    /**
     * Обход «рука за руку»: держим текущую запись, захватываем следующую, потом отпускаем
     * текущую. Пока запись захвачена, её нельзя переставить с соседом, а значит, ни одна
     * запись не перепрыгнет через неё. Поэтому каждая строка выводится ровно один раз — без
     * пропусков и повторов, даже если сортировка идёт прямо во время вывода.
     *
     * <p>Последняя блокировка отпускается, когда {@code hasNext()} вернёт {@code false},
     * поэтому обход нужно доводить до конца — цикл for-each так и делает.
     */
    @Override
    public Iterator<String> iterator() {
        head.lock.lock();
        return new Iterator<>() {
            private Node current = head;    // захваченная запись; null — обход закончен

            @Override
            public boolean hasNext() {
                if (current == null) {
                    return false;
                }
                if (current.next != null) {
                    return true;
                }
                current.lock.unlock();
                current = null;
                return false;
            }

            @Override
            public String next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Node following = current.next;
                following.lock.lock();
                current.lock.unlock();
                current = following;
                return current.value;
            }
        };
    }
}
