package dev.pumpkinmc.patch.jvm.guests;

import dev.pumpkinmc.patch.core.model.Event;
import dev.pumpkinmc.patch.core.model.HostError;
import dev.pumpkinmc.patch.core.model.Model.DrawCommand;
import dev.pumpkinmc.patch.core.model.Model.EntitySnapshot;
import dev.pumpkinmc.patch.core.model.Model.EventKind;
import dev.pumpkinmc.patch.core.model.Model.FrameInfo;
import dev.pumpkinmc.patch.core.model.Model.FrameOutput;
import dev.pumpkinmc.patch.core.model.Model.InitInfo;
import dev.pumpkinmc.patch.core.model.Model.InitResult;
import dev.pumpkinmc.patch.core.model.Model.PlayerSnapshot;
import dev.pumpkinmc.patch.core.runtime.Runtime.HostImports;
import dev.pumpkinmc.patch.core.runtime.Runtime.LogImports;
import dev.pumpkinmc.patch.jvm.JavaGuest;
import dev.pumpkinmc.patch.jvm.guests.RadarProtocol.Down;
import dev.pumpkinmc.patch.jvm.guests.RadarProtocol.Malformed;
import dev.pumpkinmc.patch.jvm.guests.RadarProtocol.RemotePlayer;
import dev.pumpkinmc.patch.jvm.guests.RadarProtocol.ServerStats;
import dev.pumpkinmc.patch.jvm.guests.RadarProtocol.Up;
import dev.pumpkinmc.patch.jvm.guests.RadarProtocol.Waypoint;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Java port of {@code guests/radar}, kept line for line with the Rust so both do the same work:
 * nearby entities from the view, waypoints and remote players from the server, text widths from
 * the HUD, and a digest so an unchanged radar returns {@code unchanged}.
 */
public final class RadarGuest extends JavaGuest {
    private static final double[] ZOOMS = {16.0, 32.0, 64.0};
    private static final int SIZE = 96;
    private static final int MAX_ENTITIES = 256;
    private static final int LIST_ROWS = 5;

    private enum Kind {
        HOSTILE(0xFFFF_4040),
        PASSIVE(0xFF40_E040),
        PLAYER(0xFF40_A0FF),
        ITEM(0xFFFF_E040),
        OTHER(0xFFC0_C0C0);

        final int argb;

        Kind(int argb) {
            this.argb = argb;
        }

        static Kind of(String kind) {
            String k = kind.startsWith("minecraft:") ? kind.substring("minecraft:".length()) : kind;
            return switch (k) {
                case "player" -> PLAYER;
                case "item", "experience_orb" -> ITEM;
                case "zombie", "skeleton", "creeper", "spider", "cave_spider", "enderman", "witch", "slime", "husk",
                        "stray", "drowned", "phantom", "blaze", "ghast", "magma_cube", "piglin_brute",
                        "wither_skeleton", "zombified_piglin", "pillager", "vindicator", "evoker", "ravager",
                        "silverfish", "endermite", "guardian", "hoglin", "zoglin", "warden", "breeze", "bogged" ->
                    HOSTILE;
                case "pig", "cow", "sheep", "chicken", "rabbit", "horse", "donkey", "mule", "llama", "cat", "wolf",
                        "fox", "bee", "goat", "villager", "turtle", "parrot", "mooshroom", "panda", "axolotl", "frog",
                        "camel", "sniffer", "armadillo", "strider", "cod", "salmon", "squid", "glow_squid", "dolphin",
                        "bat", "allay", "ocelot", "polar_bear" -> PASSIVE;
                default -> OTHER;
            };
        }
    }

    private record Blip(Kind kind, double dx, double dz) {}

    private boolean enabled = true;
    private int zoom = 1;
    private String dimension = "";
    private final double[] pos = new double[3];
    private float yaw;
    private final List<Blip> entities = new ArrayList<>();
    private List<Waypoint> waypoints = new ArrayList<>();
    private List<RemotePlayer> remote = new ArrayList<>();
    private ServerStats stats = ServerStats.NONE;
    private long seq;
    private String notice = "";
    private boolean net;
    private int added;
    /** A digest of everything drawn, so an unchanged radar returns {@code unchanged}. */
    private long drawn;
    private boolean dirty;

    public RadarGuest(HostImports host) {
        super(host);
    }

    private void send(Up m) {
        try {
            host.net().send(m.channel(), m.bytes());
        } catch (HostError e) {
            notice = "send " + m.channel() + ": " + e.getMessage();
            dirty = true;
        }
    }

