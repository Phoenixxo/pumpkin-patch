package dev.pumpkinmc.patch.fabric.bench;

import dev.pumpkinmc.patch.core.PumpkinPatch;
import dev.pumpkinmc.patch.core.component.ComponentInstance;
import dev.pumpkinmc.patch.core.diag.Perf;
import dev.pumpkinmc.patch.fabric.input.KeyBindingRegistrar;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the real client through the acceptance scenario or a benchmark, so the run is repeatable.
 * Keys are pressed through the registered key mappings, the same path a player's key press takes.
 * Enabled only with {@code -Dpumpkinpatch.autopilot=demo|bench}.
 */
public final class Autopilot {
    private static final Logger LOG = LoggerFactory.getLogger("PumpkinPatch");

    private interface Step {
        /** Returns true when the step is complete. */
        boolean tick(Minecraft mc);
    }

    private final PumpkinPatch patch;
    private final KeyBindingRegistrar keys;
    private final PerfProbe probe;
    private final Path out;
    private final String server;
    private final Deque<Step> steps = new ArrayDeque<>();
    private final List<String> report = new ArrayList<>();
    private final List<KeyMapping> release = new ArrayList<>();
    private int wait;

    public Autopilot(PumpkinPatch patch, KeyBindingRegistrar keys, PerfProbe probe, Path out, String server) {
        this.patch = patch;
        this.keys = keys;
        this.probe = probe;
        this.out = out;
        this.server = server;
    }

    public static Autopilot fromProperties(PumpkinPatch patch, KeyBindingRegistrar keys, PerfProbe probe, Path gameDir) {
        String mode = System.getProperty("pumpkinpatch.autopilot");
        if (mode == null) {
            return null;
        }
        Path out = Path.of(System.getProperty("pumpkinpatch.out", gameDir.resolve("pumpkin-patch-results").toString()));
        var a = new Autopilot(patch, keys, probe, out, System.getProperty("pumpkinpatch.server", "localhost:25565"));
        switch (mode) {
            case "demo" -> a.demo();
            case "bench" -> a.bench(
                    System.getProperty("pumpkinpatch.bench.label", "bench"),
                    Integer.getInteger("pumpkinpatch.bench.warmup", 30),
                    Integer.getInteger("pumpkinpatch.bench.seconds", 60),
                    Integer.getInteger("pumpkinpatch.bench.pingEvery", 0));
            case "radar" -> a.radar(
                    Integer.getInteger("pumpkinpatch.bench.settle", 10),
                    Integer.getInteger("pumpkinpatch.bench.seconds", 30));
            default -> throw new IllegalArgumentException("unknown autopilot " + mode);
        }
        return a;
    }

    // ---- scenarios ----

    private void demo() {
        connect();
        waitTicks(60);
        note("session 1 instances", this::states);
        shot("01-joined");
        press("example:ping", "ping");
        waitTicks(20);
        shot("02-ping-response");
        note("after ping", this::states);
        press("example:trap", "trap");
        waitTicks(40);
        shot("03-trap-faulted");
        note("after trap", this::states);
        press("example:ping", "ping");
        waitTicks(20);
        shot("04-ping-after-trap");
        note("after ping 2", this::states);
        press("example:spin", "spin");
        waitTicks(40);
        shot("05-spin-stopped");
        note("after spin", this::states);
        note("client thread interrupted", () -> String.valueOf(Thread.currentThread().isInterrupted()));
        press("example:ping", "ping");
        waitTicks(20);
        shot("06-ping-after-spin");
        note("instance identity", this::identities);
        disconnect();
        waitTicks(40);
        connect();
        waitTicks(60);
        note("session 2 instances", this::states);
        note("instance identity", this::identities);
        press("example:ping", "ping");
        waitTicks(20);
        shot("07-reconnected-ping");
        note("after reconnect ping", this::states);
        note("faults", () -> patch.faults().stream().map(f -> f.modId() + " " + f.kind() + " session "
                + f.sessionId() + ": " + f.message()).collect(Collectors.joining(" | ")));
        note("perf", () -> summary(patch.perf()));
        step(mc -> {
            writeReport("demo.txt");
            return true;
        });
        quit();
    }

