package org.example.server;


import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Нить ввода-вывода: обслуживает множество клиентских соединений через один
 * {@link Selector}. Соединения не имеют собственных нитей ОС, поэтому сотни
 * (в том числе «медленных») клиентов обслуживаются несколькими нитями.
 *
 * <p>Нить разбирает две очереди:
 * <ul>
 *   <li>{@code pending} — новые соединения от нити акцептора;</li>
 *   <li>{@code ready} — соединения с готовым ответом, поставленные генерирующими нитями
 *       (та самая «очередь на передачу ключа»).</li>
 * </ul>
 */
@Slf4j
final class IoWorker implements Runnable {

    private final String name;
    private final Selector selector;
    private final KeyRegistry registry;
    private final Queue<SocketChannel> pending = new ConcurrentLinkedQueue<>();
    private final Queue<ClientConnection> ready = new ConcurrentLinkedQueue<>();
    private final AtomicInteger activeConnections = new AtomicInteger();

    private volatile boolean running = true;
    private Thread thread;

    IoWorker(String name, KeyRegistry registry) throws IOException {
        this.name = name;
        this.registry = registry;
        this.selector = Selector.open();
    }

    void start() {
        thread = new Thread(this, name);
        thread.setDaemon(true);
        thread.start();
    }

    /** Передаёт новое соединение этой нити (вызывается нитью акцептора). */
    void submit(SocketChannel channel) {
        pending.add(channel);
        selector.wakeup();
    }

    /** Ставит соединение с готовым ответом в очередь передачи (вызывается генерирующей нитью). */
    void resultReady(ClientConnection connection) {
        ready.add(connection);
        selector.wakeup();
    }

    int activeConnections() {
        return activeConnections.get();
    }

    void connectionClosed() {
        activeConnections.decrementAndGet();
    }

    @Override
    public void run() {
        log.debug("Нить ввода-вывода {} запущена", name);
        while (running) {
            try {
                selector.select();
            } catch (ClosedSelectorException e) {
                break;
            } catch (IOException e) {
                log.warn("Ошибка selector в {}: {}", name, e.getMessage());
                continue;
            }
            if (!running) {
                break;
            }
            registerPending();
            deliverReady();
            processSelectedKeys();
        }
        shutdownConnections();
        log.debug("Нить ввода-вывода {} остановлена", name);
    }

    private void registerPending() {
        SocketChannel channel;
        while ((channel = pending.poll()) != null) {
            SocketAddress remote = null;
            try {
                remote = channel.getRemoteAddress();
                channel.configureBlocking(false);
                channel.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);
                ClientConnection connection = new ClientConnection(channel, this, registry, remote);
                SelectionKey key = channel.register(selector, SelectionKey.OP_READ, connection);
                connection.attach(key);
                activeConnections.incrementAndGet();
                log.debug("Соединение {} принято нитью {}", remote, name);
            } catch (IOException e) {
                log.warn("Не удалось зарегистрировать соединение {}: {}", remote, e.getMessage());
                closeQuietly(channel);
            }
        }
    }

    private void deliverReady() {
        ClientConnection connection;
        while ((connection = ready.poll()) != null) {
            connection.onResultReady();
        }
    }

    private void processSelectedKeys() {
        Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
        while (keys.hasNext()) {
            SelectionKey key = keys.next();
            keys.remove();
            if (!key.isValid()) {
                continue;
            }
            ClientConnection connection = (ClientConnection) key.attachment();
            try {
                if (key.isReadable()) {
                    connection.onReadable();
                }
                if (key.isValid() && key.isWritable()) {
                    connection.onWritable();
                }
            } catch (IOException e) {
                // Обрыв соединения — обычная ситуация: клиент мог завершиться аварийно.
                connection.close("ошибка ввода-вывода: " + e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Ошибка обработки соединения", e);
                connection.close("внутренняя ошибка");
            }
        }
    }

    private void shutdownConnections() {
        for (SelectionKey key : selector.keys()) {
            Object attachment = key.attachment();
            if (attachment instanceof ClientConnection connection) {
                connection.close("сервер остановлен");
            }
        }
        try {
            selector.close();
        } catch (IOException e) {
            log.debug("Ошибка закрытия selector {}: {}", name, e.getMessage());
        }
    }

    void stop() {
        running = false;
        selector.wakeup();
    }

    void join(long millis) throws InterruptedException {
        if (thread != null) {
            thread.join(millis);
        }
    }

    private static void closeQuietly(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // нечего делать
        }
    }
}
