package org.example.server;


import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Нить приёма соединений: ждёт события OP_ACCEPT на слушающем сокете и по очереди
 * (round-robin) раздаёт принятые соединения нитям ввода-вывода.
 */
@Slf4j
final class Acceptor implements Runnable {

    private final ServerSocketChannel serverChannel;
    private final Selector selector;
    private final List<IoWorker> workers;
    private final AtomicInteger next = new AtomicInteger();

    private volatile boolean running = true;
    private Thread thread;

    Acceptor(ServerSocketChannel serverChannel, List<IoWorker> workers) throws IOException {
        this.serverChannel = serverChannel;
        this.workers = workers;
        this.selector = Selector.open();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    }

    void start() {
        thread = new Thread(this, "acceptor");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        while (running) {
            try {
                selector.select();
            } catch (ClosedSelectorException e) {
                break;
            } catch (IOException e) {
                log.warn("Ошибка selector акцептора: {}", e.getMessage());
                continue;
            }
            if (!running) {
                break;
            }
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while (keys.hasNext()) {
                keys.next();
                keys.remove();
                acceptAll();
            }
        }
        try {
            selector.close();
        } catch (IOException ignored) {
            // нечего делать
        }
    }

    private void acceptAll() {
        while (true) {
            SocketChannel channel;
            try {
                channel = serverChannel.accept();
            } catch (IOException e) {
                log.warn("Не удалось принять соединение: {}", e.getMessage());
                return;
            }
            if (channel == null) {
                return;
            }
            int index = Math.floorMod(next.getAndIncrement(), workers.size());
            workers.get(index).submit(channel);
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
}
