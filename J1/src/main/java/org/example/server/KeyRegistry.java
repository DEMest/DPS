package org.example.server;

import org.example.ca.CertificateAuthority;
import org.example.common.KeyResult;

import lombok.extern.slf4j.Slf4j;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Хранилище выданных ключей и точка постановки задач в очередь генерации.
 *
 * <p>Ключи хранятся в оперативной памяти. Первый запрос имени создаёт незавершённый
 * {@link CompletableFuture} и ставит задачу в очередь фиксированного пула генерирующих
 * нитей; повторные запросы (в том числе пришедшие до окончания генерации) получают тот же
 * самый future и, как только он завершится, тот же самый результат.
 */
@Slf4j
public final class KeyRegistry implements AutoCloseable {

    private final ConcurrentHashMap<String, CompletableFuture<KeyResult>> keys = new ConcurrentHashMap<>();
    private final ExecutorService generators;
    private final CertificateAuthority ca;
    private final int keySize;
    private final ThreadLocal<KeyPairGenerator> keyPairGenerator;

    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong generated = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public KeyRegistry(CertificateAuthority ca, int generatorThreads, int keySize) {
        this.ca = ca;
        this.keySize = keySize;
        this.generators = Executors.newFixedThreadPool(generatorThreads, namedThreadFactory("keygen"));
        this.keyPairGenerator = ThreadLocal.withInitial(this::newKeyPairGenerator);
    }

    /**
     * Возвращает future с парой ключей и сертификатом для имени. Генерация запускается
     * только при первом обращении к имени.
     */
    public CompletableFuture<KeyResult> request(String name) {
        requested.incrementAndGet();
        CompletableFuture<KeyResult> existing = keys.get(name);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<KeyResult> fresh = new CompletableFuture<>();
        CompletableFuture<KeyResult> previous = keys.putIfAbsent(name, fresh);
        if (previous != null) {
            return previous;
        }
        try {
            generators.execute(() -> generate(name, fresh));
        } catch (RejectedExecutionException e) {
            keys.remove(name, fresh);
            fresh.completeExceptionally(new IllegalStateException("Сервер останавливается", e));
        }
        return fresh;
    }

    private void generate(String name, CompletableFuture<KeyResult> target) {
        long started = System.nanoTime();
        try {
            KeyPair keyPair = keyPairGenerator.get().generateKeyPair();
            KeyResult result = ca.issue(name, keyPair);
            generated.incrementAndGet();
            log.info("Ключ для \"{}\" готов за {} с", name,
                    String.format("%.2f", (System.nanoTime() - started) / 1e9));
            // Завершение future только публикует результат: ожидающие соединения кладутся
            // в очередь нити ввода-вывода, поэтому генерирующая нить здесь не блокируется.
            target.complete(result);
        } catch (Throwable e) {
            failed.incrementAndGet();
            // Неудачную запись убираем, чтобы следующий запрос смог повторить генерацию.
            keys.remove(name, target);
            log.warn("Не удалось выдать ключ для \"{}\"", name, e);
            target.completeExceptionally(e);
        }
    }

    private KeyPairGenerator newKeyPairGenerator() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(keySize, new SecureRandom());
            return generator;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Алгоритм RSA недоступен", e);
        }
    }

    public long requestedCount() {
        return requested.get();
    }

    public long generatedCount() {
        return generated.get();
    }

    public long failedCount() {
        return failed.get();
    }

    public int cachedNames() {
        return keys.size();
    }

    @Override
    public void close() {
        generators.shutdownNow();
        try {
            generators.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
