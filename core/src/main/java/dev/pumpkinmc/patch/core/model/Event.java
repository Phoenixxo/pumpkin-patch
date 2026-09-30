package dev.pumpkinmc.patch.core.model;

import dev.pumpkinmc.patch.core.model.Model.SessionInfo;
import dev.pumpkinmc.patch.core.model.Model.WorldRef;

/** An immutable notification delivered to a component in a {@code handle-events} batch. */
public sealed interface Event {
    record SessionStarted(SessionInfo info) implements Event {}

    record WorldChanged(WorldRef world) implements Event {}

    record Tick(long gameTick) implements Event {}

    record NetMessage(String channel, byte[] payload) implements Event {}

    record Action(String actionId, boolean pressed) implements Event {}

    record SessionEnding() implements Event {}
}
