package dev.pumpkinmc.patch.jvm.guests;

import dev.pumpkinmc.patch.core.model.Event;
import dev.pumpkinmc.patch.core.model.HostError;
import dev.pumpkinmc.patch.core.model.Model.DrawCommand;
import dev.pumpkinmc.patch.core.model.Model.EventKind;
import dev.pumpkinmc.patch.core.model.Model.FrameInfo;
import dev.pumpkinmc.patch.core.model.Model.FrameOutput;
import dev.pumpkinmc.patch.core.model.Model.InitInfo;
import dev.pumpkinmc.patch.core.model.Model.InitResult;
import dev.pumpkinmc.patch.core.model.Model.PlayerSnapshot;
import dev.pumpkinmc.patch.core.runtime.Runtime.HostImports;
import dev.pumpkinmc.patch.jvm.JavaGuest;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Java port of {@code guests/hud-bench}: reads the player every tick and redraws a small panel. */
public final class HudBenchGuest extends JavaGuest {
    private long tick;
    private int slot;
    private String pos = "";

    public HudBenchGuest(HostImports host) {
        super(host);
    }

    @Override
    protected InitResult onInit(InitInfo info) {
        slot = (int) (info.session().sessionId() % 7);
        return new InitResult(Set.of(EventKind.TICK));
    }

    @Override
    protected void onEvents(List<Event> batch) {
        for (Event e : batch) {
            if (e instanceof Event.Tick t) {
                tick = t.gameTick();
            }
        }
        try {
            PlayerSnapshot p = host.view().localPlayer();
            pos = String.format(Locale.ROOT, "%.1f %.1f %.1f", p.pos().x(), p.pos().y(), p.pos().z());
        } catch (HostError e) {
            pos = e.code().toString();
        }
    }

    @Override
    protected FrameOutput onRender(FrameInfo frame) {
        int y = 40 + 12 * slot;
        return new FrameOutput.Commands(List.of(
                new DrawCommand.FillRect(2, y, 150, 11, 0x6000_0000),
                new DrawCommand.Text(4, y + 1, "bench t=" + tick + " " + pos, 0xFF55_FF55, false)));
    }
}
