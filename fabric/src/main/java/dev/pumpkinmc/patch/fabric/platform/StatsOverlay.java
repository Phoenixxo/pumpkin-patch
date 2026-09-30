package dev.pumpkinmc.patch.fabric.platform;

import dev.pumpkinmc.patch.core.PumpkinPatch;
import dev.pumpkinmc.patch.core.component.ComponentInstance;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A live per-mod cost panel, toggled with {@code /pumpkinpatch hud}. It answers "which component
 * is costing frame time" while playing. Figures update once a second.
 */
public final class StatsOverlay {
    private record Prev(long guestNanos, long calls, long bytesIn, long bytesOut) {}

    private final PumpkinPatch patch;
    private final Map<ComponentInstance, Prev> prev = new HashMap<>();
    private List<String> lines = List.of();
    private boolean enabled;
    private int ticks;

    public StatsOverlay(PumpkinPatch patch) {
        this.patch = patch;
    }

    public boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    /** Called at the end of each client tick. */
    public void tick() {
        if (!enabled || ++ticks < 20) {
            return;
        }
        List<String> out = new ArrayList<>();
        out.add("Pumpkin Patch  (per second, guest time per tick)");
        for (ComponentInstance i : patch.instances()) {
            Prev p = prev.getOrDefault(i, new Prev(i.guestNanos, i.calls, i.bytesIn, i.bytesOut));
            double usPerTick = (i.guestNanos - p.guestNanos()) / 1e3 / ticks;
            long mem = i.guest() == null ? 0 : i.guest().linearMemoryBytes();
            out.add(String.format(Locale.ROOT, "%-16s %-7s %7.1f us/t %4d calls  mem %5.0f KiB  in %5d B  out %5d B",
                    i.id(), i.state(), usPerTick, i.calls - p.calls(), mem / 1024.0, i.bytesIn - p.bytesIn(),
                    i.bytesOut - p.bytesOut()));
            prev.put(i, new Prev(i.guestNanos, i.calls, i.bytesIn, i.bytesOut));
        }
        prev.keySet().retainAll(patch.instances());
        lines = out;
        ticks = 0;
    }

    public void draw(GuiGraphicsExtractor g) {
        if (!enabled) {
            return;
        }
        var font = Minecraft.getInstance().font;
        int y = 4;
        int width = lines.stream().mapToInt(font::width).max().orElse(0);
        g.fill(2, 2, 6 + width, 4 + lines.size() * 10, 0xA0000000);
        for (String line : lines) {
            g.text(font, line, 4, y, 0xFFE0E0E0, false);
            y += 10;
        }
    }
}
