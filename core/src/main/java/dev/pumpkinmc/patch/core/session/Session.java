package dev.pumpkinmc.patch.core.session;

import dev.pumpkinmc.patch.core.capability.Capability;
import dev.pumpkinmc.patch.core.catalog.CatalogEntry;
import dev.pumpkinmc.patch.core.component.ComponentInstance;
import dev.pumpkinmc.patch.core.component.ComponentInstance.NetSend;
import dev.pumpkinmc.patch.core.component.ComponentInstance.State;
import dev.pumpkinmc.patch.core.dispatch.Inbound;
import dev.pumpkinmc.patch.core.dispatch.InboundQueue;
import dev.pumpkinmc.patch.core.host.GuestCaller;
import dev.pumpkinmc.patch.core.host.HostBridge;
import dev.pumpkinmc.patch.core.host.HostServices;
import dev.pumpkinmc.patch.core.host.HostServices.CallPhase;
import dev.pumpkinmc.patch.core.manifest.Manifest.Activation;
import dev.pumpkinmc.patch.core.model.Event;
import dev.pumpkinmc.patch.core.model.Model.DrawCommand;
import dev.pumpkinmc.patch.core.model.Model.EventKind;
import dev.pumpkinmc.patch.core.model.Model.FrameInfo;
import dev.pumpkinmc.patch.core.model.Model.FrameOutput;
import dev.pumpkinmc.patch.core.model.Model.InitInfo;
import dev.pumpkinmc.patch.core.model.Model.SessionInfo;
import dev.pumpkinmc.patch.core.model.Model.WorldRef;
import dev.pumpkinmc.patch.core.network.MuxCodec;
import dev.pumpkinmc.patch.core.network.MuxCodec.MalformedFrame;
import dev.pumpkinmc.patch.core.network.MuxFrame;
import dev.pumpkinmc.patch.core.runtime.Runtime.InstantiationFailure;
import dev.pumpkinmc.patch.core.host.GuestCaller.Outcome;
import dev.pumpkinmc.patch.core.host.ViewSnapshot;
import dev.pumpkinmc.patch.core.model.Model.EntitySnapshot;
import dev.pumpkinmc.patch.core.model.Model.PlayerSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One server connection. Instances live exactly as long as their session. */
public final class Session {
    private static final Logger LOG = LoggerFactory.getLogger("PumpkinPatch");

    public enum Kind {
        PUMPKIN,
        NON_PUMPKIN
    }

    private final long id;
    private final Kind kind;
    private final HostServices host;
    private final GuestCaller caller;
    private final InboundQueue inbound = new InboundQueue();
    private final List<ComponentInstance> instances = new ArrayList<>();
    private final Map<Integer, ComponentInstance> byRoute = new HashMap<>();
    private final Map<Integer, Long> lastSend = new HashMap<>();
    private long malformedFrames;
    private boolean closed;
    /** The GUI size of the last frame drawn, which every update is given. */
    private int guiWidth;
    private int guiHeight;
    private long gameTick;
    /** Updates running on workers. At most one per instance. */
    private final Map<ComponentInstance, PendingUpdate> pending = new IdentityHashMap<>();

    /** An update on a worker, with what is needed to apply its outcome. */
    private record PendingUpdate(CompletableFuture<Outcome<FrameOutput>> outcome, List<Event> batch, FrameInfo frame) {}

    Session(long id, Kind kind, HostServices host, GuestCaller caller) {
        this.id = id;
        this.kind = kind;
        this.host = host;
        this.caller = caller;
        host.textWidths().clear();
    }

    public long id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    public List<ComponentInstance> instances() {
        return instances;
    }

    public InboundQueue inbound() {
        return inbound;
    }

    public long malformedFrames() {
        return malformedFrames;
    }

