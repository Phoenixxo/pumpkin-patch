package dev.pumpkinmc.patch.core.port;

import dev.pumpkinmc.patch.core.diag.FaultRecord;
import dev.pumpkinmc.patch.core.model.Model.DrawCommand;
import dev.pumpkinmc.patch.core.model.Model.EntitySnapshot;
import dev.pumpkinmc.patch.core.model.Model.PlayerSnapshot;
import java.util.List;
import java.util.Optional;

/** The platform services core needs. The {@code fabric} module implements them. */
public record Ports(
        PlayerView playerView, HudCanvas hudCanvas, Transport transport, Clock clock, FaultReporter faults) {

    /** Read-only game state for the {@code view} imports. Called on the client thread only. */
    public interface PlayerView {
        Optional<PlayerSnapshot> localPlayer(int epoch);

        Optional<String> dimension();

        List<EntitySnapshot> entitiesNear(double radius, int max);
    }

    public interface HudCanvas {
        void draw(List<DrawCommand> commands);

        int measureText(String text);
    }

    /** Sends a {@code pumpkin:mux} frame. Inbound frames arrive through the facade. */
    public interface Transport {
        void sendFrame(byte[] frame);

        /** The largest frame the platform can send to the server. */
        int maxOutboundFrameBytes();
    }

    public interface Clock {
        long nanoTime();

        long gameTick();
    }

    public interface FaultReporter {
        void report(FaultRecord fault);
    }
}
