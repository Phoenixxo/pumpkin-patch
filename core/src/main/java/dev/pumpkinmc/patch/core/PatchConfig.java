package dev.pumpkinmc.patch.core;

import dev.pumpkinmc.patch.core.runtime.Runtime.RuntimeLimits;

/** Host-wide limits. The defaults follow Architecture.md section 30. */
public record PatchConfig(
        RuntimeLimits limits,
        int maxOutboundPayload,
        int maxInboundPayload,
        int maxSendsPerTick,
        int maxSendBytesPerTick,
        int maxLogLinesPerTick,
        int maxEventsPerBatch,
        int maxDrawCommands,
        int maxTextLength,
        int maxNearbyEntities,
        long catalogWaitMillis,
        String hostVersion,
        boolean workers) {

    public static PatchConfig defaults() {
        return new PatchConfig(
                RuntimeLimits.defaults(), 16 * 1024, 64 * 1024, 64, 32 * 1024, 20, 256, 512, 256, 256, 5_000,
                "0.1.0", false);
    }

    public PatchConfig withLimits(RuntimeLimits l) {
        return new PatchConfig(l, maxOutboundPayload, maxInboundPayload, maxSendsPerTick, maxSendBytesPerTick,
                maxLogLinesPerTick, maxEventsPerBatch, maxDrawCommands, maxTextLength, maxNearbyEntities,
                catalogWaitMillis, hostVersion, workers);
    }

    /**
     * With {@code workers}, each instance's {@code update} runs on a worker thread against a view
     * snapshot taken on the client thread, and the client thread only applies the results.
     */
    public PatchConfig withWorkers(boolean workers) {
        return new PatchConfig(limits, maxOutboundPayload, maxInboundPayload, maxSendsPerTick, maxSendBytesPerTick,
                maxLogLinesPerTick, maxEventsPerBatch, maxDrawCommands, maxTextLength, maxNearbyEntities,
                catalogWaitMillis, hostVersion, workers);
    }
}
