package dev.pumpkinmc.patch.core.diag;

/** Why an instance stopped. Kept in diagnostics and shown to the player. */
public record FaultRecord(
        String modId, String modVersion, long sessionId, String kind, String message, String phase, long timeMillis) {}
