package dev.pumpkinmc.patch.jvm.guests;

import dev.pumpkinmc.patch.core.model.Event;
import dev.pumpkinmc.patch.core.model.Model.EventKind;
import dev.pumpkinmc.patch.core.model.Model.FrameInfo;
import dev.pumpkinmc.patch.core.model.Model.FrameOutput;
import dev.pumpkinmc.patch.core.model.Model.InitInfo;
import dev.pumpkinmc.patch.core.model.Model.InitResult;
import dev.pumpkinmc.patch.core.runtime.Runtime.HostImports;
import dev.pumpkinmc.patch.jvm.JavaGuest;
import java.util.List;
import java.util.Set;

/** Java port of {@code guests/host-calls}: 1000 calls to the cheapest host import per tick. */
public final class HostCallsGuest extends JavaGuest {
    private static final int CALLS = 1000;

    /** Published so the JIT cannot drop the loop. */
    public volatile int sink;

    public HostCallsGuest(HostImports host) {
        super(host);
    }

    @Override
    protected InitResult onInit(InitInfo info) {
        return new InitResult(Set.of(EventKind.TICK));
    }

    @Override
    protected void onEvents(List<Event> batch) {
        if (batch.stream().anyMatch(e -> e instanceof Event.Tick)) {
            int sum = 0;
            for (int i = 0; i < CALLS; i++) {
                sum += host.net().maxPayloadBytes();
            }
            sink = sum;
        }
    }

    @Override
    protected FrameOutput onRender(FrameInfo frame) {
        return new FrameOutput.Unchanged();
    }
}