    private void bench(String label, int warmupSeconds, int seconds, int pingEvery) {
        connect();
        waitTicks(warmupSeconds * 20);
        step(mc -> {
            System.gc();
            probe.reset();
            if (patch != null) {
                patch.perf().reset();
            }
            return true;
        });
        for (int t = 0; t < seconds * 20; t++) {
            if (pingEvery > 0 && t % pingEvery == 0) {
                press("example:ping", "ping");
            } else {
                waitTicks(1);
            }
        }
        step(mc -> {
            var mem = ManagementFactory.getMemoryMXBean();
            long before = mem.getHeapMemoryUsage().getUsed();
            System.gc();
            long after = mem.getHeapMemoryUsage().getUsed();
            List<Long> heap = new ArrayList<>(probe.heapSamples());
            heap.sort(Long::compare);
            report.add("label: " + label);
            report.add("engine: " + (patch == null ? "none (baseline, host not installed)" : patch.runtime().describe()));
            report.add("active instances: " + (patch == null ? 0 : patch.instances().stream()
                    .filter(i -> i.state() == ComponentInstance.State.ACTIVE).count()));
            report.add("warmup s: " + warmupSeconds + ", measured s: " + seconds + ", fps limit: "
                    + mc.options.framerateLimit().get() + ", vsync: " + mc.options.enableVsync().get());
            report.add(String.format(Locale.ROOT, "heap used: median %.1f MiB, max %.1f MiB (1 Hz samples), "
                    + "after GC %.1f MiB (before GC %.1f MiB)",
                    heap.isEmpty() ? 0 : heap.get(heap.size() / 2) / 1048576.0,
                    heap.isEmpty() ? 0 : heap.getLast() / 1048576.0, after / 1048576.0, before / 1048576.0));
            report.add(summary(probe.perf));
            if (patch != null) {
                report.add(summary(patch.perf()));
                report.add("catalog: " + patch.catalog().entries().stream()
                        .map(e -> String.format(Locale.ROOT, "%s %s compile %.1f ms", e.id(), e.status(),
                                e.compileNanos() / 1e6)).collect(Collectors.joining(", ")));
                report.add("faults: " + patch.faults());
            }
            return true;
        });
        step(mc -> {
            writeReport("bench-" + label + ".txt");
            writeFile("bench-" + label + ".json", "{\"scenario\":" + BenchJson.str(label) + ",\"environment\":"
                    + BenchJson.environment(mc, patch, warmupSeconds, seconds) + ",\"instances\":"
                    + BenchJson.instances(patch) + ",\"perf\":{\"probe\":" + probe.perf.toJson() + ",\"host\":"
                    + (patch == null ? "null" : patch.perf().toJson()) + "}}");
            return true;
        });
        quit();
    }

    /**
     * The example:radar sweep. One launch, several phases. Each phase applies server-side benchmark
     * controls, lets the game settle, then measures. Results go to radar-<engine>.json.
     */
    private void radar(int settleSeconds, int seconds) {
        List<String> phases = new ArrayList<>();
        connect();
        waitTicks(200);
        control(3, 20);
        control(2, 0);
        control(1, 0);
        press("example:radar", "toggle");
        phase(phases, "radar-off", "{\"entities\":0,\"waypoints\":0,\"broadcast_period_ticks\":20,\"radar\":false}",
                settleSeconds, seconds);
        press("example:radar", "toggle");
        for (int n : new int[] {0, 32, 128, 256}) {
            control(1, n);
            phase(phases, "entities-" + n, "{\"entities\":" + n
                    + ",\"waypoints\":0,\"broadcast_period_ticks\":20,\"radar\":true}", settleSeconds, seconds);
        }
        control(2, 200);
        phase(phases, "entities-256-waypoints-200",
                "{\"entities\":256,\"waypoints\":200,\"broadcast_period_ticks\":20,\"radar\":true}",
                settleSeconds, seconds);
        control(3, 1);
        phase(phases, "entities-256-waypoints-200-broadcast-20hz",
                "{\"entities\":256,\"waypoints\":200,\"broadcast_period_ticks\":1,\"radar\":true}",
                settleSeconds, seconds);
        control(3, 20);
        control(2, 0);
        control(1, 0);
        step(mc -> {
            String engine = System.getProperty("pumpkinpatch.radar.label",
                    System.getProperty("pumpkinpatch.engine", "compiler"));
            String json = "{\"scenario\":\"radar\",\"environment\":"
                    + BenchJson.environment(mc, patch, settleSeconds, seconds) + ",\"catalog\":" + catalogJson()
                    + ",\"faults\":" + patch.faults().size() + ",\"phases\":[" + String.join(",", phases) + "]}";
            writeFile("radar-" + engine + ".json", json);
            return true;
        });
        quit();
    }

    /** Sends a radar benchmark control: 1 entities, 2 waypoints, 3 broadcast period. */
    private void control(int op, int value) {
        step(mc -> {
            byte[] payload = java.nio.ByteBuffer.allocate(5).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .put((byte) op).putInt(value).array();
            if (!patch.sendAsMod("example:radar", "bench", payload)) {
                LOG.warn("[PumpkinPatch autopilot] example:radar has no server route; bench control not sent");
            }
            return true;
        });
        waitTicks(10);
    }

