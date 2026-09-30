package dev.pumpkinmc.patch.core.network;

import java.util.List;

/** One frame of the {@code pumpkin:mux} wire format, version 1. */
public sealed interface MuxFrame {
    int MUX_VERSION = 1;

    record HelloMod(
            String id, String versionReq, String world, boolean required, String protocol, List<String> channels) {}

    enum Status {
        AVAILABLE,
        MISSING,
        INCOMPATIBLE_VERSION,
        INCOMPATIBLE_WORLD,
        INCOMPATIBLE_PROTOCOL,
        REJECTED_LOCALLY,
        DISABLED_BY_USER
    }

    record ReplyMod(String id, Status status, String version, String detail) {}

    record Route(String modId, int routeId) {}

    record Hello(int muxVersion, String serverId, List<HelloMod> mods) implements MuxFrame {}

    record Reply(int muxVersion, List<ReplyMod> mods) implements MuxFrame {}

    record Accept(boolean join, String message, List<Route> routes) implements MuxFrame {}

    record Data(int route, int channel, byte[] payload) implements MuxFrame {}

    record Close(int route, String reason) implements MuxFrame {}
}
