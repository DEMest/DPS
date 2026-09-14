package org.example.server;

import org.example.ca.CertificateAuthority;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Микросервис генерации ключей.
 *
 * <p>Схема работы:
 * <pre>
 *   acceptor (Selector, OP_ACCEPT)
 *        |  round-robin
 *        v
 *   io-N (Selector, OP_READ/OP_WRITE)  --очередь запросов-->  пул keygen-N
 *        ^                                                          |
 *        +------------ очередь готовых ответов ---------------------+
 * </pre>
 * Генерирующие нити никогда не блокируются на передаче результата: они кладут готовый
 * ответ в очередь нити ввода-вывода и будят её selector.
 */
@Slf4j
public final class KeyServer implements AutoCloseable {

    private final ServerConfig config;
    private final KeyRegistry registry;
    private final List<IoWorker> workers = new ArrayList<>();

    private ServerSocketChannel serverChannel;
    private Acceptor acceptor;
    private int boundPort;

    public KeyServer(ServerConfig config, CertificateAuthority ca) {
        this.config = config;
        this.registry = new KeyRegistry(ca, config.generatorThreads(), config.keySize());
    }

    /** Открывает слушающий сокет и запускает нити. */
    public void start() throws IOException {
        serverChannel = ServerSocketChannel.open();
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, Boolean.TRUE);
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(config.bindAddress(), config.port()), config.backlog());
        boundPort = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();

        for (int i = 0; i < config.ioThreads(); i++) {
            IoWorker worker = new IoWorker("io-" + (i + 1), registry);
            workers.add(worker);
            worker.start();
        }
        acceptor = new Acceptor(serverChannel, workers);
        acceptor.start();

        log.info("Сервер слушает {}:{}; генерирующих нитей: {}, нитей ввода-вывода: {}, длина ключа: {} бит",
                config.bindAddress(), boundPort, config.generatorThreads(), config.ioThreads(), config.keySize());
    }

    /** Фактический порт (важно при запуске с port=0). */
    public int port() {
        return boundPort;
    }

    public KeyRegistry registry() {
        return registry;
    }

    /** Число соединений, обслуживаемых сейчас нитями ввода-вывода. */
    public int activeConnections() {
        int total = 0;
        for (IoWorker worker : workers) {
            total += worker.activeConnections();
        }
        return total;
    }

    @Override
    public void close() {
        if (acceptor != null) {
            acceptor.stop();
        }
        for (IoWorker worker : workers) {
            worker.stop();
        }
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException e) {
            log.debug("Ошибка закрытия слушающего сокета: {}", e.getMessage());
        }
        try {
            if (acceptor != null) {
                acceptor.join(2000);
            }
            for (IoWorker worker : workers) {
                worker.join(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        registry.close();
        log.info("Сервер остановлен. Выдано ключей: {}, имён в памяти: {}",
                registry.generatedCount(), registry.cachedNames());
    }
}
