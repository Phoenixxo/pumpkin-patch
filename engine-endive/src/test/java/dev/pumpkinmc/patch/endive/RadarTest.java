package dev.pumpkinmc.patch.endive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pumpkinmc.patch.core.PatchConfig;
import dev.pumpkinmc.patch.core.component.ComponentInstance;
import dev.pumpkinmc.patch.core.model.Model;
import dev.pumpkinmc.patch.core.network.MuxCodec;
import dev.pumpkinmc.patch.core.network.MuxFrame;
import dev.pumpkinmc.patch.core.runtime.Runtime.ComponentRuntime;
import dev.pumpkinmc.patch.endive.EndiveComponentRuntime.Engine;
import dev.pumpkinmc.patch.jvm.JavaComponentRuntime;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The real example:radar component on the real runtime, with a scripted server. The Java port runs
 * the same script, so the benchmark control is known to do the same work.
 */
class RadarTest {
    static final List<String> CHANNELS = List.of("sync", "wp-add", "wp-clear", "bench", "waypoints", "players", "notice");

    /** The three Endive engines, and the Java port as the control. */
    static ComponentRuntime runtime(String engine) {
        return engine.equals("java")
                ? new JavaComponentRuntime()
                : new EndiveComponentRuntime(Engine.valueOf(engine.toUpperCase(Locale.ROOT)), true);
    }

    static Harness radar(String engine) throws Exception {
        var h = new Harness(runtime(engine), PatchConfig.defaults());
        h.install("example:radar", "radar", "always", "\"view\", \"hud\", \"input\"", "\"net\"", """
                [client.net]
                protocol = "radar/1"
                channels = ["sync", "wp-add", "wp-clear", "bench", "waypoints", "players", "notice"]
                [[client.actions]]
                id = "mark"
                [[client.actions]]
                id = "zoom"
                [[client.actions]]
                id = "toggle"
                [[client.actions]]
                id = "clear"
                """);
        h.load();
        h.joinPumpkin(new MuxFrame.HelloMod("example:radar", "^0.1", "pumpkin:client/client-mod@0.1.0", false,
                "radar/1", CHANNELS));
        return h;
    }

    /** The radar-protocol encoding of a Down::Waypoints message. */
    static byte[] waypoints(String dimension, String... names) {
        var out = new ByteArrayOutputStream();
        str(out, dimension);
        out.write(names.length & 0xFF);
        out.write(names.length >> 8);
        int id = 1;
        for (String n : names) {
            out.writeBytes(le(4).putInt(id++).array());
            str(out, "Alex");
            str(out, n);
            var pos = le(24).putDouble(1 + id * 3).putDouble(64).putDouble(2);
            out.writeBytes(pos.array());
        }
        return out.toByteArray();
    }

    static ByteBuffer le(int n) {
        return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
    }

    static void str(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(b.length & 0xFF);
        out.write(b.length >> 8);
        out.writeBytes(b);
    }

    static List<Model.DrawCommand> lastDrawn(Harness h) {
        return h.drawn.getLast();
    }

    @ParameterizedTest
    @ValueSource(strings = {"interpreter", "compiler", "redline", "java"})
    void radarDrawsEntitiesAndServerWaypoints(String engine) throws Exception {
        try (var h = radar(engine)) {
            ComponentInstance radar = h.patch.instances().getFirst();
            assertEquals(ComponentInstance.State.ACTIVE, radar.state());

            // The session start makes the component ask the server for this world's waypoints.
            h.tick();
            var sync = (MuxFrame.Data) MuxCodec.decode(h.sent.getLast());
            assertEquals(CHANNELS.indexOf("sync"), sync.channel());

            for (int i = 0; i < 40; i++) {
                h.entities.add(new Model.EntitySnapshot(UUID.randomUUID(), i % 2 == 0 ? "minecraft:pig" : "minecraft:zombie",
                        new Model.Vec3(1 + (i % 7), 64, 2 + i / 7)));
            }
            h.patch.onFrameReceived(MuxCodec.encode(new MuxFrame.Data(radar.route(), CHANNELS.indexOf("waypoints"),
                    waypoints("minecraft:overworld", "Home", "Mine"))));
            h.tick();
            h.frame();
            var drawn = lastDrawn(h);
            long dots = drawn.stream().filter(c -> c instanceof Model.DrawCommand.FillRect r && r.w() == 2 && r.h() == 2)
                    .count();
            assertEquals(40, dots, "one dot per entity");
            assertTrue(drawn.stream().anyMatch(c -> c instanceof Model.DrawCommand.Text t && t.text().equals("Home")));
            assertTrue(drawn.stream().anyMatch(c -> c instanceof Model.DrawCommand.Text t
                    && t.text().startsWith("32m  40 ent  2 wp")));

            // Nothing changed: the component answers unchanged and the host keeps drawing its cache.
            int before = h.drawn.size();
            h.tick();
            h.frame();
            assertEquals(before + 1, h.drawn.size());
            assertEquals(drawn, lastDrawn(h));

            // Pressing mark sends a waypoint with the player's position.
            h.patch.enqueueAction("example:radar", "mark", true);
            h.tick();
            var mark = (MuxFrame.Data) MuxCodec.decode(h.sent.getLast());
            assertEquals(CHANNELS.indexOf("wp-add"), mark.channel());

            // A world change drops the old world's waypoints.
            h.patch.enqueueWorldChanged("minecraft:the_nether");
            h.tick();
            h.frame();
            assertTrue(lastDrawn(h).stream().noneMatch(c -> c instanceof Model.DrawCommand.Text t
                    && t.text().equals("Home")));

            // Performance capture reaches every layer.
            var timings = h.patch.perf().summarize();
            var values = h.patch.perf().summarizeValues();
            assertTrue(timings.containsKey("guest.update/example:radar"));
            assertTrue(timings.containsKey("host.view.nearby-entities/example:radar"));
            assertTrue(timings.containsKey("host.hud.measure-text/example:radar"));
            assertTrue(values.containsKey("alloc-bytes.update/example:radar"));
            assertEquals(40.0, values.get("count.nearby-entities/example:radar").max());
            if (!engine.equals("java")) {
                // Only Wasm has a boundary to convert across and a linear memory to size.
                assertTrue(timings.containsKey("convert.update/example:radar"));
                assertTrue(radar.guest().linearMemoryBytes() > 0);
            }
            assertTrue(radar.bytesIn > 0 && radar.bytesOut > 0);
            System.out.printf("[%s] radar linear memory %d KiB, update %s%n", engine,
                    radar.guest().linearMemoryBytes() / 1024, timings.get("guest.update/example:radar"));
        }
    }
}
