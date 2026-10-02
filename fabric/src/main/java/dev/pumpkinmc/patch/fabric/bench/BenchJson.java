package dev.pumpkinmc.patch.fabric.bench;

import dev.pumpkinmc.patch.core.PumpkinPatch;
import dev.pumpkinmc.patch.core.component.ComponentInstance;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** The JSON pieces a benchmark report is made of. Hand-written to keep the mod dependency-free. */
final class BenchJson {
    private BenchJson() {}

    static String str(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    static String version(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    /** Machine, versions, and client settings, so a result can be reproduced. */
    static String environment(Minecraft mc, PumpkinPatch patch, int warmupSeconds, int measureSeconds) {
        var rt = Runtime.getRuntime();
        return String.format(Locale.ROOT,
                "{\"os\":%s,\"arch\":%s,\"cpus\":%d,\"java\":%s,\"jvm\":%s,\"max_heap_mib\":%d,"
                        + "\"minecraft\":%s,\"fabric_loader\":%s,\"fabric_api\":%s,\"engine\":%s,\"workers\":%b,"
                        + "\"warmup_s\":%d,\"measure_s\":%d,\"fps_limit\":%d,\"vsync\":%b,\"window\":\"%dx%d\","
                        + "\"gui_scale\":%d,\"render_distance\":%d}",
                str(System.getProperty("os.name") + " " + System.getProperty("os.version")),
                str(System.getProperty("os.arch")), rt.availableProcessors(), str(System.getProperty("java.version")),
                str(System.getProperty("java.vm.name")), rt.maxMemory() >> 20, str(version("minecraft")),
                str(version("fabricloader")), str(version("fabric-api")),
                str(patch == null ? "none (host not installed)" : patch.runtime().describe()),
                patch != null && patch.config().workers(), warmupSeconds,
                measureSeconds, mc.options.framerateLimit().get(), mc.options.enableVsync().get(),
                mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.options.guiScale().get(),
                mc.options.renderDistance().get());
    }

    static String instances(PumpkinPatch patch) {
        if (patch == null) {
            return "[]";
        }
        return patch.instances().stream().map(BenchJson::instance).collect(Collectors.joining(",", "[", "]"));
    }

    private static String instance(ComponentInstance i) {
        long mem = i.guest() == null ? -1 : i.guest().linearMemoryBytes();
        return String.format(Locale.ROOT,
                "{\"id\":%s,\"state\":%s,\"calls\":%d,\"guest_ms\":%.3f,\"linear_memory_bytes\":%d,"
                        + "\"bytes_in\":%d,\"bytes_out\":%d,\"messages_in\":%d,\"messages_out\":%d,"
                        + "\"events_dropped\":%d,\"draw_commands_truncated\":%d}",
                str(i.id()), str(i.state().name()), i.calls, i.guestNanos / 1e6, mem, i.bytesIn, i.bytesOut,
                i.messagesIn, i.messagesOut, i.eventsDropped, i.drawCommandsTruncated);
    }

    /** Heap in MiB after a GC, plus the median and max of the 1 Hz samples taken while measuring. */
    static String heap(List<Long> samples) {
        var mem = ManagementFactory.getMemoryMXBean();
        long before = mem.getHeapMemoryUsage().getUsed();
        System.gc();
        long after = mem.getHeapMemoryUsage().getUsed();
        List<Long> sorted = samples.stream().sorted().toList();
        return String.format(Locale.ROOT,
                "{\"median_mib\":%.1f,\"max_mib\":%.1f,\"before_gc_mib\":%.1f,\"after_gc_mib\":%.1f,\"samples\":%d}",
                sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2) / 1048576.0,
                sorted.isEmpty() ? 0 : sorted.getLast() / 1048576.0, before / 1048576.0, after / 1048576.0,
                sorted.size());
    }
}