    @Override
    protected InitResult onInit(InitInfo info) {
        host.log().log(LogImports.Level.INFO, "radar " + info.modVersion() + " started, server route: "
                + info.session().netNegotiated());
        net = info.session().netNegotiated();
        return new InitResult(Set.of(EventKind.TICK, EventKind.WORLD, EventKind.INPUT));
    }

    @Override
    protected void onEvents(List<Event> batch) {
        for (Event event : batch) {
            switch (event) {
                case Event.SessionStarted _ when net -> send(Up.sync());
                case Event.WorldChanged w -> {
                    // A new world epoch: everything cached belongs to the old world.
                    dimension = w.world().dimension();
                    waypoints = new ArrayList<>();
                    remote = new ArrayList<>();
                    dirty = true;
                    if (net) {
                        send(Up.sync());
                    }
                }
                case Event.Action a when a.pressed() -> {
                    switch (a.actionId()) {
                        case "toggle" -> {
                            enabled = !enabled;
                            dirty = true;
                        }
                        case "zoom" -> {
                            zoom = (zoom + 1) % ZOOMS.length;
                            dirty = true;
                        }
                        case "mark" -> {
                            if (net) {
                                added++;
                                send(Up.addWaypoint("WP" + added, pos.clone()));
                            }
                        }
                        case "clear" -> {
                            if (net) {
                                send(Up.clearWaypoints());
                            }
                        }
                        default -> {}
                    }
                }
                case Event.NetMessage m -> onMessage(m);
                default -> {}
            }
        }
        if (!enabled) {
            return;
        }
        try {
            PlayerSnapshot p = host.view().localPlayer();
            pos[0] = p.pos().x();
            pos[1] = p.pos().y();
            pos[2] = p.pos().z();
            yaw = p.yaw();
            if (dimension.isEmpty()) {
                dimension = p.world().dimension();
            }
        } catch (HostError ignored) {
            // Keep the last position, like the Rust component.
        }
        double radius = ZOOMS[zoom];
        entities.clear();
        try {
            for (EntitySnapshot e : host.view().nearbyEntities(radius, MAX_ENTITIES)) {
                entities.add(new Blip(Kind.of(e.kind()), e.pos().x() - pos[0], e.pos().z() - pos[2]));
            }
        } catch (HostError ignored) {
            // No entities this tick.
        }
    }

    private void onMessage(Event.NetMessage m) {
        Down down;
        try {
            down = RadarProtocol.decode(m.channel(), m.payload());
        } catch (Malformed e) {
            host.log().log(LogImports.Level.INFO, "dropped a malformed radar message");
            return;
        }
        switch (down) {
            case Down.Waypoints w when w.dimension().equals(dimension) -> {
                waypoints = w.list();
                dirty = true;
            }
            case Down.Players p when p.dimension().equals(dimension) -> {
                remote = p.list();
                stats = p.stats();
                seq = p.seq();
                dirty = true;
            }
            case Down.Notice n -> {
                notice = n.text();
                dirty = true;
            }
            // A message for a world the player already left.
            default -> {}
        }
    }

    @Override
    protected FrameOutput onRender(FrameInfo frame) {
        if (!enabled) {
            if (drawn == 0) {
                return new FrameOutput.Unchanged();
            }
            drawn = 0;
            return new FrameOutput.Clear();
        }
        List<DrawCommand> commands = draw(frame.guiWidth());
        long digest = digest(commands);
        if (digest == drawn && !dirty) {
            return new FrameOutput.Unchanged();
        }
        drawn = digest;
        dirty = false;
        return new FrameOutput.Commands(commands);
    }

    /** Projects a world offset onto the radar so the player's heading points up. */
    private static double[] project(double dx, double dz, float yaw, double scale) {
        double r = Math.toRadians(yaw);
        double sin = Math.sin(r);
        double cos = Math.cos(r);
        double forward = -dx * sin + dz * cos;
        double right = -dx * cos - dz * sin;
        return new double[] {right * scale, -forward * scale};
    }

