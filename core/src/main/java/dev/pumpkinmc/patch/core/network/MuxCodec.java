package dev.pumpkinmc.patch.core.network;

import dev.pumpkinmc.patch.core.network.MuxFrame.Accept;
import dev.pumpkinmc.patch.core.network.MuxFrame.Close;
import dev.pumpkinmc.patch.core.network.MuxFrame.Data;
import dev.pumpkinmc.patch.core.network.MuxFrame.Hello;
import dev.pumpkinmc.patch.core.network.MuxFrame.HelloMod;
import dev.pumpkinmc.patch.core.network.MuxFrame.Reply;
import dev.pumpkinmc.patch.core.network.MuxFrame.ReplyMod;
import dev.pumpkinmc.patch.core.network.MuxFrame.Route;
import dev.pumpkinmc.patch.core.network.MuxFrame.Status;
import java.io.ByteArrayOutputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Encodes and decodes {@link MuxFrame}s. Every inbound byte is untrusted. */
public final class MuxCodec {
    public static final int MAX_MODS = 256;
    private static final int MAX_CHANNELS = 64;
    private static final int MAX_STRING = 32_767;

    private MuxCodec() {}

    public static final class MalformedFrame extends Exception {
        public MalformedFrame(String message) {
            super(message, null, false, false);
        }
    }

    public static byte[] encode(MuxFrame frame) {
        var out = new Writer();
        switch (frame) {
            case Hello h -> {
                out.varInt(0).varInt(h.muxVersion()).string(h.serverId()).varInt(h.mods().size());
                for (HelloMod m : h.mods()) {
                    out.string(m.id()).string(m.versionReq()).string(m.world()).bool(m.required());
                    out.string(m.protocol()).varInt(m.channels().size());
                    m.channels().forEach(out::string);
                }
            }
            case Reply r -> {
                out.varInt(1).varInt(r.muxVersion()).varInt(r.mods().size());
                for (ReplyMod m : r.mods()) {
                    out.string(m.id()).bytes(new byte[] {(byte) m.status().ordinal()});
                    out.string(m.version()).string(m.detail());
                }
            }
            case Accept a -> {
                out.varInt(2).bool(!a.join()).string(a.message()).varInt(a.routes().size());
                for (Route route : a.routes()) {
                    out.string(route.modId()).varInt(route.routeId());
                }
            }
            case Data d -> out.varInt(3).varInt(d.route()).varInt(d.channel()).bytes(d.payload());
            case Close c -> out.varInt(4).varInt(c.route()).string(c.reason());
        }
        return out.toByteArray();
    }

    public static MuxFrame decode(byte[] bytes) throws MalformedFrame {
        var in = new Reader(bytes);
        MuxFrame frame =
                switch (in.varInt()) {
                    case 0 -> {
                        int version = in.varInt();
                        String serverId = in.string();
                        int n = in.count(MAX_MODS);
                        var mods = new ArrayList<HelloMod>(n);
                        for (int i = 0; i < n; i++) {
                            String id = in.string();
                            String req = in.string();
                            String world = in.string();
                            boolean required = in.u8() != 0;
                            String protocol = in.string();
                            int c = in.count(MAX_CHANNELS);
                            var channels = new ArrayList<String>(c);
                            for (int j = 0; j < c; j++) {
                                channels.add(in.string());
                            }
                            mods.add(new HelloMod(id, req, world, required, protocol, List.copyOf(channels)));
                        }
                        yield new Hello(version, serverId, List.copyOf(mods));
                    }
                    case 1 -> {
                        int version = in.varInt();
                        int n = in.count(MAX_MODS);
                        var mods = new ArrayList<ReplyMod>(n);
                        for (int i = 0; i < n; i++) {
                            String id = in.string();
                            int status = in.u8();
                            if (status >= Status.values().length) {
                                throw new MalformedFrame("unknown status " + status);
                            }
                            mods.add(new ReplyMod(id, Status.values()[status], in.string(), in.string()));
                        }
                        yield new Reply(version, List.copyOf(mods));
                    }
                    case 2 -> {
                        boolean join = in.u8() == 0;
                        String message = in.string();
                        int n = in.count(MAX_MODS);
                        var routes = new ArrayList<Route>(n);
                        for (int i = 0; i < n; i++) {
                            routes.add(new Route(in.string(), in.varInt()));
                        }
                        yield new Accept(join, message, List.copyOf(routes));
                    }
                    case 3 -> {
                        int route = in.varInt();
                        int channel = in.varInt();
                        yield new Data(route, channel, in.rest());
                    }
                    case 4 -> new Close(in.varInt(), in.string());
                    default -> throw new MalformedFrame("unknown frame type");
                };
        in.end();
        return frame;
    }

    private static final class Writer extends ByteArrayOutputStream {
        Writer varInt(int value) {
            while ((value & ~0x7F) != 0) {
                write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            write(value);
            return this;
        }

        Writer string(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            varInt(b.length);
            return bytes(b);
        }

        Writer bool(boolean b) {
            write(b ? 1 : 0);
            return this;
        }

        Writer bytes(byte[] b) {
            write(b, 0, b.length);
            return this;
        }
    }

    private static final class Reader {
        private final byte[] buf;
        private int pos;

        Reader(byte[] buf) {
            this.buf = buf;
        }

        int u8() throws MalformedFrame {
            if (pos >= buf.length) {
                throw new MalformedFrame("truncated");
            }
            return buf[pos++] & 0xFF;
        }

        int varInt() throws MalformedFrame {
            int value = 0;
            for (int i = 0; i < 5; i++) {
                int b = u8();
                value |= (b & 0x7F) << (7 * i);
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new MalformedFrame("varint too long");
        }

        int count(int max) throws MalformedFrame {
            int n = varInt();
            if (n < 0 || n > max) {
                throw new MalformedFrame("count " + n + " outside 0.." + max);
            }
            return n;
        }

        String string() throws MalformedFrame {
            int len = count(MAX_STRING);
            if (buf.length - pos < len) {
                throw new MalformedFrame("truncated string");
            }
            try {
                String s = StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(buf, pos, len))
                        .toString();
                pos += len;
                return s;
            } catch (CharacterCodingException e) {
                throw new MalformedFrame("invalid utf-8");
            }
        }

        byte[] rest() {
            byte[] r = Arrays.copyOfRange(buf, pos, buf.length);
            pos = buf.length;
            return r;
        }

        void end() throws MalformedFrame {
            if (pos != buf.length) {
                throw new MalformedFrame("trailing bytes");
            }
        }
    }
}
