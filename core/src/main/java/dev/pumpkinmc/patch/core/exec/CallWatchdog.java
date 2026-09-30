package dev.pumpkinmc.patch.core.exec;

/**
 * Stops a guest call that exceeds its budget by interrupting the calling thread, which the runtime
 * turns into a trap.
 *
 * <p>The calling thread is the Minecraft client thread, and an interrupt that escapes a guest call
 * would break Minecraft's blocking I/O. So an interrupt is only ever delivered while guest code is
 * on the stack. If the budget runs out during a host import, delivery waits until the import returns
 * to the guest. {@link #disarm()} runs after the call has ended and clears the flag, and no interrupt
 * can be issued for that call afterwards because arming, firing, and disarming share one lock.
 */
public final class CallWatchdog implements AutoCloseable {
    private final Object lock = new Object();
    private final Thread thread;
    private Thread target;
    private long deadline;
    private boolean inHost;
    private boolean fired;
    private long firedAt;
    private boolean closed;

    public CallWatchdog() {
        thread = new Thread(this::run, "PumpkinPatch-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    /** Starts the budget for a call on the current thread. */
    public void arm(long budgetNanos) {
        synchronized (lock) {
            target = Thread.currentThread();
            deadline = System.nanoTime() + budgetNanos;
            inHost = false;
            fired = false;
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
            target = null;
            result = fired ? firedAt : 0;
            fired = false;
        }
        Thread.interrupted();
        return result;
    }

    /** Called when a guest enters a host import. */
    public void enterHost() {
        synchronized (lock) {
            if (target == Thread.currentThread()) {
                inHost = true;
            }
        }
        // An interrupt delivered just before the guest called in is held back while host code runs.
        Thread.interrupted();
    }

    /** Called when a host import returns to the guest. */
    public void exitHost() {
        boolean redeliver;
        synchronized (lock) {
            if (target != Thread.currentThread()) {
                return;
            }
            inHost = false;
            redeliver = fired;
        }
        if (redeliver) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        synchronized (lock) {
            while (!closed) {
                try {
                    if (target == null || fired) {
                        lock.wait();
                        continue;
                    }
                    long wait = deadline - System.nanoTime();
                    if (wait > 0) {
                        lock.wait(wait / 1_000_000, (int) (wait % 1_000_000));
                        continue;
                    }
                    fired = true;
                    firedAt = System.nanoTime();
                    if (!inHost) {
                        target.interrupt();
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
