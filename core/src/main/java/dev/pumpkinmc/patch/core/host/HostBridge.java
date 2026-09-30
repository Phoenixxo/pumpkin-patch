package dev.pumpkinmc.patch.core.host;

import dev.pumpkinmc.patch.core.capability.Capability;
import dev.pumpkinmc.patch.core.component.ComponentInstance;
import dev.pumpkinmc.patch.core.component.ComponentInstance.NetSend;
import dev.pumpkinmc.patch.core.host.HostServices.CallPhase;
import dev.pumpkinmc.patch.core.model.HostError;
import dev.pumpkinmc.patch.core.model.HostError.Code;
import dev.pumpkinmc.patch.core.model.Model.EntitySnapshot;
import dev.pumpkinmc.patch.core.model.Model.PlayerSnapshot;
import dev.pumpkinmc.patch.core.model.Model.WorldRef;
import dev.pumpkinmc.patch.core.runtime.Runtime.HostImports;
import dev.pumpkinmc.patch.core.runtime.Runtime.HudImports;
import dev.pumpkinmc.patch.core.runtime.Runtime.LogImports;
import dev.pumpkinmc.patch.core.runtime.Runtime.NetImports;
import dev.pumpkinmc.patch.core.runtime.Runtime.ViewImports;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The host imports for one instance. Phase checks, capability denials, quotas, validation, and
 * outbox staging all live here, so every runtime and WIT version gets the same enforcement.
 */
public final class HostBridge implements HostImports, LogImports, NetImports, ViewImports, HudImports {
    private final HostServices host;
    private final ComponentInstance instance;
    private final Logger guestLog;

    public HostBridge(HostServices host, ComponentInstance instance) {
        this.host = host;
        this.instance = instance;
        this.guestLog = LoggerFactory.getLogger("pumpkin:" + instance.id());
    }

    @Override
    public LogImports log() {
        return this;
    }

    @Override
    public NetImports net() {
        return this;
    }

    @Override
    public ViewImports view() {
        return this;
    }

    @Override
    public HudImports hud() {
        return this;
    }

    /** Thread and re-entry check, and the watchdog's host-code window. */
    private long enter() {
        host.checkThread();
        if (host.executing != instance) {
            throw new IllegalStateException("HOST_REENTRY: " + instance.id() + " called an import while "
                    + (host.executing == null ? "no instance" : host.executing.id()) + " was executing");
        }
        host.watchdog.enterHost();
        return System.nanoTime();
    }

    private void exit(long start, String name) {
        long elapsed = System.nanoTime() - start;
        host.perf.record("host." + name, elapsed);
        host.perf.record("host." + name + "/" + instance.id(), elapsed);
        host.watchdog.exitHost();
    }

    @Override
    public void log(Level level, String message) {
        long t = enter();
        try {
            if (++instance.logLinesThisTick > host.config.maxLogLinesPerTick()) {
                return;
            }
            String line = "[pumpkin:" + instance.id() + "] "
                    + (message.length() > 8192 ? message.substring(0, 8192) : message);
            switch (level) {
                case TRACE -> guestLog.trace(line);
                case DEBUG -> guestLog.debug(line);
                case INFO -> guestLog.info(line);
                case WARN -> guestLog.warn(line);
                case ERROR -> guestLog.error(line);
            }
        } finally {
            exit(t, "log");
        }
    }

    @Override
    public void send(String channel, byte[] payload) throws HostError {
        long t = enter();
        try {
            if (host.phase != CallPhase.EVENTS && host.phase != CallPhase.SHUTDOWN) {
                throw HostError.of(Code.UNAVAILABLE);
            }
            if (!instance.granted(Capability.NET)) {
                throw HostError.of(Code.DENIED);
            }
            if (instance.route() < 0) {
                throw HostError.of(Code.UNAVAILABLE);
            }
            int index = instance.channels().indexOf(channel);
            if (index < 0) {
                throw new HostError(Code.INVALID_ARGUMENT, "undeclared channel " + channel);
            }
            int max = computeMaxPayload();
            if (payload.length > max) {
                throw new HostError(Code.LIMIT_EXCEEDED, "payload " + payload.length + " > " + max);
            }
            if (instance.sentThisTick + 1 > host.config.maxSendsPerTick()
                    || instance.bytesThisTick + payload.length > host.config.maxSendBytesPerTick()) {
                throw new HostError(Code.LIMIT_EXCEEDED, "per-tick send quota");
            }
            instance.sentThisTick++;
            instance.bytesThisTick += payload.length;
            instance.outbox().add(new NetSend(index, payload));
        } finally {
            exit(t, "net.send");
        }
    }

    @Override
    public int maxPayloadBytes() {
        long t = enter();
        try {
            return computeMaxPayload();
        } finally {
            exit(t, "net.max-payload");
        }
    }

    private int computeMaxPayload() {
        return Math.min(host.config.maxOutboundPayload(), host.ports.transport().maxOutboundFrameBytes() - 16);
    }

    private void checkView() throws HostError {
        if (host.phase == CallPhase.SHUTDOWN) {
            throw HostError.of(Code.UNAVAILABLE);
        }
        if (!instance.granted(Capability.VIEW)) {
            throw HostError.of(Code.DENIED);
        }
    }

    @Override
    public PlayerSnapshot localPlayer() throws HostError {
        long t = enter();
        try {
            checkView();
            return host.ports.playerView().localPlayer(host.worldEpoch())
                    .orElseThrow(() -> HostError.of(Code.UNAVAILABLE));
        } finally {
            exit(t, "view.local-player");
        }
    }

    @Override
    public WorldRef currentWorld() throws HostError {
        long t = enter();
        try {
            checkView();
            return host.ports.playerView().dimension()
                    .map(d -> new WorldRef(d, host.worldEpoch()))
                    .orElseThrow(() -> HostError.of(Code.UNAVAILABLE));
        } finally {
            exit(t, "view.current-world");
        }
    }

    @Override
    public List<EntitySnapshot> nearbyEntities(double radius, int max) throws HostError {
        long t = enter();
        try {
            checkView();
            if (!(radius >= 0 && radius <= 256)) {
                throw new HostError(Code.INVALID_ARGUMENT, "radius must be within 0..256");
            }
            int clamped = Math.max(0, Math.min(max, host.config.maxNearbyEntities()));
            List<EntitySnapshot> found = host.ports.playerView().entitiesNear(radius, clamped);
            host.perf.value("count.nearby-entities/" + instance.id(), found.size());
            return found;
        } finally {
            exit(t, "view.nearby-entities");
        }
    }

    @Override
    public List<Integer> measureText(List<String> texts) throws HostError {
        long t = enter();
        try {
            if (host.phase != CallPhase.RENDER) {
                throw HostError.of(Code.UNAVAILABLE);
            }
            if (!instance.granted(Capability.HUD)) {
                throw HostError.of(Code.DENIED);
            }
            if (texts.size() > host.config.maxDrawCommands()) {
                throw new HostError(Code.LIMIT_EXCEEDED, "at most " + host.config.maxDrawCommands() + " texts");
            }
            List<Integer> out = new ArrayList<>(texts.size());
            for (String s : texts) {
                out.add(host.ports.hudCanvas().measureText(s));
            }
            return out;
        } finally {
            exit(t, "hud.measure-text");
        }
    }
}