    /** Creates and initializes one instance per eligible entry, in mod id order. */
    void start(List<CatalogEntry> entries, Map<String, Integer> routes, Map<String, List<String>> channelOrder) {
        for (CatalogEntry entry : entries) {
            if (entry.status() != CatalogEntry.Status.COMPILED) {
                continue;
            }
            var instance = new ComponentInstance(entry, id);
            instances.add(instance);
            Integer route = routes.get(entry.id());
            boolean negotiated = route != null;
            if (!negotiated && entry.manifest().activation() == Activation.PUMPKIN_SERVER) {
                instance.transition(State.DORMANT);
                continue;
            }
            if (negotiated && entry.granted().contains(Capability.NET)) {
                instance.bindRoute(route, channelOrder.getOrDefault(entry.id(), entry.manifest().channels()));
                byRoute.put(route, instance);
            }
            long t0 = System.nanoTime();
            try {
                instance.attach(entry.compiled().instantiate(new HostBridge(host, instance)));
            } catch (InstantiationFailure e) {
                LOG.error("[PumpkinPatch] {} failed to instantiate", entry.id(), e);
                instance.fault(new dev.pumpkinmc.patch.core.diag.FaultRecord(entry.id(), entry.manifest().version(),
                        id, "INSTANTIATION", e.getMessage(), "INIT", System.currentTimeMillis()));
                continue;
            }
            host.perf.record("lifecycle.instantiate", System.nanoTime() - t0);
            var info = new SessionInfo(id, kind == Kind.PUMPKIN, instance.route() >= 0);
            var init = new InitInfo(info, entry.manifest().version(), host.config.hostVersion());
            long t1 = System.nanoTime();
            caller.call(instance, CallPhase.INIT, "init", host.config.limits().initBudgetNanos(), g -> g.init(init))
                    .ifPresent(result -> {
                        instance.activate(result.subscriptions());
                        instance.enqueue(new Event.SessionStarted(info));
                        instance.setUpdateDue(true);
                        LOG.info("[PumpkinPatch] {} ACTIVE (session {}, route {}, subscriptions {})",
                                entry.id(), id, instance.route(), result.subscriptions());
                    });
            host.perf.record("lifecycle.init", System.nanoTime() - t1);
            if (instance.state() == State.FAULTED) {
                sendClose(instance, "init failed");
            }
        }
    }

    /**
     * The tick drain point. Each instance gets one {@code update} with its pending events and the
     * GUI size of the last frame, and none when it has no events and no update is due.
     */
    void tick(long gameTick) {
        this.gameTick = gameTick;
        host.runClientTasks();
        collectFinished();
        inbound.drain(this::route);
        var frame = new FrameInfo(guiWidth, guiHeight, gameTick);
        ViewSnapshot view = null;
        for (ComponentInstance instance : instances) {
            // An instance still running last tick's update keeps its events for the next one.
            if (instance.state() != State.ACTIVE || pending.containsKey(instance)) {
                continue;
            }
            instance.resetTickQuotas();
            List<Event> batch = new ArrayList<>();
            int cap = host.config.maxEventsPerBatch() - 1;
            while (batch.size() < cap && !instance.queue().isEmpty()) {
                batch.add(instance.queue().pollFirst());
            }
            if (instance.subscriptions().contains(EventKind.TICK) && instance.queue().isEmpty()) {
                batch.add(new Event.Tick(gameTick));
            }
            if (batch.isEmpty() && !instance.updateDue()) {
                continue;
            }
            instance.setUpdateDue(false);
            long budget = host.config.limits().callBudgetNanos();
            if (host.workers() == null) {
                completeUpdate(instance, caller.call(instance, CallPhase.UPDATE, "update", budget,
                        g -> g.update(batch, frame)), batch, frame);
            } else {
                if (view == null) {
                    view = takeSnapshot();
                }
                pending.put(instance, new PendingUpdate(caller.submit(instance, CallPhase.UPDATE, "update", budget,
                        view, g -> g.update(batch, frame)), batch, frame));
            }
        }
    }

    private void completeUpdate(ComponentInstance instance, Optional<FrameOutput> output, List<Event> batch,
            FrameInfo frame) {
        if (output.isPresent()) {
            for (Event e : batch) {
                if (e instanceof Event.NetMessage) {
                    host.perf.record("net.delivered-to-guest", System.nanoTime() - pendingDeliveryStart);
                }
            }
            applyOutbox(instance);
            if (instance.granted(Capability.HUD)) {
                applyFrameOutput(instance, output.get(), frame);
            }
        } else {
            sendClose(instance, "component faulted");
        }
    }

