package dev.pumpkinmc.patch.fabric.bench;

import dev.pumpkinmc.patch.core.diag.Perf;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures the running client independently of the host: client tick duration, the interval
 * between frames, and heap use. Baseline runs use this probe with no host installed.
 */
public final class PerfProbe {
    public final Perf perf;
    private long tickStart;
    private long lastFrame;
    private final List<Long> heapSamples = new ArrayList<>();
    private long nextHeapSample;

    public PerfProbe(Perf perf) {
        this.perf = perf;
    }

    public void startTick() {
        tickStart = System.nanoTime();
    }

    public void endTick() {
        if (tickStart != 0) {
            perf.record("client.tick", System.nanoTime() - tickStart);
        }
        long now = System.nanoTime();
        if (now >= nextHeapSample) {
            nextHeapSample = now + 1_000_000_000L;
            synchronized (heapSamples) {
                heapSamples.add(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            }
        }
    }

    public void frame() {
        long now = System.nanoTime();
        if (lastFrame != 0) {
            perf.record("client.frame", now - lastFrame);
        }
        lastFrame = now;
    }

    public void reset() {
        perf.reset();
        lastFrame = 0;
        tickStart = 0;
        synchronized (heapSamples) {
            heapSamples.clear();
        }
    }

    public List<Long> heapSamples() {
        synchronized (heapSamples) {
            return List.copyOf(heapSamples);
        }
    }
}
