package dev.pumpkinmc.patch.core.host;

import dev.pumpkinmc.patch.core.component.ComponentInstance;
import dev.pumpkinmc.patch.core.diag.FaultRecord;
import dev.pumpkinmc.patch.core.host.HostServices.CallPhase;
import dev.pumpkinmc.patch.core.runtime.Runtime.GuestInitError;
import dev.pumpkinmc.patch.core.runtime.Runtime.GuestInstance;
import dev.pumpkinmc.patch.core.runtime.Runtime.GuestTrap;
import dev.pumpkinmc.patch.core.runtime.Runtime.TrapKind;
import java.lang.management.ManagementFactory;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The only caller of guest exports. */
public final class GuestCaller {
    private static final Logger LOG = LoggerFactory.getLogger("PumpkinPatch");

    @FunctionalInterface
    public interface Call<T> {
        T run(GuestInstance guest) throws GuestTrap, GuestInitError;
    }

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
     * Calls one export under the call budget. Returns empty if the call failed, in which case the
     * outbox is discarded and the instance is {@code FAULTED}.
     */
    public <T> Optional<T> call(
            ComponentInstance instance, CallPhase phase, String export, long budgetNanos, Call<T> call) {
        host.checkThread();
        if (host.executing != null) {
            throw new IllegalStateException("HOST_REENTRY: " + export + " on " + instance.id() + " while "
                    + host.executing.id() + " is executing");
        }
        GuestInstance guest = instance.guest();
        if (guest == null) {
            return Optional.empty();
        }
        instance.outbox().clear();
        host.executing = instance;
        host.phase = phase;
        T result = null;
        Throwable failure = null;
        long allocBefore = THREADS != null ? THREADS.getCurrentThreadAllocatedBytes() : 0;
        long start = System.nanoTime();
        host.watchdog.arm(budgetNanos);
        try {
            result = call.run(guest);
        } catch (GuestTrap | GuestInitError | RuntimeException | StackOverflowError e) {
            failure = e;
        } finally {
            long end = System.nanoTime();
            long allocated = THREADS != null ? THREADS.getCurrentThreadAllocatedBytes() - allocBefore : -1;
            long firedAt = host.watchdog.disarm();
            host.executing = null;
            host.phase = null;
            long elapsed = end - start;
            instance.calls++;
            instance.guestNanos += elapsed;
            String key = export + "/" + instance.id();
            host.perf.record("guest." + export, elapsed);
            host.perf.record("guest." + key, elapsed);
            if (allocated >= 0) {
                host.perf.value("alloc-bytes." + key, allocated);
            }
            long conversion = guest.takeConversionNanos();
            if (conversion > 0) {
                host.perf.record("convert." + key, conversion);
            }
            if (firedAt != 0) {
                host.perf.record("watchdog.stop-latency", end - firedAt);
                host.perf.record("watchdog.call-duration", elapsed);
            }
            if (failure != null) {
                fail(instance, phase, failure, firedAt != 0, elapsed);
            }
        }
        return failure == null ? Optional.ofNullable(result) : Optional.empty();
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
