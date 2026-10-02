package dev.pumpkinmc.patch.core.host;

import dev.pumpkinmc.patch.core.PatchConfig;
import dev.pumpkinmc.patch.core.component.ComponentInstance;
import dev.pumpkinmc.patch.core.diag.Perf;
import dev.pumpkinmc.patch.core.exec.CallWatchdog;
import dev.pumpkinmc.patch.core.port.Ports;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;

/** The host-wide services every instance's bridge and calls share. Owned by the facade. */
public final class HostServices {
    /** The radius the first view snapshot covers, before any instance has asked for one. */
    private static final double DEFAULT_SNAPSHOT_RADIUS = 64;

    public final PatchConfig config;
    public final Ports ports;
    public final Perf perf;
    public final CallWatchdog watchdog;
    private final Thread clientThread;
    /** Runs updates off the client thread, or {@code null} when everything runs on it. */
    private final ExecutorService workers;

    /** The guest call in progress on each thread. */
    private final ThreadLocal<CallContext> context = new ThreadLocal<>();
    /** Work a worker needs done on the client thread, run at every tick and frame. */
    private final Queue<Runnable> clientTasks = new ConcurrentLinkedQueue<>();
    /** Text widths measured on the client thread, for workers. Cleared with each session. */
    private final Map<String, Integer> textWidths = new ConcurrentHashMap<>();
    private volatile double snapshotRadius = DEFAULT_SNAPSHOT_RADIUS;
    /** The last tick an instance read entities, so snapshots only collect them while they are used. */
    private volatile long entitiesReadTick = Long.MIN_VALUE / 2;
    private volatile int worldEpoch = 1;

    /**
     * One guest call. {@code view} is the snapshot a worker reads, or {@code null} on the client
     * thread, where the view imports read the game directly.
     */
    public record CallContext(ComponentInstance instance, CallPhase phase, ViewSnapshot view) {}

    public HostServices(
            PatchConfig config,
            Ports ports,
            Perf perf,
            CallWatchdog watchdog,
            Thread clientThread,
            ExecutorService workers) {
        this.config = config;
        this.ports = ports;
        this.perf = perf;
        this.watchdog = watchdog;
        this.clientThread = clientThread;
        this.workers = workers;
    }

    public void checkThread() {
        if (!onClientThread()) {
            throw new IllegalStateException("Pumpkin Patch guest code must run on the client thread, not "
                    + Thread.currentThread().getName());
        }
    }

    public boolean onClientThread() {
        return Thread.currentThread() == clientThread;
    }

    /** The worker pool, or {@code null} when updates run on the client thread. */
    public ExecutorService workers() {
        return workers;
    }

    public CallContext context() {
        return context.get();
    }

    void enterCall(CallContext call) {
        context.set(call);
    }

    void exitCall() {
        context.remove();
    }

    /** Queues work for the client thread. */
    public void onClientThread(Runnable task) {
        clientTasks.add(task);
    }

    /** Runs the work workers queued. Client thread only. */
    public void runClientTasks() {
        Runnable task;
        while ((task = clientTasks.poll()) != null) {
            task.run();
        }
    }

    public Map<String, Integer> textWidths() {
        return textWidths;
    }

    /** How far the next view snapshot reaches: the widest radius any instance asked for. */
    public double snapshotRadius() {
        return snapshotRadius;
    }

    void requestRadius(double radius) {
        if (radius > snapshotRadius) {
            snapshotRadius = Math.min(radius, 256);
        }
    }

    void noteEntitiesRead(long gameTick) {
        if (gameTick > entitiesReadTick) {
            entitiesReadTick = gameTick;
        }
    }

    /** Whether some instance read entities within the last {@code ticks} ticks before {@code gameTick}. */
    public boolean entitiesReadWithin(long gameTick, long ticks) {
        return gameTick - entitiesReadTick <= ticks;
    }

    public int worldEpoch() {
        return worldEpoch;
    }

    public void setWorldEpoch(int epoch) {
        worldEpoch = epoch;
    }

    public enum CallPhase {
        INIT,
        UPDATE,
        SHUTDOWN
    }
}
