package dev.pumpkinmc.patch.core.component;

import dev.pumpkinmc.patch.core.capability.Capability;
import dev.pumpkinmc.patch.core.catalog.CatalogEntry;
import dev.pumpkinmc.patch.core.diag.FaultRecord;
import dev.pumpkinmc.patch.core.model.Event;
import dev.pumpkinmc.patch.core.model.Model.DrawCommand;
import dev.pumpkinmc.patch.core.model.Model.EventKind;
import dev.pumpkinmc.patch.core.runtime.Runtime.GuestInstance;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One component instantiated for one session. Confined to the client thread, except that during an
 * update on a worker, that worker runs the guest and fills the outbox and quota counters. The
 * session starts no other update and reads none of them until the worker's update has finished.
 */
public final class ComponentInstance {

    public enum State {
        PENDING,
        DORMANT,
        INSTANTIATED,
        ACTIVE,
        FAULTED,
        DISABLED,
        CLOSING,
        CLOSED
    }

    /** A staged effect, applied after the export that produced it returns. */
    public record NetSend(int channelIndex, byte[] payload) {}

    public static final int MAX_QUEUED_EVENTS = 1024;

    private final CatalogEntry entry;
    private final long sessionId;
    private State state = State.PENDING;
    private GuestInstance guest;
    private Set<EventKind> subscriptions = EnumSet.noneOf(EventKind.class);
    private final Deque<Event> queue = new ArrayDeque<>();
    private final List<NetSend> outbox = new ArrayList<>();
    private int route = -1;
    private List<String> channels = List.of();
    private List<DrawCommand> renderCache = List.of();
    private boolean updateDue;
    private FaultRecord fault;

    // Per-tick quota counters.
    public int sentThisTick;
    public int bytesThisTick;
    public int logLinesThisTick;

    // Diagnostics.
    public long eventsDropped;
    public long calls;
    public long guestNanos;
    public long bytesIn;
    public long bytesOut;
    public long messagesIn;
    public long messagesOut;
    public long drawCommandsTruncated;

    public ComponentInstance(CatalogEntry entry, long sessionId) {
        this.entry = entry;
        this.sessionId = sessionId;
    }

    public CatalogEntry entry() {
        return entry;
    }

    public String id() {
        return entry.id();
    }

    public long sessionId() {
        return sessionId;
    }

    public State state() {
        return state;
    }

    public void transition(State next) {
        state = next;
    }

    public GuestInstance guest() {
        return guest;
    }

    public void attach(GuestInstance g) {
        guest = g;
        state = State.INSTANTIATED;
    }

    public boolean granted(Capability c) {
        return entry.granted().contains(c);
    }

    public Set<EventKind> subscriptions() {
        return subscriptions;
    }

    public void activate(Set<EventKind> subs) {
        subscriptions = subs.isEmpty() ? EnumSet.noneOf(EventKind.class) : EnumSet.copyOf(subs);
        state = State.ACTIVE;
    }

    public void bindRoute(int routeId, List<String> channelOrder) {
        route = routeId;
        channels = List.copyOf(channelOrder);
    }

    public int route() {
        return route;
    }

    public void unbindRoute() {
        route = -1;
    }

    public List<String> channels() {
        return channels;
    }

    /** Queues an event, dropping the oldest when the queue is full. */
    public void enqueue(Event e) {
        if (queue.size() >= MAX_QUEUED_EVENTS) {
            queue.pollFirst();
            eventsDropped++;
        }
        queue.addLast(e);
    }

    public Deque<Event> queue() {
        return queue;
    }

    public List<NetSend> outbox() {
        return outbox;
    }

    public List<DrawCommand> renderCache() {
        return renderCache;
    }

    public void setRenderCache(List<DrawCommand> c) {
        renderCache = c;
    }

    /** Whether the next tick updates this instance even without events. */
    public boolean updateDue() {
        return updateDue;
    }

    public void setUpdateDue(boolean due) {
        updateDue = due;
    }

    public FaultRecord fault() {
        return fault;
    }

    public void fault(FaultRecord f) {
        fault = f;
        state = State.FAULTED;
        queue.clear();
        outbox.clear();
        renderCache = List.of();
        closeGuest();
    }

    /** Releases the store. The next session creates a fresh instance. */
    public void closeGuest() {
        if (guest != null) {
            guest.close();
            guest = null;
        }
    }

    public void resetTickQuotas() {
        sentThisTick = 0;
        bytesThisTick = 0;
        logLinesThisTick = 0;
    }
}
