package dev.pumpkinmc.patch.core.session;

import dev.pumpkinmc.patch.core.catalog.Catalog;
import dev.pumpkinmc.patch.core.dispatch.Inbound;
import dev.pumpkinmc.patch.core.host.GuestCaller;
import dev.pumpkinmc.patch.core.host.HostServices;
import dev.pumpkinmc.patch.core.model.Model.FrameInfo;
import dev.pumpkinmc.patch.core.network.HandshakeNegotiator;
import dev.pumpkinmc.patch.core.network.MuxCodec;
import dev.pumpkinmc.patch.core.network.MuxCodec.MalformedFrame;
import dev.pumpkinmc.patch.core.network.MuxFrame;
import dev.pumpkinmc.patch.core.network.MuxFrame.Accept;
import dev.pumpkinmc.patch.core.network.MuxFrame.Hello;
import dev.pumpkinmc.patch.core.network.MuxFrame.HelloMod;
import dev.pumpkinmc.patch.core.network.MuxFrame.Route;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the one live session and the handshake that precedes it.
 *
 * <p>{@code HELLO} and {@code ACCEPT} are handled on the network thread during configuration,
 * because the reply has to reach the server before the client acknowledges known packs. The
 * negotiation itself is a pure function of the immutable catalog snapshot.
 */
public final class SessionManager {
    private static final Logger LOG = LoggerFactory.getLogger("PumpkinPatch");

    /** Handshake progress for the connection being configured. Written by the network thread. */
    private record Handshake(Hello hello, Accept accept) {}

    private final Catalog catalog;
    private final HostServices host;
    private final GuestCaller caller;
    private volatile Handshake handshake = new Handshake(null, null);
    private volatile Consumer<String> refuseHandler = m -> {};
    private Session session;
    private long nextSessionId = 1;

    public SessionManager(Catalog catalog, HostServices host, GuestCaller caller) {
        this.catalog = catalog;
        this.host = host;
        this.caller = caller;
    }

    public Session current() {
        return session;
    }

    public void onRefuse(Consumer<String> handler) {
        refuseHandler = handler;
    }

    /** Configuration begins. Any thread. */
    public void onConfigurationStart() {
        handshake = new Handshake(null, null);
    }

    /** A {@code pumpkin:mux} frame arrived. Any thread. */
    public void onFrameReceived(byte[] bytes) {
        long now = System.nanoTime();
        int type = bytes.length > 0 ? bytes[0] : -1;
        if (type == 0 || type == 2) {
            MuxFrame frame;
            try {
                frame = MuxCodec.decode(bytes);
            } catch (MalformedFrame e) {
                LOG.warn("[PumpkinPatch] malformed handshake frame from server: {}", e.getMessage());
                return;
            }
            if (frame instanceof Hello hello) {
                onHello(hello);
            } else if (frame instanceof Accept accept) {
                onAccept(accept);
            }
            return;
        }
        Session s = session;
        if (s != null) {
            s.inbound().offer(new Inbound.Frame(bytes, now));
        }
    }

    private void onHello(Hello hello) {
        if (hello.muxVersion() != MuxFrame.MUX_VERSION) {
            LOG.warn("[PumpkinPatch] server speaks mux version {}, this client speaks {}", hello.muxVersion(),
                    MuxFrame.MUX_VERSION);
        }
        if (!catalog.awaitCompiled(host.config.catalogWaitMillis())) {
            LOG.warn("[PumpkinPatch] components still compiling after {} ms; they are reported as not compiled",
                    host.config.catalogWaitMillis());
        }
        var reply = HandshakeNegotiator.reply(catalog.entries(), hello);
        handshake = new Handshake(hello, null);
        LOG.info("[PumpkinPatch] HELLO from {} offering {}; replying {}", hello.serverId(),
                hello.mods().stream().map(HelloMod::id).toList(), reply.mods());
        host.ports.transport().sendFrame(MuxCodec.encode(reply));
    }

    private void onAccept(Accept accept) {
        Handshake h = handshake;
        handshake = new Handshake(h.hello(), accept);
        LOG.info("[PumpkinPatch] ACCEPT {} routes {}", accept.join() ? "JOIN" : "REFUSE", accept.routes());
        if (!accept.join()) {
            refuseHandler.accept(accept.message());
        }
    }

    /** The player joined the world. Client thread. */
    public void onJoin() {
        host.checkThread();
        if (session != null) {
            session.close(true);
        }
        Handshake h = handshake;
        boolean pumpkin = h.accept() != null && h.accept().join();
        if (h.hello() != null && h.accept() == null) {
            LOG.warn("[PumpkinPatch] server sent HELLO but no ACCEPT before join; treating the handshake as failed");
        }
        Map<String, Integer> routes = new HashMap<>();
        Map<String, List<String>> channels = new HashMap<>();
        if (pumpkin) {
            for (Route r : h.accept().routes()) {
                routes.put(r.modId(), r.routeId());
            }
            for (HelloMod m : h.hello().mods()) {
                channels.put(m.id(), m.channels());
            }
        }
        catalog.awaitCompiled(host.config.catalogWaitMillis());
        host.setWorldEpoch(1);
        session = new Session(nextSessionId++, pumpkin ? Session.Kind.PUMPKIN : Session.Kind.NON_PUMPKIN, host,
                caller);
        LOG.info("[PumpkinPatch] session {} started ({})", session.id(), session.kind());
        session.start(catalog.entries(), routes, channels);
    }

    public void tick(long gameTick) {
        if (session != null) {
            session.tick(gameTick);
        }
    }

    public void renderHud(FrameInfo frame) {
        if (session != null) {
            session.renderHud(frame);
        }
    }

    /** See {@link Session#awaitUpdates}. */
    public boolean awaitUpdates(long timeoutNanos) {
        host.checkThread();
        return session == null || session.awaitUpdates(timeoutNanos);
    }

    /** Benchmark harness only. See {@link Session#sendAsMod}. */
    public boolean sendAsMod(String modId, String channel, byte[] payload) {
        host.checkThread();
        return session != null && session.sendAsMod(modId, channel, payload);
    }

    public void enqueue(Inbound item) {
        Session s = session;
        if (s != null) {
            s.inbound().offer(item);
        }
    }

    /** Disconnect, or client stop when {@code gameRunning} is false. Client thread. */
    public void onDisconnect(boolean gameRunning) {
        if (session != null) {
            session.close(gameRunning);
            session = null;
        }
        handshake = new Handshake(null, null);
    }
}
