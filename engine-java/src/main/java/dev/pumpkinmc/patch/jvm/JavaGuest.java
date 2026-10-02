package dev.pumpkinmc.patch.jvm;

import dev.pumpkinmc.patch.core.model.Event;
import dev.pumpkinmc.patch.core.model.Model.FrameInfo;
import dev.pumpkinmc.patch.core.model.Model.FrameOutput;
import dev.pumpkinmc.patch.core.model.Model.InitInfo;
import dev.pumpkinmc.patch.core.model.Model.InitResult;
import dev.pumpkinmc.patch.core.runtime.Runtime.GuestInitError;
import dev.pumpkinmc.patch.core.runtime.Runtime.GuestInstance;
import dev.pumpkinmc.patch.core.runtime.Runtime.GuestTrap;
import dev.pumpkinmc.patch.core.runtime.Runtime.HostImports;
import dev.pumpkinmc.patch.core.runtime.Runtime.TrapKind;
import java.util.List;

/**
 * A Java port of a sample component. Subclasses write plain Java against the host imports, the
 * way a Fabric mod would; this base reports an escaping exception as a trap, like the Wasm binder.
 */
public abstract class JavaGuest implements GuestInstance {
    protected final HostImports host;

    protected JavaGuest(HostImports host) {
        this.host = host;
    }

    protected abstract InitResult onInit(InitInfo info) throws GuestInitError;

    protected abstract void onEvents(List<Event> batch);

    protected abstract FrameOutput onRender(FrameInfo frame);

    @Override
    public final InitResult init(InitInfo info) throws GuestTrap, GuestInitError {
        try {
            return onInit(info);
        } catch (RuntimeException | StackOverflowError e) {
            throw trap(e);
        }
    }

    /** Mirrors the Rust samples, whose {@code update} runs their event handling, then drawing. */
    @Override
    public final FrameOutput update(List<Event> events, FrameInfo frame) throws GuestTrap {
        try {
            onEvents(events);
            return onRender(frame);
        } catch (RuntimeException | StackOverflowError e) {
            throw trap(e);
        }
    }

    @Override
    public void shutdown() {}

    @Override
    public void close() {}

    private static GuestTrap trap(Throwable e) {
        return new GuestTrap(TrapKind.OTHER, e.toString(), e);
    }
}
