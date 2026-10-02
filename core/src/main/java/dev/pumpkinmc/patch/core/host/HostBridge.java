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
import dev.pumpkinmc.patch.core.host.HostServices.CallContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /** Re-entry check, and the watchdog's host-code window. */
    private long enter() {
        CallContext call = host.context();
        if (call == null || call.instance() != instance) {
            throw new IllegalStateException("HOST_REENTRY: " + instance.id() + " called an import while "
                    + (call == null ? "no instance" : call.instance().id()) + " was executing");
        }
        host.watchdog.enterHost();
        return System.nanoTime();
    }

    private CallPhase phase() {
        return host.context().phase();
    }

    /** The snapshot a worker call reads, or {@code null} when the game can be read directly. */
    private ViewSnapshot snapshot() {
        return host.context().view();
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
            if (phase() != CallPhase.UPDATE && phase() != CallPhase.SHUTDOWN) {
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
        if (phase() == CallPhase.SHUTDOWN) {
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
            ViewSnapshot view = snapshot();
            if (view != null) {
                if (view.player() == null) {
                    throw HostError.of(Code.UNAVAILABLE);
                }
                return view.player();
            }
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
            ViewSnapshot view = snapshot();
            if (view != null) {
                if (view.dimension() == null) {
                    throw HostError.of(Code.UNAVAILABLE);
                }
                return new WorldRef(view.dimension(), view.player() != null
                        ? view.player().world().epoch() : host.worldEpoch());
            }
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
            host.requestRadius(radius);
            ViewSnapshot view = snapshot();
            List<EntitySnapshot> found;
            if (view == null) {
                found = host.ports.playerView().entitiesNear(radius, clamped);
            } else {
                host.noteEntitiesRead(view.gameTick());
                found = view.entities() != null
                        ? view.nearby(radius, clamped)
                        : view.nearby(entitiesFromClientThread(radius, clamped), radius, clamped);
            }
            host.perf.value("count.nearby-entities/" + instance.id(), found.size());
            return found;
        } finally {
            exit(t, "view.nearby-entities");
        }
    }

    /**
     * Entities for a worker whose snapshot has none, because no instance had read entities lately.
     * Fetched on the client thread, where game objects may be read, without charging the wait to the
     * guest. Later snapshots collect them while they keep being read.
     */
    private List<EntitySnapshot> entitiesFromClientThread(double radius, int max) throws HostError {
        var result = new CompletableFuture<List<EntitySnapshot>>();
        host.onClientThread(() -> result.complete(List.copyOf(host.ports.playerView().entitiesNear(radius, max))));
        host.watchdog.suspend();
        long waited = System.nanoTime();
        try {
            return result.get(CLIENT_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            throw HostError.of(Code.UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw HostError.of(Code.UNAVAILABLE);
        } finally {
            host.watchdog.resume();
            host.perf.record("host.view.nearby-entities.client-wait", System.nanoTime() - waited);
        }
    }

    /** How long a worker waits for work it handed the client thread before giving up. */
    private static final long CLIENT_WAIT_MILLIS = 1_000;

    /**
     * Text widths for a worker. The game's font code is not thread safe, so widths missing from the
     * cache are measured on the client thread at its next tick or frame. The wait is not charged to
     * the guest's call budget.
     */
    private List<Integer> measureOnClientThread(List<String> texts) throws HostError {
        var cache = host.textWidths();
        List<String> missing = new ArrayList<>();
        for (String s : texts) {
            if (!cache.containsKey(s)) {
                missing.add(s);
            }
        }
        if (!missing.isEmpty()) {
            var done = new CompletableFuture<Void>();
            host.onClientThread(() -> {
                for (String s : missing) {
                    cache.put(s, host.ports.hudCanvas().measureText(s));
                }
                done.complete(null);
            });
            host.watchdog.suspend();
            long waited = System.nanoTime();
            try {
                done.get(CLIENT_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException e) {
                throw HostError.of(Code.UNAVAILABLE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw HostError.of(Code.UNAVAILABLE);
            } finally {
                host.watchdog.resume();
                host.perf.record("host.hud.measure-text.client-wait", System.nanoTime() - waited);
            }
        }
        List<Integer> out = new ArrayList<>(texts.size());
        for (String s : texts) {
            Integer width = cache.get(s);
            out.add(width != null ? width : 0);
        }
        return out;
    }

    @Override
    public List<Integer> measureText(List<String> texts) throws HostError {
        long t = enter();
        try {
            if (phase() != CallPhase.UPDATE) {
                throw HostError.of(Code.UNAVAILABLE);
            }
            if (!instance.granted(Capability.HUD)) {
                throw HostError.of(Code.DENIED);
            }
            if (texts.size() > host.config.maxDrawCommands()) {
                throw new HostError(Code.LIMIT_EXCEEDED, "at most " + host.config.maxDrawCommands() + " texts");
            }
            if (host.onClientThread()) {
                List<Integer> out = new ArrayList<>(texts.size());
                for (String s : texts) {
                    out.add(host.ports.hudCanvas().measureText(s));
                }
                return out;
            }
            return measureOnClientThread(texts);
        } finally {
            exit(t, "hud.measure-text");
        }
    }
}
