package dev.pumpkinmc.patch.jvm.guests;

import dev.pumpkinmc.patch.core.model.Event;
import dev.pumpkinmc.patch.core.model.HostError;
import dev.pumpkinmc.patch.core.model.Model.DrawCommand;
import dev.pumpkinmc.patch.core.model.Model.EventKind;
import dev.pumpkinmc.patch.core.model.Model.FrameInfo;
import dev.pumpkinmc.patch.core.model.Model.FrameOutput;
import dev.pumpkinmc.patch.core.model.Model.InitInfo;
import dev.pumpkinmc.patch.core.model.Model.InitResult;
import dev.pumpkinmc.patch.core.runtime.Runtime.HostImports;
import dev.pumpkinmc.patch.core.runtime.Runtime.LogImports;
import dev.pumpkinmc.patch.jvm.JavaGuest;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Java port of {@code guests/ping}: the action asks the server for data, the reply is drawn. */
public final class PingGuest extends JavaGuest {
    private int nextSeq;
    private int sent;
    private List<String> lines = new ArrayList<>();
    private boolean dirty;

    public PingGuest(HostImports host) {
        super(host);
    }

    @Override
    protected InitResult onInit(InitInfo info) {
        host.log().log(LogImports.Level.INFO, "ping " + info.modVersion() + " started, session "
                + info.session().sessionId() + ", negotiated=" + info.session().netNegotiated());
        lines = new ArrayList<>(List.of(info.session().netNegotiated()
                ? "Press O to ask the server" : "No Pumpkin server route"));
        dirty = true;
        return new InitResult(Set.of(EventKind.INPUT));
    }

    @Override
    protected void onEvents(List<Event> batch) {
        for (Event event : batch) {
            if (event instanceof Event.Action a && a.actionId().equals("ping") && a.pressed()) {
                int seq = nextSeq++;
                try {
                    host.net().send("request",
                            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(seq).array());
                    sent++;
                } catch (HostError e) {
                    lines = new ArrayList<>(List.of("send failed: " + e.getMessage()));
                    dirty = true;
                }
            } else if (event instanceof Event.NetMessage m && m.channel().equals("response")
                    && m.payload().length >= 4) {
                int seq = ByteBuffer.wrap(m.payload()).order(ByteOrder.LITTLE_ENDIAN).getInt();
                String text = new String(m.payload(), 4, m.payload().length - 4, StandardCharsets.UTF_8);
                lines = new ArrayList<>();
                lines.add("Reply #" + Integer.toUnsignedString(seq) + " (" + sent + " sent)");
                lines.addAll(text.lines().toList());
                dirty = true;
            }
        }
    }

    @Override
    protected FrameOutput onRender(FrameInfo frame) {
        if (!dirty) {
            return new FrameOutput.Unchanged();
        }
        dirty = false;
        int width = 220;
        int x = frame.guiWidth() - width - 4;
        List<DrawCommand> commands = new ArrayList<>();
        commands.add(new DrawCommand.FillRect(x - 3, 3, width + 6, 14 + 10 * lines.size(), 0x9000_0000));
        commands.add(new DrawCommand.Text(x, 6, "Pumpkin Patch: example:ping", 0xFFFF_AA00, true));
        for (int i = 0; i < lines.size(); i++) {
            commands.add(new DrawCommand.Text(x, 16 + 10 * i, lines.get(i), 0xFFFF_FFFF, true));
        }
        return new FrameOutput.Commands(commands);
    }
}
