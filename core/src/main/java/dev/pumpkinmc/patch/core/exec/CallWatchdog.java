package dev.pumpkinmc.patch.core.exec;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stops a guest call that exceeds its budget by interrupting the calling thread, which the runtime
 * turns into a trap. Calls on several threads are watched at once, each against its own deadline.
 *
 * <p>A calling thread may be the Minecraft client thread, and an interrupt that escapes a guest call
 * would break Minecraft's blocking I/O. So an interrupt is only ever delivered while guest code is
 * on the stack. If the budget runs out during a host import, delivery waits until the import returns
 * to the guest. {@link #disarm()} runs after the call has ended and clears the flag, and no interrupt
 * can be issued for that call afterwards because arming, firing, and disarming share one lock.
 *
 * <p>Time the JVM spent stopped for garbage collection during a call is not charged to it. A call on
 * a worker can be stopped by a collection another thread triggered, and the guest did nothing in
 * that time.
 */
public final class CallWatchdog implements AutoCloseable {
    private final Object lock = new Object();
    private final Thread thread;
    private final Map<Thread, Slot> slots = new HashMap<>();
    private boolean closed;

    /** One armed call. */
    private static final class Slot {
        long deadline;
        boolean inHost;
        boolean fired;
        long firedAt;
        /** When the budget was suspended, or 0 while it runs. */
        long suspendedAt;
        /** Collection pause time, in milliseconds, already counted against this slot. */
        long gcPauseMillis;

        Slot(long deadline, long gcPauseMillis) {
            this.deadline = deadline;
            this.gcPauseMillis = gcPauseMillis;
        }
    }

    /** The collectors whose time is stop-the-world pause, not concurrent work. */
    private static final List<GarbageCollectorMXBean> PAUSING_COLLECTORS =
            ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .filter(c -> !c.getName().contains("Concurrent") && !c.getName().contains("Cycles"))
                    .toList();

    /** Milliseconds the JVM has spent paused for collection so far. */
    private static long gcPauseMillis() {
        long total = 0;
        for (GarbageCollectorMXBean c : PAUSING_COLLECTORS) {
            total += Math.max(0, c.getCollectionTime());
        }
        return total;
    }

    public CallWatchdog() {
        thread = new Thread(this::run, "PumpkinPatch-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    /** Starts the budget for a call on the current thread. */
    public void arm(long budgetNanos) {
        synchronized (lock) {
            slots.put(Thread.currentThread(), new Slot(System.nanoTime() + budgetNanos, gcPauseMillis()));
            lock.notifyAll();
        }
    }

    /**
     * Ends the budget. Returns the time the watchdog fired, or 0 if it did not. The current thread's
     * interrupt flag is clear when this returns.
     */
    public long disarm() {
        long result;
        synchronized (lock) {
            Slot slot = slots.remove(Thread.currentThread());
            result = slot != null && slot.fired ? slot.firedAt : 0;
        }
        Thread.interrupted();
        return result;
    }

    /** Called when a guest enters a host import. */
    public void enterHost() {
        synchronized (lock) {
            Slot slot = slots.get(Thread.currentThread());
            if (slot != null) {
                slot.inHost = true;
            }
        }
        // An interrupt delivered just before the guest called in is held back while host code runs.
        Thread.interrupted();
    }

    /** Called when a host import returns to the guest. */
    public void exitHost() {
        boolean redeliver;
        synchronized (lock) {
            Slot slot = slots.get(Thread.currentThread());
            if (slot == null) {
                return;
            }
            slot.inHost = false;
            redeliver = slot.fired;
        }
        if (redeliver) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stops the current call's budget while a host import waits on another thread, so the guest is
     * not charged for the wait. {@link #resume()} moves the deadline out by the time spent.
     */
    public void suspend() {
        synchronized (lock) {
            Slot slot = slots.get(Thread.currentThread());
            if (slot != null && slot.suspendedAt == 0) {
                slot.suspendedAt = System.nanoTime();
            }
        }
    }

    public void resume() {
        synchronized (lock) {
            Slot slot = slots.get(Thread.currentThread());
            if (slot != null && slot.suspendedAt != 0) {
                slot.deadline += System.nanoTime() - slot.suspendedAt;
                slot.suspendedAt = 0;
                lock.notifyAll();
            }
        }
    }

    private void run() {
        synchronized (lock) {
            while (!closed) {
                try {
                    long now = System.nanoTime();
                    long wait = Long.MAX_VALUE;
                    for (var e : slots.entrySet()) {
                        Slot slot = e.getValue();
                        if (slot.fired || slot.suspendedAt != 0) {
                            continue;
                        }
                        long left = slot.deadline - now;
                        if (left <= 0) {
                            // Credit any collection pause since the slot was armed, then look again.
                            long paused = gcPauseMillis();
                            if (paused > slot.gcPauseMillis) {
                                slot.deadline += (paused - slot.gcPauseMillis) * 1_000_000L;
                                slot.gcPauseMillis = paused;
                                left = slot.deadline - now;
                            }
                        }
                        if (left <= 0) {
                            slot.fired = true;
                            slot.firedAt = now;
                            if (!slot.inHost) {
                                e.getKey().interrupt();
                            }
                        } else {
                            wait = Math.min(wait, left);
                        }
                    }
                    if (wait == Long.MAX_VALUE) {
                        lock.wait();
                    } else {
                        lock.wait(wait / 1_000_000, (int) (wait % 1_000_000));
                    }
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }
}
