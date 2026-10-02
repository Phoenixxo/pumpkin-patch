package dev.pumpkinmc.patch.core.host;

import dev.pumpkinmc.patch.core.component.ComponentInstance;
import dev.pumpkinmc.patch.core.diag.FaultRecord;
import dev.pumpkinmc.patch.core.host.HostServices.CallContext;
import dev.pumpkinmc.patch.core.host.HostServices.CallPhase;
import dev.pumpkinmc.patch.core.runtime.Runtime.GuestInitError;
import dev.pumpkinmc.patch.core.runtime.Runtime.GuestInstance;
import dev.pumpkinmc.patch.core.runtime.Runtime.GuestTrap;
import dev.pumpkinmc.patch.core.runtime.Runtime.TrapKind;
import java.lang.management.ManagementFactory;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only caller of guest exports. A call runs on the client thread, or on a worker against a view
 * snapshot. Either way its outcome is finished on the client thread, which is where faults are
 * recorded and perf is kept.
 */
public final class GuestCaller {
    private static final Logger LOG = LoggerFactory.getLogger("PumpkinPatch");

    @FunctionalInterface
    public interface Call<T> {
        T run(GuestInstance guest) throws GuestTrap, GuestInitError;
    }

    /** What one call did, measured on the thread that ran it. */
    public record Outcome<T>(
            ComponentInstance instance,
            CallPhase phase,
            String export,
            T result,
            Throwable failure,
            long elapsedNanos,
            long allocatedBytes,
            long conversionNanos,
            long firedAt,
            long endNanos) {}

    private static final com.sun.management.ThreadMXBean THREADS =
            ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean t
                            && t.isThreadAllocatedMemorySupported()
                    ? t
                    : null;

    private final HostServices host;
    private final Consumer<FaultRecord> onFault;

    public GuestCaller(HostServices host, Consumer<FaultRecord> onFault) {
        this.host = host;
        this.onFault = onFault;
    }

    /**
     * Calls one export on the client thread under the call budget. Returns empty if the call failed,
     * in which case the outbox is discarded and the instance is {@code FAULTED}.
     */
    public <T> Optional<T> call(
            ComponentInstance instance, CallPhase phase, String export, long budgetNanos, Call<T> call) {
        host.checkThread();
        if (instance.guest() == null) {
            return Optional.empty();
        }
        return finish(run(instance, phase, export, budgetNanos, null, call));
    }

    /**
     * Starts one export on a worker against {@code view}. Pass the outcome to {@link #finish} on the
     * client thread once it completes.
     */
    public <T> CompletableFuture<Outcome<T>> submit(
            ComponentInstance instance, CallPhase phase, String export, long budgetNanos, ViewSnapshot view,
            Call<T> call) {
        host.checkThread();
        return CompletableFuture.supplyAsync(
                () -> run(instance, phase, export, budgetNanos, view, call), host.workers());
    }

    /** Runs one export on the current thread. Records nothing outside the instance. */
    private <T> Outcome<T> run(
            ComponentInstance instance, CallPhase phase, String export, long budgetNanos, ViewSnapshot view,
            Call<T> call) {
        CallContext outer = host.context();
        if (outer != null) {
            throw new IllegalStateException("HOST_REENTRY: " + export + " on " + instance.id() + " while "
                    + outer.instance().id() + " is executing");
        }
        GuestInstance guest = instance.guest();
        instance.outbox().clear();
        host.enterCall(new CallContext(instance, phase, view));
        T result = null;
        Throwable failure = null;
        long allocBefore = THREADS != null ? THREADS.getCurrentThreadAllocatedBytes() : 0;
        long start = System.nanoTime();
        host.watchdog.arm(budgetNanos);
        long end;
        long firedAt;
        long allocated;
        try {
            result = call.run(guest);
        } catch (GuestTrap | GuestInitError | RuntimeException | StackOverflowError e) {
            failure = e;
        } finally {
            end = System.nanoTime();
            allocated = THREADS != null ? THREADS.getCurrentThreadAllocatedBytes() - allocBefore : -1;
            firedAt = host.watchdog.disarm();
            host.exitCall();
        }
        return new Outcome<>(instance, phase, export, result, failure, end - start, allocated,
                guest.takeConversionNanos(), firedAt, end);
    }

    /**
     * Records a call's measurements and, if it failed, its fault. Returns the result, or empty if
     * the call failed. Client thread only.
     */
    public <T> Optional<T> finish(Outcome<T> o) {
        host.checkThread();
        ComponentInstance instance = o.instance();
        instance.calls++;
        instance.guestNanos += o.elapsedNanos();
        String key = o.export() + "/" + instance.id();
        host.perf.record("guest." + o.export(), o.elapsedNanos());
        host.perf.record("guest." + key, o.elapsedNanos());
        if (o.allocatedBytes() >= 0) {
            host.perf.value("alloc-bytes." + key, o.allocatedBytes());
        }
        if (o.conversionNanos() > 0) {
            host.perf.record("convert." + key, o.conversionNanos());
        }
        if (o.firedAt() != 0) {
            host.perf.record("watchdog.stop-latency", o.endNanos() - o.firedAt());
            host.perf.record("watchdog.call-duration", o.elapsedNanos());
        }
        if (o.failure() != null) {
            instance.outbox().clear();
            fail(instance, o.phase(), o.failure(), o.firedAt() != 0, o.elapsedNanos());
            return Optional.empty();
        }
        return Optional.ofNullable(o.result());
    }

    private void fail(ComponentInstance instance, CallPhase phase, Throwable t, boolean timedOut, long elapsed) {
        String kind;
        String message;
        if (t instanceof GuestInitError e) {
            kind = "INIT_ERROR";
            message = e.getMessage();
        } else if (t instanceof GuestTrap trap) {
            kind = timedOut || trap.kind() == TrapKind.BUDGET_EXHAUSTED ? "TIMEOUT" : "TRAP_" + trap.kind();
            message = trap.getMessage();
        } else {
            kind = timedOut ? "TIMEOUT" : "HOST_PANIC";
            message = t.toString();
        }
        if (timedOut) {
            message += String.format(" (stopped after %.1f ms)", elapsed / 1e6);
        }
        var record = new FaultRecord(
                instance.id(), instance.entry().manifest().version(), instance.sessionId(), kind, message,
                phase.name(), System.currentTimeMillis());
        LOG.error("[PumpkinPatch] {} faulted in {}: {} {}", instance.id(), phase, kind, message,
                kind.equals("HOST_PANIC") ? t : null);
        instance.fault(record);
        onFault.accept(record);
    }
}
