package org.example;

import java.util.concurrent.atomic.AtomicLong;

/**
 * То, что умеют обе реализации списка: собственная ({@link LinkedStringList}) и библиотечная
 * ({@link SyncStringList}). Благодаря общему интерфейсу остальная программа не зависит от того,
 * какой список выбран.
 */
public interface StringList extends Iterable<String> {

    /** Положить строку в начало списка. */
    void addFirst(String s);

    /**
     * Один проход пузырька от головы до хвоста — всегда по всему списку.
     *
     * <p>Шаг — попытка поменять местами две соседние строки. Внутри шага (строки уже захвачены)
     * нить ждёт {@code insideMs}, между шагами (ничего не захвачено) — {@code betweenMs}.
     * После каждого шага счётчик {@code steps} увеличивается на единицу.
     *
     * @return сколько шагов сделано; 0 — в списке меньше двух строк, сравнивать нечего
     */
    int sortPass(long insideMs, long betweenMs, AtomicLong steps) throws InterruptedException;
}
