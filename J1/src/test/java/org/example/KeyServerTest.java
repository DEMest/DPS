package org.example;

import org.example.ca.CaGenerator;
import org.example.ca.CertificateAuthority;
import org.example.client.KeyClient;
import org.example.common.KeyResult;
import org.example.server.KeyServer;
import org.example.server.ServerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Интеграционные тесты сервера генерации ключей. Длина ключа уменьшена до 1024 бит,
 * чтобы тесты выполнялись быстро; логика от длины ключа не зависит.
 */
class KeyServerTest {

    private static final int KEY_SIZE = 1024;
    private static final String ISSUER = "CN=Test CA, O=DPS";

    private static CertificateAuthority ca;
    private static CaGenerator.Ca caMaterial;
    private static KeyServer server;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        caMaterial = CaGenerator.generate(ISSUER, 2048, Duration.ofDays(1));
        ca = CertificateAuthority.of(caMaterial.keyPair().getPrivate(), caMaterial.certificate(), ISSUER, Duration.ofDays(1));
        server = new KeyServer(ServerConfig.forTests(2, 2, KEY_SIZE), ca);
        server.start();
        port = server.port();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    private static KeyResult request(String name) throws IOException, InterruptedException {
        return new KeyClient("127.0.0.1", port, Duration.ZERO, false, 60_000).request(name);
    }

    @Test
    @DisplayName("Сервер выдаёт ключ и подписанный сертификат на переданное имя")
    @Timeout(60)
    void issuesSignedCertificate() throws Exception {
        String name = "alice-" + System.nanoTime();
        KeyResult result = request(name);

        assertNotNull(result);
        assertTrue(new String(result.privateKeyPem(), StandardCharsets.US_ASCII).startsWith("-----BEGIN PRIVATE KEY-----"));
        assertTrue(new String(result.certPem(), StandardCharsets.US_ASCII).startsWith("-----BEGIN CERTIFICATE-----"));

        X509Certificate certificate = parseCertificate(result.certPem());
        PrivateKey privateKey = parsePrivateKey(result.privateKeyPem());

        // Subject Name совпадает с именем, переданным клиентом.
        assertEquals("CN=" + name, certificate.getSubjectX500Principal().getName());
        // Issuer Name фиксирован настройками сервиса.
        assertEquals(caMaterial.certificate().getSubjectX500Principal(), certificate.getIssuerX500Principal());
        // Подпись сделана ключом УЦ.
        certificate.verify(caMaterial.certificate().getPublicKey());
        certificate.checkValidity();
        // Приватный ключ соответствует публичному ключу из сертификата.
        assertEquals(((RSAPrivateCrtKey) privateKey).getModulus(), ((RSAPublicKey) certificate.getPublicKey()).getModulus());
        assertEquals(KEY_SIZE, ((RSAPublicKey) certificate.getPublicKey()).getModulus().bitLength());
    }

    @Test
    @DisplayName("Повторный запрос того же имени возвращает ранее созданные ключи")
    @Timeout(60)
    void sameNameReturnsCachedKeys() throws Exception {
        String name = "repeat-" + System.nanoTime();
        long generatedBefore = server.registry().generatedCount();

        KeyResult first = request(name);
        KeyResult second = request(name);

        assertArrayEquals(first.privateKeyPem(), second.privateKeyPem());
        assertArrayEquals(first.certPem(), second.certPem());
        assertEquals(1, server.registry().generatedCount() - generatedBefore,
                "для одного имени ключ должен генерироваться ровно один раз");
    }

    @Test
    @DisplayName("Повторные запросы, пришедшие до окончания генерации, получают тот же ключ")
    @Timeout(120)
    void concurrentRequestsForSameNameShareOneGeneration() throws Exception {
        String name = "concurrent-" + System.nanoTime();
        int clients = 50;
        long generatedBefore = server.registry().generatedCount();

        List<KeyResult> results = runInParallel(clients, index -> request(name));

        assertEquals(clients, results.size());
        KeyResult first = results.get(0);
        for (KeyResult result : results) {
            assertArrayEquals(first.privateKeyPem(), result.privateKeyPem());
            assertArrayEquals(first.certPem(), result.certPem());
        }
        assertEquals(1, server.registry().generatedCount() - generatedBefore,
                "одновременные запросы одного имени должны запускать генерацию один раз");
    }

