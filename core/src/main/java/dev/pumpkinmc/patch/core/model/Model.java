package dev.pumpkinmc.patch.core.model;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** The small immutable records core shares with the runtime binders and the platform edge. */
public final class Model {
    private Model() {}

    public record Vec3(double x, double y, double z) {}

    public record WorldRef(String dimension, int epoch) {}

    public record PlayerSnapshot(
            UUID id, String name, Vec3 pos, float yaw, float pitch, float health, WorldRef world) {}

    public record EntitySnapshot(UUID id, String kind, Vec3 pos) {}

    public record SessionInfo(long sessionId, boolean pumpkinServer, boolean netNegotiated) {}

    public record InitInfo(SessionInfo session, String modVersion, String hostVersion) {}

    public enum EventKind {
        TICK,
        WORLD,
        INPUT
    }

    public record InitResult(Set<EventKind> subscriptions) {}

    public record FrameInfo(int guiWidth, int guiHeight, long gameTick) {}

    public sealed interface DrawCommand {
        record Text(int x, int y, String text, int argb, boolean shadow) implements DrawCommand {}

        record FillRect(int x, int y, int w, int h, int argb) implements DrawCommand {}
    }

    public sealed interface FrameOutput {
        record Unchanged() implements FrameOutput {}

        record Clear() implements FrameOutput {}

        record Commands(List<DrawCommand> commands) implements FrameOutput {}
    }
}
