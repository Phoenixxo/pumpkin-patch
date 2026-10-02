package dev.pumpkinmc.patch.endive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pumpkinmc.patch.core.PatchConfig;
import dev.pumpkinmc.patch.core.catalog.CatalogEntry;
import dev.pumpkinmc.patch.core.component.ComponentInstance;
import dev.pumpkinmc.patch.core.component.ComponentInstance.State;
import dev.pumpkinmc.patch.core.model.Model;
import dev.pumpkinmc.patch.core.network.MuxCodec;
import dev.pumpkinmc.patch.core.network.MuxFrame;
import dev.pumpkinmc.patch.core.runtime.Runtime.RuntimeLimits;
import dev.pumpkinmc.patch.endive.EndiveComponentRuntime.Engine;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Real Rust components on the real Endive runtime, through the real core. */
class RealComponentTest {
    static final MuxFrame.HelloMod PING = new MuxFrame.HelloMod(
            "example:ping", "^0.1", "pumpkin:client/client-mod@0.1.0", true, "ping/1", List.of("request", "response"));

    static Harness demo(Engine engine, PatchConfig config) throws IOException {
        var h = new Harness(engine, config);
        h.install("example:ping", "ping", "pumpkin-server", "\"net\", \"hud\", \"input\"", """
                [client.net]
                protocol = "ping/1"
                channels = ["request", "response"]
                [[client.actions]]
                id = "ping"
                title = "Ping the server"
                default-key = "key.keyboard.o"
                """);
        h.install("example:trap", "trap", "always", "\"hud\", \"input\"", """
                [[client.actions]]
                id = "trap"
                title = "Trap"
                default-key = "key.keyboard.k"
                """);
        h.install("example:spin", "spin", "always", "\"input\"", """
                [[client.actions]]
                id = "spin"
                title = "Spin"
                default-key = "key.keyboard.j"
                """);
        h.load();
        return h;
    }

    static ComponentInstance instance(Harness h, String id) {
        return h.patch.instances().stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
    }