    private List<DrawCommand> draw(int guiWidth) {
        int x0 = guiWidth - SIZE - 6;
        int y0 = 6;
        double half = SIZE / 2.0;
        double radius = ZOOMS[zoom];
        double scale = half / radius;
        double cx = x0 + half;
        double cy = y0 + half;
        List<DrawCommand> out = new ArrayList<>(8 + entities.size() + waypoints.size() + remote.size());

        out.add(new DrawCommand.FillRect(x0 - 1, y0 - 1, SIZE + 2, SIZE + 2, 0xFF30_3030));
        out.add(new DrawCommand.FillRect(x0, y0, SIZE, SIZE, 0xB000_0000));
        for (Blip b : entities) {
            double[] p = project(b.dx(), b.dz(), yaw, scale);
            if (Math.abs(p[0]) < half - 1.0 && Math.abs(p[1]) < half - 1.0) {
                out.add(new DrawCommand.FillRect((int) (cx + p[0]) - 1, (int) (cy + p[1]) - 1, 2, 2, b.kind().argb));
            }
        }
        for (Waypoint w : waypoints) {
            marker(out, w.pos(), 0xFFFF_AA00, cx, cy, half, scale);
        }
        for (RemotePlayer p : remote) {
            marker(out, p.pos(), Kind.PLAYER.argb, cx, cy, half, scale);
        }
        out.add(new DrawCommand.FillRect((int) cx - 1, (int) cy - 2, 2, 4, 0xFFFF_FFFF));

        int y = y0 + SIZE + 3;
        out.add(text(x0, y, (int) radius + "m  " + entities.size() + " ent  " + waypoints.size() + " wp", 0xFFE0_E0E0));
        y += 10;

        // Nearest waypoints, distances right-aligned with hud.measure-text.
        record Near(double distance, Waypoint waypoint) {}
        List<Near> nearest = new ArrayList<>(waypoints.size());
        for (Waypoint w : waypoints) {
            double dx = w.pos()[0] - pos[0];
            double dz = w.pos()[2] - pos[2];
            nearest.add(new Near(Math.sqrt(dx * dx + dz * dz), w));
        }
        nearest.sort(Comparator.comparingDouble(Near::distance));
        if (nearest.size() > LIST_ROWS) {
            nearest = nearest.subList(0, LIST_ROWS);
        }
        List<String> distances = new ArrayList<>(nearest.size());
        for (Near n : nearest) {
            distances.add(String.format(Locale.ROOT, "%.0fm", n.distance()));
        }
        List<Integer> widths;
        try {
            widths = host.hud().measureText(distances);
        } catch (HostError e) {
            widths = new ArrayList<>();
            for (int i = 0; i < distances.size(); i++) {
                widths.add(0);
            }
        }
        for (int i = 0; i < nearest.size() && i < widths.size(); i++) {
            out.add(text(x0, y, nearest.get(i).waypoint().name(), 0xFFFF_AA00));
            out.add(text(x0 + SIZE - widths.get(i), y, distances.get(i), 0xFFFF_FFFF));
            y += 10;
        }
        if (net) {
            out.add(text(x0, y, String.format(Locale.ROOT, "srv %.1f mspt #%d", stats.mspt(), seq), 0xFF90_90A0));
        } else {
            out.add(text(x0, y, "no server route", 0xFF90_90A0));
        }
        if (!notice.isEmpty()) {
            out.add(text(x0, y + 10, notice, 0xFFFF_8080));
        }
        return out;
    }

    private void marker(List<DrawCommand> out, double[] p, int argb, double cx, double cy, double half, double scale) {
        double[] q = project(p[0] - pos[0], p[2] - pos[2], yaw, scale);
        double clamp = half - 2.0;
        out.add(new DrawCommand.FillRect((int) (cx + Math.clamp(q[0], -clamp, clamp)) - 1,
                (int) (cy + Math.clamp(q[1], -clamp, clamp)) - 1, 3, 3, argb));
    }

    private static DrawCommand text(int x, int y, String t, int argb) {
        return new DrawCommand.Text(x, y, t, argb, true);
    }

    /** FNV-1a over the drawn commands. */
    private static long digest(List<DrawCommand> commands) {
        long h = 0xcbf2_9ce4_8422_2325L;
        for (DrawCommand c : commands) {
            switch (c) {
                case DrawCommand.FillRect r -> {
                    h = eat(h, 1);
                    h = eat(h, ((long) r.x() << 32) | Integer.toUnsignedLong(r.y()));
                    h = eat(h, ((long) r.w() << 32) | Integer.toUnsignedLong(r.h()));
                    h = eat(h, Integer.toUnsignedLong(r.argb()));
                }
                case DrawCommand.Text t -> {
                    h = eat(h, 2);
                    h = eat(h, ((long) t.x() << 32) | Integer.toUnsignedLong(t.y()));
                    for (byte b : t.text().getBytes(StandardCharsets.UTF_8)) {
                        h = eat(h, b & 0xFF);
                    }
                }
            }
        }
        return h | 1;
    }

    private static long eat(long h, long v) {
        return (h ^ v) * 0x0100_0000_01b3L;
    }
}
