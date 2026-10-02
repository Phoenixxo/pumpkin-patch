package dev.pumpkinmc.patch.core.host;

import dev.pumpkinmc.patch.core.model.Model.EntitySnapshot;
import dev.pumpkinmc.patch.core.model.Model.PlayerSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * What the view imports read during a call on a worker thread. Taken once per tick on the client
 * thread and shared by every instance updated that tick, so a worker never touches game objects.
 *
 * @param player the local player, or {@code null} when there is none
 * @param dimension the current dimension, or {@code null} when there is none
 * @param entities entities within {@code radius} of the player, as many as the host allows, or
 *     {@code null} when no instance has read entities lately and none were collected
 * @param radius how far {@code entities} reaches
 * @param gameTick the tick the snapshot was taken on
 */
public record ViewSnapshot(
        PlayerSnapshot player, String dimension, List<EntitySnapshot> entities, double radius, long gameTick) {

    /**
     * The snapshot's entities within {@code radius} of the player, at most {@code max}. A radius
     * beyond the snapshot's own is answered with what the snapshot holds.
     */
    public List<EntitySnapshot> nearby(double radius, int max) {
        return nearby(entities, radius, max);
    }

    /** {@link #nearby(double, int)} over {@code from}, entities collected for this snapshot's player. */
    public List<EntitySnapshot> nearby(List<EntitySnapshot> from, double radius, int max) {
        if (player == null || max <= 0 || from == null) {
            return List.of();
        }
        double x = player.pos().x();
        double y = player.pos().y();
        double z = player.pos().z();
        List<EntitySnapshot> out = new ArrayList<>(Math.min(max, from.size()));
        for (EntitySnapshot e : from) {
            if (out.size() == max) {
                break;
            }
            // The live lookup inflates the player's box by radius, so this is a box test too.
            if (Math.abs(e.pos().x() - x) <= radius + 1 && Math.abs(e.pos().y() - y) <= radius + 2
                    && Math.abs(e.pos().z() - z) <= radius + 1) {
                out.add(e);
            }
        }
        return out;
    }
}
