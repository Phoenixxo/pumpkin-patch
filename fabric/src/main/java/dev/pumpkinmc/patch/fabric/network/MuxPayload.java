package dev.pumpkinmc.patch.fabric.network;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** The one custom payload Pumpkin Patch registers. Its body is an opaque mux frame. */
public record MuxPayload(byte[] frame) implements CustomPacketPayload {
    public static final Type<MuxPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("pumpkin", "mux"));
    /** Vanilla's serverbound custom payload limit. */
    public static final int MAX_SERVERBOUND = 32_767;
    private static final int MAX_CLIENTBOUND = 1 << 20;

    public static final StreamCodec<ByteBuf, MuxPayload> CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBytes(payload.frame()),
            buf -> {
                int n = buf.readableBytes();
                if (n > MAX_CLIENTBOUND) {
                    throw new IllegalArgumentException("pumpkin:mux frame of " + n + " bytes");
                }
                byte[] bytes = new byte[n];
                buf.readBytes(bytes);
                return new MuxPayload(bytes);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Registers the payload for both phases and directions, before any receiver. */
    public static void register() {
        PayloadTypeRegistry.clientboundConfiguration().register(TYPE, CODEC);
        PayloadTypeRegistry.serverboundConfiguration().register(TYPE, CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
    }
}