    /** Applies the outcome of every update a worker has finished. */
    private void collectFinished() {
        if (pending.isEmpty()) {
            return;
        }
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            ComponentInstance instance = e.getKey();
            PendingUpdate p = e.getValue();
            if (p.outcome().isDone()) {
                // An IdentityHashMap entry is unusable once removed, so it is read first.
                it.remove();
                completeUpdate(instance, caller.finish(p.outcome().join()), p.batch(), p.frame());
            }
        }
    }

    /**
     * Waits for the updates on workers, serving the client-thread work they ask for meanwhile, and
     * applies them. Returns whether none is left running.
     */
    boolean awaitUpdates(long timeoutNanos) {
        long deadline = System.nanoTime() + timeoutNanos;
        while (!pending.isEmpty()) {
            host.runClientTasks();
            collectFinished();
            if (pending.isEmpty() || System.nanoTime() - deadline > 0) {
                break;
            }
            Thread.onSpinWait();
        }
        return pending.isEmpty();
    }

    /**
     * What the view imports read on a worker this tick. Taken on the client thread, once, for every
     * instance updated this tick. Entities are only collected when some instance may read them.
     */
    private ViewSnapshot takeSnapshot() {
        long t = System.nanoTime();
        var view = host.ports.playerView();
        PlayerSnapshot player = view.localPlayer(host.worldEpoch()).orElse(null);
        String dimension = view.dimension().orElse(null);
        double radius = host.snapshotRadius();
        boolean wanted = false;
        for (ComponentInstance i : instances) {
            wanted |= i.state() == State.ACTIVE && i.granted(Capability.VIEW);
        }
        List<EntitySnapshot> entities = wanted && player != null
                ? List.copyOf(view.entitiesNear(radius, host.config.maxNearbyEntities()))
                : List.of();
        host.perf.record("host.view.snapshot", System.nanoTime() - t);
        return new ViewSnapshot(player, dimension, entities, radius);
    }

    private long pendingDeliveryStart;

    private void route(Inbound item) {
        switch (item) {
            case Inbound.Frame f -> {
                MuxFrame frame;
                try {
                    frame = MuxCodec.decode(f.bytes());
                } catch (MalformedFrame e) {
                    malformedFrames++;
                    return;
                }
                switch (frame) {
                    case MuxFrame.Data d -> {
                        ComponentInstance target = byRoute.get(d.route());
                        if (target == null || target.state() != State.ACTIVE
                                || d.channel() < 0 || d.channel() >= target.channels().size()
                                || d.payload().length > host.config.maxInboundPayload()) {
                            malformedFrames++;
                            return;
                        }
                        Long sent = lastSend.remove(d.route());
                        if (sent != null) {
                            host.perf.record("net.rtt", f.receivedNanos() - sent);
                            pendingDeliveryStart = sent;
                        }
                        target.messagesIn++;
                        target.bytesIn += d.payload().length;
                        target.enqueue(new Event.NetMessage(target.channels().get(d.channel()), d.payload()));
                    }
                    case MuxFrame.Close c -> {
                        ComponentInstance target = byRoute.remove(c.route());
                        if (target != null) {
                            target.unbindRoute();
                            LOG.info("[PumpkinPatch] server closed route {} ({}): {}", c.route(), target.id(),
                                    c.reason());
                        }
                    }
                    default -> malformedFrames++;
                }
            }
            case Inbound.Action a -> {
                for (ComponentInstance i : instances) {
                    if (i.id().equals(a.modId()) && i.state() == State.ACTIVE && i.granted(Capability.INPUT)
                            && i.subscriptions().contains(EventKind.INPUT)) {
                        i.enqueue(new Event.Action(a.actionId(), a.pressed()));
                    }
                }
            }
            case Inbound.WorldChanged w -> {
                host.setWorldEpoch(host.worldEpoch() + 1);
                var ref = new WorldRef(w.dimension(), host.worldEpoch());
                for (ComponentInstance i : instances) {
                    if (i.state() == State.ACTIVE && i.subscriptions().contains(EventKind.WORLD)) {
                        i.enqueue(new Event.WorldChanged(ref));
                    }
                }
            }
        }
    }

    private void applyOutbox(ComponentInstance instance) {
        for (NetSend send : instance.outbox()) {
            if (instance.route() < 0) {
                break;
            }
            byte[] frame = MuxCodec.encode(new MuxFrame.Data(instance.route(), send.channelIndex(), send.payload()));
            host.ports.transport().sendFrame(frame);
            instance.messagesOut++;
            instance.bytesOut += send.payload().length;
            lastSend.put(instance.route(), System.nanoTime());
        }
        instance.outbox().clear();
    }

    /**
     * Sends a payload on a mod's route as if its component had. Benchmark harness only: the
     * autopilot uses it for server-side benchmark controls. Returns false without a route.
     */
    boolean sendAsMod(String modId, String channel, byte[] payload) {
        for (ComponentInstance i : instances) {
            if (i.id().equals(modId) && i.route() >= 0) {
                int index = i.channels().indexOf(channel);
                if (index < 0) {
                    return false;
                }
                host.ports.transport().sendFrame(MuxCodec.encode(new MuxFrame.Data(i.route(), index, payload)));
                return true;
            }
        }
        return false;
    }

    private void sendClose(ComponentInstance instance, String reason) {
        if (instance.route() >= 0) {
            host.ports.transport().sendFrame(MuxCodec.encode(new MuxFrame.Close(instance.route(), reason)));
            byRoute.remove(instance.route());
            instance.unbindRoute();
        }
    }

    private void applyFrameOutput(ComponentInstance instance, FrameOutput out, FrameInfo frame) {
        switch (out) {
            case FrameOutput.Unchanged u -> {}
            case FrameOutput.Clear c -> instance.setRenderCache(List.of());
            case FrameOutput.Commands c -> {
                host.perf.value("count.draw-commands/" + instance.id(), c.commands().size());
                if (c.commands().size() > host.config.maxDrawCommands()) {
                    instance.drawCommandsTruncated += c.commands().size() - host.config.maxDrawCommands();
                }
                instance.setRenderCache(sanitize(c.commands(), frame));
            }
        }
    }

    /**
     * The HUD drain point. Makes no guest calls: it draws each instance's last output. A resized
     * GUI makes the next tick update every HUD instance, so its layout follows.
     */
    void renderHud(FrameInfo frame) {
        host.runClientTasks();
        collectFinished();
        if (frame.guiWidth() != guiWidth || frame.guiHeight() != guiHeight) {
            guiWidth = frame.guiWidth();
            guiHeight = frame.guiHeight();
            for (ComponentInstance instance : instances) {
                if (instance.state() == State.ACTIVE && instance.granted(Capability.HUD)) {
                    instance.setUpdateDue(true);
                }
            }
        }
        for (ComponentInstance instance : instances) {
            if (instance.state() == State.ACTIVE && !instance.renderCache().isEmpty()) {
                host.ports.hudCanvas().draw(instance.renderCache());
            }
        }
    }

    private List<DrawCommand> sanitize(List<DrawCommand> commands, FrameInfo frame) {
        int max = host.config.maxDrawCommands();
        int w = frame.guiWidth() * 2;
        int h = frame.guiHeight() * 2;
        List<DrawCommand> out = new ArrayList<>(Math.min(commands.size(), max));
        for (DrawCommand c : commands) {
            if (out.size() == max) {
                break;
            }
            out.add(switch (c) {
                case DrawCommand.Text t -> new DrawCommand.Text(
                        clamp(t.x(), w), clamp(t.y(), h),
                        t.text().length() > host.config.maxTextLength()
                                ? t.text().substring(0, host.config.maxTextLength())
                                : t.text(),
                        t.argb(), t.shadow());
                case DrawCommand.FillRect r -> new DrawCommand.FillRect(
                        clamp(r.x(), w), clamp(r.y(), h), Math.min(r.w(), w), Math.min(r.h(), h), r.argb());
            });
        }
        return List.copyOf(out);
    }

    private static int clamp(int v, int limit) {
        return Math.max(-limit, Math.min(v, limit));
    }

    /**
     * Ends the session. With {@code gameRunning}, instances get {@code session-ending} and
     * {@code shutdown}. Otherwise they are closed without guest calls.
     */
    void close(boolean gameRunning) {
        if (closed) {
            return;
        }
        closed = true;
        inbound.clear();
        // The budget bounds each update, and a worker waiting on the client thread is served here.
        if (!awaitUpdates(2 * host.config.limits().callBudgetNanos() + 2_000_000_000L)) {
            LOG.warn("[PumpkinPatch] session {} closed with updates still running", id);
        }
        if (gameRunning) {
            for (ComponentInstance i : instances) {
                if (i.state() == State.ACTIVE) {
                    var frame = new FrameInfo(guiWidth, guiHeight, gameTick);
                    caller.call(i, CallPhase.UPDATE, "update", host.config.limits().callBudgetNanos(),
                            g -> g.update(List.of(new Event.SessionEnding()), frame));
                }
            }
        }
        for (int n = instances.size() - 1; n >= 0; n--) {
            ComponentInstance i = instances.get(n);
            if (i.state() == State.ACTIVE) {
                if (gameRunning) {
                    caller.call(i, CallPhase.SHUTDOWN, "shutdown", 50_000_000L, g -> {
                        g.shutdown();
                        return Boolean.TRUE;
                    });
                    applyOutbox(i);
                }
                if (i.state() == State.ACTIVE) {
                    i.transition(State.CLOSING);
                }
            }
            i.closeGuest();
            i.transition(State.CLOSED);
        }
        byRoute.clear();
        LOG.info("[PumpkinPatch] session {} closed", id);
    }
}
