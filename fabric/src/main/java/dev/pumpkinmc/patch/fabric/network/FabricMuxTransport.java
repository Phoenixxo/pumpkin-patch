package dev.pumpkinmc.patch.fabric.network;

import dev.pumpkinmc.patch.core.PumpkinPatch;
import dev.pumpkinmc.patch.core.port.Ports;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends mux frames in whichever phase the connection is in. Receivers only enqueue. */
public final class FabricMuxTransport implements Ports.Transport {
    private static final Logger LOG = LoggerFactory.getLogger("PumpkinPatch");
    private volatile boolean play;

    public void configurationStarted() {
        play = false;
    }

    public void playStarted() {
        play = true;
    }

    @Override
    public void sendFrame(byte[] frame) {
        try {
            if (play) {
                ClientPlayNetworking.send(new MuxPayload(frame));
            } else {
                ClientConfigurationNetworking.send(new MuxPayload(frame));
            }
        } catch (IllegalStateException e) {
            LOG.warn("[PumpkinPatch] dropped a {} byte mux frame: {}", frame.length, e.getMessage());
        }
    }

    @Override
    public int maxOutboundFrameBytes() {
        return MuxPayload.MAX_SERVERBOUND;
    }

    public static void registerReceivers(PumpkinPatch patch) {
        ClientConfigurationNetworking.registerGlobalReceiver(MuxPayload.TYPE,
                (payload, context) -> patch.onFrameReceived(payload.frame()));
        ClientPlayNetworking.registerGlobalReceiver(MuxPayload.TYPE,
                (payload, context) -> patch.onFrameReceived(payload.frame()));
    }
}
