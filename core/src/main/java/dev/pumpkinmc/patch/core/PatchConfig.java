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
        String hostVersion) {

    public static PatchConfig defaults() {
        return new PatchConfig(
                RuntimeLimits.defaults(), 16 * 1024, 64 * 1024, 64, 32 * 1024, 20, 256, 512, 256, 256, 5_000,
                "0.1.0");
    }

    public PatchConfig withLimits(RuntimeLimits l) {
        return new PatchConfig(l, maxOutboundPayload, maxInboundPayload, maxSendsPerTick, maxSendBytesPerTick,
                maxLogLinesPerTick, maxEventsPerBatch, maxDrawCommands, maxTextLength, maxNearbyEntities,
                catalogWaitMillis, hostVersion);
    }
}