    @Test
    @DisplayName("Сотня одновременных клиентов с разными именами обслуживается")
    @Timeout(300)
    void servesHundredConcurrentClients() throws Exception {
        int clients = 120;
        String prefix = "load-" + System.nanoTime() + "-";

        List<KeyResult> results = runInParallel(clients, index -> request(prefix + index));

        assertEquals(clients, results.size());
        Map<String, Boolean> subjects = new ConcurrentHashMap<>();
        for (int i = 0; i < clients; i++) {
            X509Certificate certificate = parseCertificate(results.get(i).certPem());
            subjects.put(certificate.getSubjectX500Principal().getName(), Boolean.TRUE);
        }
        assertEquals(clients, subjects.size(), "каждому клиенту должен достаться свой сертификат");
    }

    @Test
    @DisplayName("Медленный клиент не мешает остальным")
    @Timeout(120)
    void slowClientDoesNotBlockOthers() throws Exception {
        String slowName = "slow-" + System.nanoTime();
        String fastName = "fast-" + System.nanoTime();

        AtomicReference<KeyResult> slowResult = new AtomicReference<>();
        CountDownLatch slowStarted = new CountDownLatch(1);
        Thread slow = Thread.ofVirtual().start(() -> {
            try {
                slowStarted.countDown();
                slowResult.set(new KeyClient("127.0.0.1", port, Duration.ofSeconds(3), false, 60_000).request(slowName));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(slowStarted.await(5, TimeUnit.SECONDS));
        long started = System.nanoTime();
        KeyResult fast = request(fastName);
        long fastMillis = (System.nanoTime() - started) / 1_000_000;

        assertNotNull(fast);
        assertTrue(fastMillis < 3_000,
                "быстрый клиент не должен ждать медленного, ожидание составило " + fastMillis + " мс");

        slow.join();
        assertNotNull(slowResult.get(), "медленный клиент тоже должен получить ключ");
    }

    @Test
    @DisplayName("Аварийное завершение клиента не ломает сервер")
    @Timeout(120)
    void abortedClientDoesNotBreakServer() throws Exception {
        String abortedName = "aborted-" + System.nanoTime();

        // Клиент уходит, не читая ответ.
        assertNull(new KeyClient("127.0.0.1", port, Duration.ZERO, true, 60_000).request(abortedName));

        // Сервер продолжает работать и сохраняет уже сгенерированный ключ.
        KeyResult afterCrash = request("after-crash-" + System.nanoTime());
        assertNotNull(afterCrash);

        KeyResult repeated = request(abortedName);
        assertNotNull(repeated);
        assertEquals("CN=" + abortedName, parseCertificate(repeated.certPem()).getSubjectX500Principal().getName());
    }

    @Test
    @DisplayName("Пустое имя приводит к ответу с ошибкой")
    @Timeout(60)
    void emptyNameIsRejected() {
        IOException error = assertThrows(IOException.class, () -> request(" "));
        assertTrue(error.getMessage().contains("Пустое имя"), "получено: " + error.getMessage());
    }

    /** Запускает задачи в виртуальных нитях и собирает результаты. */
    private static List<KeyResult> runInParallel(int count, ThrowingFunction task) throws Exception {
        List<KeyResult> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(count);
        AtomicInteger index = new AtomicInteger();

        for (int i = 0; i < count; i++) {
            int current = index.getAndIncrement();
            Thread.ofVirtual().name("test-client-" + current).start(() -> {
                try {
                    ready.countDown();
                    start.await();
                    results.add(task.apply(current));
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS), "клиенты не успели стартовать");
        start.countDown();
        assertTrue(done.await(280, TimeUnit.SECONDS), "клиенты не дождались ответа");
        if (!errors.isEmpty()) {
            throw new AssertionError("ошибки у " + errors.size() + " клиентов, первая: " + errors.get(0), errors.get(0));
        }
        return results;
    }

    @FunctionalInterface
    private interface ThrowingFunction {
        KeyResult apply(int index) throws Exception;
    }

    private static X509Certificate parseCertificate(byte[] pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem));
    }

    private static PrivateKey parsePrivateKey(byte[] pem) throws Exception {
        String text = new String(pem, StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(text);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
