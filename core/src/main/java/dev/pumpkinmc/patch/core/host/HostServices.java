package dev.pumpkinmc.patch.core.host;

import dev.pumpkinmc.patch.core.PatchConfig;
import dev.pumpkinmc.patch.core.component.ComponentInstance;
import dev.pumpkinmc.patch.core.diag.Perf;
import dev.pumpkinmc.patch.core.exec.CallWatchdog;
import dev.pumpkinmc.patch.core.port.Ports;

/** The host-wide services every instance's bridge and calls share. Owned by the facade. */
public final class HostServices {
    public final PatchConfig config;
    public final Ports ports;
    public final Perf perf;
    public final CallWatchdog watchdog;
    private final Thread clientThread;

    /** The instance currently executing a guest export, if any. */
    ComponentInstance executing;

    CallPhase phase;
    int worldEpoch = 1;

    public HostServices(PatchConfig config, Ports ports, Perf perf, CallWatchdog watchdog, Thread clientThread) {
        this.config = config;
        this.ports = ports;
        this.perf = perf;
        this.watchdog = watchdog;
        this.clientThread = clientThread;
    }

    public void checkThread() {
        if (Thread.currentThread() != clientThread) {
            throw new IllegalStateException("Pumpkin Patch guest code must run on the client thread, not "
                    + Thread.currentThread().getName());
        }
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