    static String hudText(Harness h) {
        StringBuilder sb = new StringBuilder();
        for (var list : h.drawn) {
            for (var c : list) {
                if (c instanceof Model.DrawCommand.Text t) {
                    sb.append(t.text()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void pingRoundTripTrapIsolationAndFreshSessions(Engine engine) throws Exception {
        pingRoundTripTrapIsolationAndFreshSessions(engine, PatchConfig.defaults());
    }

    /** The same, with every update on a worker thread against a view snapshot. */
    @ParameterizedTest
    @EnumSource(Engine.class)
    void pingRoundTripTrapIsolationAndFreshSessionsOnWorkers(Engine engine) throws Exception {
        pingRoundTripTrapIsolationAndFreshSessions(engine, PatchConfig.defaults().withWorkers(true));
    }

    private static void pingRoundTripTrapIsolationAndFreshSessions(Engine engine, PatchConfig config)
            throws Exception {
        try (var h = demo(engine, config)) {
            for (CatalogEntry e : h.patch.catalog().entries()) {
                assertEquals(CatalogEntry.Status.COMPILED, e.status(), e.id() + " " + e.reason());
            }
            h.joinPumpkin(PING);
            assertEquals(State.ACTIVE, instance(h, "example:ping").state());
            assertEquals(State.ACTIVE, instance(h, "example:trap").state());
            assertEquals(1, instance(h, "example:ping").route());

            // Press P: the component sends DATA{route 1, "request"} with sequence 0.
            h.patch.enqueueAction("example:ping", "ping", true);
            h.tick();
            var request = (MuxFrame.Data) MuxCodec.decode(h.sent.removeLast());
            assertEquals(1, request.route());
            assertEquals(0, request.channel());
            assertEquals(0, ByteBuffer.wrap(request.payload()).order(ByteOrder.LITTLE_ENDIAN).getInt());

            // The server answers on "response". The HUD shows it after the next tick.
            byte[] text = "Hello Tester from Pumpkin".getBytes(StandardCharsets.UTF_8);
            byte[] reply = ByteBuffer.allocate(4 + text.length).order(ByteOrder.LITTLE_ENDIAN).putInt(0).put(text).array();
            h.patch.onFrameReceived(MuxCodec.encode(new MuxFrame.Data(1, 1, reply)));
            h.tick();
            h.frame();
            assertTrue(hudText(h).contains("Hello Tester from Pumpkin"), hudText(h));

            // The trap component faults. Its outbox is discarded and nothing else changes.
            h.patch.enqueueAction("example:trap", "trap", true);
            h.tick();
            assertEquals(State.FAULTED, instance(h, "example:trap").state());
            assertEquals("TRAP_UNREACHABLE", h.faults.getLast().kind());
            assertEquals(State.ACTIVE, instance(h, "example:ping").state());
            h.patch.enqueueAction("example:ping", "ping", true);
            h.tick();
            assertEquals(1, ((MuxFrame.Data) MuxCodec.decode(h.sent.removeLast())).route());

            // Disconnect and reconnect: fresh instances, fresh state (sequence restarts at 0).
            ComponentInstance before = instance(h, "example:ping");
            h.patch.onDisconnect();
            assertEquals(State.CLOSED, before.state());
            h.sent.clear();
            h.joinPumpkin(PING);
            ComponentInstance after = instance(h, "example:ping");
            assertTrue(before != after);
            assertEquals(State.ACTIVE, instance(h, "example:trap").state());
            h.patch.enqueueAction("example:ping", "ping", true);
            h.tick();
            var again = (MuxFrame.Data) MuxCodec.decode(h.sent.removeLast());
            assertEquals(0, ByteBuffer.wrap(again.payload()).order(ByteOrder.LITTLE_ENDIAN).getInt());
        }
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void loopingGuestIsStoppedAndTheThreadStaysUsable(Engine engine) throws Exception {
        loopingGuestIsStopped(engine, false);
    }

    /** The loop runs on a worker, so the worker is interrupted and the client thread never is. */
    @ParameterizedTest
    @EnumSource(Engine.class)
    void loopingGuestOnAWorkerIsStopped(Engine engine) throws Exception {
        loopingGuestIsStopped(engine, true);
    }

    private static void loopingGuestIsStopped(Engine engine, boolean workers) throws Exception {
        var config = PatchConfig.defaults().withLimits(new RuntimeLimits(25_000_000L, 2_000_000_000L, 256L << 20))
                .withWorkers(workers);
        try (var h = demo(engine, config)) {
            h.joinPumpkin(PING);
            h.patch.enqueueAction("example:spin", "spin", true);
            long start = System.nanoTime();
            h.tick();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertEquals(State.FAULTED, instance(h, "example:spin").state());
            assertEquals("TIMEOUT", h.faults.getLast().kind(), h.faults.getLast().message());
            assertTrue(elapsedMs < 1_000, "spin stopped after " + elapsedMs + " ms");

            // No interrupt leaks to the client thread: interruptible I/O still works.
            assertFalse(Thread.currentThread().isInterrupted());
            var file = Files.createTempFile("pumpkin", ".bin");
            try (var ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
                ch.write(ByteBuffer.wrap(new byte[] {1, 2, 3}));
            }
            Thread.sleep(5);

            // Other components keep working.
            h.patch.enqueueAction("example:ping", "ping", true);
            h.tick();
            assertEquals(1, ((MuxFrame.Data) MuxCodec.decode(h.sent.removeLast())).route());
            assertEquals(State.ACTIVE, instance(h, "example:trap").state());
            System.out.printf("[%s%s] spin stopped in %d ms; %s%n", engine, workers ? ", workers" : "", elapsedMs,
                    h.patch.perf().summarize().get("watchdog.stop-latency"));
        }
    }
}
