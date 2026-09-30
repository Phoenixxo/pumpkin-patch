package dev.pumpkinmc.patch.core.dispatch;

/** Something that happened off the drain points, waiting for the next tick. */
public sealed interface Inbound {
    record Frame(byte[] bytes, long receivedNanos) implements Inbound {}

    record Action(String modId, String actionId, boolean pressed, long atNanos) implements Inbound {}

    record WorldChanged(String dimension) implements Inbound {}
}