    private void phase(List<String> phases, String label, String config, int settleSeconds, int seconds) {
        waitTicks(settleSeconds * 20);
        step(mc -> {
            System.gc();
            probe.reset();
            patch.perf().reset();
            LOG.info("[PumpkinPatch autopilot] measuring {}", label);
            return true;
        });
        waitTicks(seconds * 20);
        step(mc -> {
            String heap = BenchJson.heap(probe.heapSamples());
            phases.add("{\"phase\":" + BenchJson.str(label) + ",\"config\":" + config + ",\"heap\":" + heap
                    + ",\"instances\":" + BenchJson.instances(patch) + ",\"perf\":" + patch.perf().toJson() + "}");
            return true;
        });
    }

    private String catalogJson() {
        return patch.catalog().entries().stream()
                .map(e -> String.format(java.util.Locale.ROOT, "{\"id\":%s,\"status\":%s,\"compile_ms\":%.2f,\"bytes\":%d}",
                        BenchJson.str(e.id()), BenchJson.str(e.status().name()), e.compileNanos() / 1e6,
                        e.bytes() == null ? 0 : e.bytes().length))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private void writeFile(String name, String content) {
        try {
            Files.createDirectories(out);
            Files.writeString(out.resolve(name), content);
            LOG.info("[PumpkinPatch autopilot] wrote {}", out.resolve(name));
        } catch (IOException e) {
            LOG.error("[PumpkinPatch autopilot] cannot write {}", name, e);
        }
    }

    // ---- steps ----

    private void step(Step s) {
        steps.addLast(s);
    }

    private void waitTicks(int n) {
        step(mc -> {
            if (wait == 0) {
                wait = n;
            }
            return --wait == 0;
        });
    }

    private void connect() {
        step(mc -> {
            var screen = mc.gui.screen();
            if (mc.player == null && screen != null && !(screen instanceof ConnectScreen)
                    && mc.gui.overlay() == null) {
                ConnectScreen.startConnecting(screen, mc, ServerAddress.parseString(server),
                        new ServerData("Pumpkin", server, ServerData.Type.OTHER), false, null);
                return true;
            }
            return false;
        });
        // Joined, the terrain screen is gone, and the host has a session.
        step(mc -> mc.player != null && mc.level != null && mc.gui.screen() == null
                && (patch == null || patch.session() != null));
    }

    private void disconnect() {
        step(mc -> {
            // The same order as the pause menu's Disconnect button: close the connection, then leave.
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("Pumpkin Patch autopilot"));
            }
            mc.disconnectWithProgressScreen();
            return true;
        });
        step(mc -> mc.player == null && mc.gui.screen() != null);
    }

    private void press(String modId, String action) {
        step(mc -> {
            for (var b : keys.bound()) {
                if (b.action().modId().equals(modId) && b.action().action().id().equals(action)) {
                    var key = KeyMappingHelper.getBoundKeyOf(b.mapping());
                    KeyMapping.click(key);
                    KeyMapping.set(key, true);
                    release.add(b.mapping());
                    LOG.info("[PumpkinPatch autopilot] pressed {} for {}.{}", key.getName(), modId, action);
                    return true;
                }
            }
            LOG.warn("[PumpkinPatch autopilot] no key for {}.{}", modId, action);
            return true;
        });
    }

    private void shot(String name) {
        step(mc -> {
            // Saved under screenshots/ with a timestamped name, in the order of these steps.
            Screenshot.grab(mc, false);
            report.add("screenshot " + name + " at " + java.time.LocalTime.now());
            return true;
        });
    }

    private void note(String label, java.util.function.Supplier<String> value) {
        step(mc -> {
            String line = label + ": " + value.get();
            report.add(line);
            LOG.info("[PumpkinPatch autopilot] {}", line);
            return true;
        });
    }

    private void quit() {
        step(mc -> {
            mc.stop();
            return true;
        });
    }

    private String states() {
        return patch.instances().stream().map(i -> i.id() + "=" + i.state()).collect(Collectors.joining(", "))
                + " (session " + (patch.session() == null ? "-" : patch.session().id() + " " + patch.session().kind())
                + ")";
    }

    private String identities() {
        return patch.instances().stream()
                .map(i -> i.id() + "@" + Integer.toHexString(System.identityHashCode(i)) + "/s" + i.sessionId())
                .collect(Collectors.joining(", "));
    }

    static String summary(Perf perf) {
        return perf.summarize().entrySet().stream()
                .map(e -> "  " + e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("\n", "\n", ""));
    }

    private void writeReport(String name) {
        try {
            Files.createDirectories(out);
            Files.write(out.resolve(name), report);
            LOG.info("[PumpkinPatch autopilot] wrote {}", out.resolve(name));
        } catch (IOException e) {
            LOG.error("[PumpkinPatch autopilot] cannot write report", e);
        }
    }

    /** Called at the end of each client tick. */
    public void tick(Minecraft mc) {
        for (KeyMapping m : release) {
            KeyMapping.set(KeyMappingHelper.getBoundKeyOf(m), false);
        }
        release.clear();
        Step s = steps.peekFirst();
        if (s != null && s.tick(mc)) {
            steps.pollFirst();
        }
    }
}
