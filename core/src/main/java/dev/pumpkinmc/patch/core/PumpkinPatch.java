package dev.pumpkinmc.patch.core;

import dev.pumpkinmc.patch.core.catalog.Catalog;
import dev.pumpkinmc.patch.core.catalog.CatalogEntry;
import dev.pumpkinmc.patch.core.component.ComponentInstance;
import dev.pumpkinmc.patch.core.diag.FaultRecord;
import dev.pumpkinmc.patch.core.diag.Perf;
import dev.pumpkinmc.patch.core.dispatch.Inbound;
import dev.pumpkinmc.patch.core.exec.CallWatchdog;
import dev.pumpkinmc.patch.core.host.GuestCaller;
import dev.pumpkinmc.patch.core.host.HostServices;
import dev.pumpkinmc.patch.core.manifest.Manifest.ActionDecl;
import dev.pumpkinmc.patch.core.model.Model.FrameInfo;
import dev.pumpkinmc.patch.core.port.Ports;
import dev.pumpkinmc.patch.core.runtime.Runtime.ComponentRuntime;
import dev.pumpkinmc.patch.core.session.Session;
import dev.pumpkinmc.patch.core.session.SessionManager;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** The facade the platform edge uses. All state hangs off one instance; core has no statics. */
public final class PumpkinPatch implements AutoCloseable {
    private final PatchConfig config;
    private final ComponentRuntime runtime;
    private final Ports ports;
    private final Catalog catalog = new Catalog();
    private final Perf perf = new Perf();
    private final CallWatchdog watchdog = new CallWatchdog();
    private final ExecutorService workers;
    private final Deque<FaultRecord> faults = new ArrayDeque<>();
    private final SessionManager sessions;

    private PumpkinPatch(PatchConfig config, ComponentRuntime runtime, Ports ports, Thread clientThread) {
        this.config = config;
        this.runtime = runtime;
        this.ports = ports;
        this.workers = config.workers() ? newWorkerPool() : null;
        var host = new HostServices(config, ports, perf, watchdog, clientThread, workers);
        var caller = new GuestCaller(host, this::recordFault);
        this.sessions = new SessionManager(catalog, host, caller);
    }

    /**
     * Daemon threads for updates, at most one per instance at a time. A few are enough: an update
     * only waits on its own guest code.
     */
    private static ExecutorService newWorkerPool() {
        int n = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        var count = new AtomicInteger();
        return Executors.newFixedThreadPool(n, task -> {
            Thread t = new Thread(task, "PumpkinPatch-worker-" + count.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * @param clientThread the thread that drives the patch. With {@link PatchConfig#workers()}
     *     guest updates run on worker threads, and everything else still runs here.
     */
    public static PumpkinPatch create(PatchConfig config, ComponentRuntime runtime, Ports ports, Thread clientThread) {
        return new PumpkinPatch(config, runtime, ports, clientThread);
    }

    private void recordFault(FaultRecord f) {
        synchronized (faults) {
            faults.addFirst(f);
            while (faults.size() > 20) {
                faults.removeLast();
            }
        }
        ports.faults().report(f);
    }

    public void discover(Path modsDir) {
        catalog.discover(modsDir);
    }

    public void compileInBackground() {
        catalog.compileInBackground(runtime, config.limits());
    }

    public void compileNow() {
        catalog.compileAll(runtime, config.limits());
    }

    public Catalog catalog() {
        return catalog;
    }

    public ComponentRuntime runtime() {
        return runtime;
    }

    public Perf perf() {
        return perf;
    }

    /** Key actions the manifests declare, as (mod id, action). */
    public List<DeclaredAction> declaredActions() {
        List<DeclaredAction> out = new ArrayList<>();
        for (CatalogEntry e : catalog.entries()) {
            if (e.manifest() != null && e.status() != CatalogEntry.Status.REJECTED) {
                for (ActionDecl a : e.manifest().actions()) {
                    out.add(new DeclaredAction(e.id(), e.manifest().name(), a));
                }
            }
        }
        return out;
    }

    public record DeclaredAction(String modId, String modName, ActionDecl action) {}

    public void onRefuse(Consumer<String> handler) {
        sessions.onRefuse(handler);
    }

    public void onConfigurationStart() {
        sessions.onConfigurationStart();
    }

    public void onFrameReceived(byte[] frame) {
        sessions.onFrameReceived(frame);
    }

    public void onJoin() {
        sessions.onJoin();
    }

    public void onDisconnect() {
        sessions.onDisconnect(true);
    }

    public void onClientStopping() {
        sessions.onDisconnect(false);
    }

    public void enqueueAction(String modId, String actionId, boolean pressed) {
        sessions.enqueue(new Inbound.Action(modId, actionId, pressed, System.nanoTime()));
    }

    public void enqueueWorldChanged(String dimension) {
        sessions.enqueue(new Inbound.WorldChanged(dimension));
    }

    public PatchConfig config() {
        return config;
    }

    /**
     * Waits until no update is running on a worker, applying each one's outcome. For tests and
     * benchmarks that need an update's result in the same tick. Returns whether all finished.
     */
    public boolean awaitUpdates(Duration timeout) {
        return sessions.awaitUpdates(timeout.toNanos());
    }

    public void tick(long gameTick) {
        long t = System.nanoTime();
        sessions.tick(gameTick);
        perf.record("drain.tick", System.nanoTime() - t);
    }

    public void renderHud(FrameInfo frame) {
        long t = System.nanoTime();
        sessions.renderHud(frame);
        perf.record("drain.hud", System.nanoTime() - t);
    }

    /** Benchmark harness only: sends on a mod's route as if its component had. */
    public boolean sendAsMod(String modId, String channel, byte[] payload) {
        return sessions.sendAsMod(modId, channel, payload);
    }

    public Session session() {
        return sessions.current();
    }

    public List<ComponentInstance> instances() {
        Session s = sessions.current();
        return s == null ? List.of() : List.copyOf(s.instances());
    }

    public List<FaultRecord> faults() {
        synchronized (faults) {
            return List.copyOf(faults);
        }
    }

    @Override
    public void close() {
        watchdog.close();
        if (workers != null) {
            workers.shutdownNow();
        }
        runtime.close();
    }
}
