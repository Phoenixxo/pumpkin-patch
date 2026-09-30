package dev.pumpkinmc.patch.core.dispatch;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Multi-producer, single-consumer, bounded. The only cross-thread structure in a session. */
public final class InboundQueue {
    private static final int CAPACITY = 4096;

    private final ConcurrentLinkedQueue<Inbound> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();

    /** Callable from any thread. */
    public void offer(Inbound item) {
        if (size.incrementAndGet() > CAPACITY) {
            size.decrementAndGet();
            dropped.incrementAndGet();
            return;
        }
        queue.offer(item);
    }

    /** Client thread only. Items offered while draining wait for the next drain. */
    public void drain(Consumer<Inbound> sink) {
        int n = size.get();
        for (int i = 0; i < n; i++) {
            Inbound item = queue.poll();
            if (item == null) {
                return;
            }
            size.decrementAndGet();
            sink.accept(item);
        }
    }

    public void clear() {
        queue.clear();
        size.set(0);
    }

    public long dropped() {
        return dropped.get();
    }
}
