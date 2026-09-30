package dev.pumpkinmc.patch.jvm.guests;

import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Java port of {@code examples/radar/protocol}. Integers and floats are little-endian; strings are
 * a u16 length and UTF-8.
 */
final class RadarProtocol {
    private RadarProtocol() {}

    record Waypoint(long id, String owner, String name, double[] pos) {}

    record RemotePlayer(String name, double[] pos) {}

    record ServerStats(float mspt, long handlerMicros, long broadcastMicros) {
        static final ServerStats NONE = new ServerStats(0, 0, 0);
    }

    /** Server to client. */
    sealed interface Down {
        record Waypoints(String dimension, List<Waypoint> list) implements Down {}

        record Players(String dimension, long seq, ServerStats stats, List<RemotePlayer> list) implements Down {}

        record Notice(String text) implements Down {}
    }

    static final class Malformed extends Exception {
        Malformed() {
            super("malformed radar message", null, false, false);
        }
    }

    /** One client-to-server message: its channel and bytes. */
    record Up(String channel, byte[] bytes) {
        static Up sync() {
            return new Up("sync", new byte[0]);
        }

        static Up addWaypoint(String name, double[] pos) {
            var w = new Writer();
            w.str(name);
            w.pos(pos);
            return new Up("wp-add", w.bytes());
        }

        static Up clearWaypoints() {
            return new Up("wp-clear", new byte[0]);
        }
    }

    static Down decode(String channel, byte[] bytes) throws Malformed {
        var r = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        try {
            Down m = switch (channel) {
                case "waypoints" -> {
                    String dimension = str(r);
                    int n = Short.toUnsignedInt(r.getShort());
                    List<Waypoint> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        list.add(new Waypoint(Integer.toUnsignedLong(r.getInt()), str(r), str(r), pos(r)));
                    }
                    yield new Down.Waypoints(dimension, list);
                }
                case "players" -> {
                    String dimension = str(r);
                    long seq = Integer.toUnsignedLong(r.getInt());
                    var stats = new ServerStats(r.getFloat(), Integer.toUnsignedLong(r.getInt()),
                            Integer.toUnsignedLong(r.getInt()));
                    int n = Short.toUnsignedInt(r.getShort());
                    List<RemotePlayer> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        list.add(new RemotePlayer(str(r), pos(r)));
                    }
                    yield new Down.Players(dimension, seq, stats, list);
                }
                case "notice" -> new Down.Notice(str(r));
                default -> throw new Malformed();
            };
            if (r.hasRemaining()) {
                throw new Malformed();
            }
            return m;
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            throw new Malformed();
        }
    }

    private static String str(ByteBuffer r) {
        int n = Short.toUnsignedInt(r.getShort());
        byte[] b = new byte[n];
        r.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static double[] pos(ByteBuffer r) {
        return new double[] {r.getDouble(), r.getDouble(), r.getDouble()};
    }

    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void str(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            int n = Math.min(b.length, 0xFFFF);
            out.write(n & 0xFF);
            out.write(n >>> 8);
            out.write(b, 0, n);
        }

        void pos(double[] p) {
            var b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            for (double c : p) {
                b.putDouble(c);
            }
            out.writeBytes(b.array());
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }
}
